package com.example.eyegaze.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.eyegaze.engine.EyeGazeInferenceEngine
import com.example.eyegaze.model.GazePrediction
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView?,
    private val engine: EyeGazeInferenceEngine,
    private val onGazePredicted: (GazePrediction) -> Unit
) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )

    @Volatile private var isProcessing = false

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = previewView?.let {
                Preview.Builder().build().also { p -> p.setSurfaceProvider(it.surfaceProvider) }
            }

            // Higher resolution => more pixels on the eye => far less noise in the model input
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { proxy -> processImageProxy(proxy) } }

            try {
                cameraProvider.unbindAll()
                if (preview != null) {
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalyzer)
                } else {
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalyzer)
                }
                Log.i("CameraManager", "CameraX started (front camera, target 1280x720)")
            } catch (e: Exception) {
                Log.e("CameraManager", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }
        isProcessing = true

        // Rotate to upright ONLY. The model must see an un-mirrored image; mirroring is handled
        // by the calibration (it learns the sign of yaw itself).
        val raw = imageProxy.toBitmap()
        val rot = imageProxy.imageInfo.rotationDegrees
        val upright = if (rot != 0) {
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        } else raw

        faceDetector.process(InputImage.fromBitmap(upright, 0))
            .addOnSuccessListener { faces ->
                val face = faces.firstOrNull()
                if (face == null) {
                    onGazePredicted(noFace())
                } else {
                    onGazePredicted(runGaze(upright, face))
                }
            }
            .addOnFailureListener { e ->
                Log.e("CameraManager", "Face detection failed: ${e.message}")
                onGazePredicted(noFace())
            }
            .addOnCompleteListener {
                if (upright !== raw) upright.recycle()
                raw.recycle()
                imageProxy.close()
                isProcessing = false
            }
    }

    // Eye socket stabilization filters
    private var smoothAx = -1f
    private var smoothAy = -1f
    private var smoothBx = -1f
    private var smoothBy = -1f

    private fun resetEyeSmoothing() {
        smoothAx = -1f
        smoothAy = -1f
        smoothBx = -1f
        smoothBy = -1f
    }

    private fun noFace(): GazePrediction {
        resetEyeSmoothing()
        return GazePrediction(
            pitch = 0f, yaw = 0f, landmarks = null, inferenceTimeMs = 0L,
            faceDetected = false, isBlinking = false, engineType = engine.engineName
        )
    }

    private fun runGaze(frame: Bitmap, face: Face): GazePrediction {
        val l = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val r = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        if (l == null || r == null) return noFace()

        // a = eye on the left of the image, b = eye on the right
        val rawA = if (l.x <= r.x) l else r
        val rawB = if (l.x <= r.x) r else l
        val rawDist = hypot(rawB.x - rawA.x, rawB.y - rawA.y)
        if (rawDist < 30f) return noFace()

        // Smooth eye socket centers across frames to eliminate crop jitter without moving with the pupil
        if (smoothAx < 0f) {
            smoothAx = rawA.x
            smoothAy = rawA.y
            smoothBx = rawB.x
            smoothBy = rawB.y
        } else {
            val alpha = 0.35f
            smoothAx = smoothAx * (1f - alpha) + rawA.x * alpha
            smoothAy = smoothAy * (1f - alpha) + rawA.y * alpha
            smoothBx = smoothBx * (1f - alpha) + rawB.x * alpha
            smoothBy = smoothBy * (1f - alpha) + rawB.y * alpha
        }

        val dist = hypot(smoothBx - smoothAx, smoothBy - smoothAy)
        val angle = atan2(smoothBy - smoothAy, smoothBx - smoothAx)
        val cropW = dist * 0.55f

        val t0 = System.nanoTime()
        val ra = predictEye(frame, smoothAx, smoothAy, angle, cropW)
        val rb = predictEye(frame, smoothBx, smoothBy, angle, cropW)
        val ms = (System.nanoTime() - t0) / 1_000_000

        val lo = face.leftEyeOpenProbability
        val ro = face.rightEyeOpenProbability
        val blinking = lo != null && ro != null && lo < 0.22f && ro < 0.22f

        val eyeYaw = (ra.yaw + rb.yaw) / 2f
        val eyePitch = (ra.pitch + rb.pitch) / 2f

        // Head pose from ML Kit in radians (natural head turn/tilt)
        val headYawRad = Math.toRadians(face.headEulerAngleY.toDouble()).toFloat()
        val headPitchRad = Math.toRadians(face.headEulerAngleX.toDouble()).toFloat()

        // Physiologically combined gaze: Eye gaze + Head pose contribution
        val totalYaw = eyeYaw + headYawRad * 0.30f
        val totalPitch = eyePitch + headPitchRad * 0.30f

        return GazePrediction(
            pitch = totalPitch,
            yaw = totalYaw,
            landmarks = ra.landmarks,
            inferenceTimeMs = ms,
            faceDetected = true,
            isBlinking = blinking,
            engineType = engine.engineName
        )
    }

    /**
     * Single inference pass per eye centered on the smoothed eye socket center.
     * The model directly measures pupil displacement relative to the socket without feedback collapse.
     */
    private fun predictEye(frame: Bitmap, cx: Float, cy: Float, angle: Float, cropW: Float): GazePrediction {
        return engine.predict(EyeRegionExtractor.extract(frame, cx, cy, angle, cropW))
    }

    fun stopCamera() {
        cameraExecutor.shutdown()
        faceDetector.close()
    }
}
