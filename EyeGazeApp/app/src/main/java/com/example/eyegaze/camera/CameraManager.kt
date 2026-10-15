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
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
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

    // Crop geometry stabilization
    private var smoothAngle = -999f
    private var smoothCropW = -1f

    // Head pose stabilization
    private var smoothHeadYaw = 0f
    private var smoothHeadPitch = 0f
    private var hasHeadPoseInit = false

    // Output gaze stabilization
    private var smoothOutputYaw = 0f
    private var smoothOutputPitch = 0f
    private var hasOutputGazeInit = false

    private fun resetEyeSmoothing() {
        smoothAx = -1f
        smoothAy = -1f
        smoothBx = -1f
        smoothBy = -1f
        smoothAngle = -999f
        smoothCropW = -1f
        hasHeadPoseInit = false
        hasOutputGazeInit = false
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

        // 1. Smooth eye socket centers across frames to eliminate crop translation jitter
        if (smoothAx < 0f) {
            smoothAx = rawA.x
            smoothAy = rawA.y
            smoothBx = rawB.x
            smoothBy = rawB.y
        } else {
            val alpha = 0.25f
            smoothAx = smoothAx * (1f - alpha) + rawA.x * alpha
            smoothAy = smoothAy * (1f - alpha) + rawA.y * alpha
            smoothBx = smoothBx * (1f - alpha) + rawB.x * alpha
            smoothBy = smoothBy * (1f - alpha) + rawB.y * alpha
        }

        // 2. Smooth inter-eye crop angle and crop width to eliminate rotation jitter
        val dist = hypot(smoothBx - smoothAx, smoothBy - smoothAy)
        val rawAngle = atan2(smoothBy - smoothAy, smoothBx - smoothAx)
        val rawCropW = dist * 0.55f

        if (smoothAngle < -900f) {
            smoothAngle = rawAngle
            smoothCropW = rawCropW
        } else {
            val geoAlpha = 0.18f
            smoothAngle = smoothAngle * (1f - geoAlpha) + rawAngle * geoAlpha
            smoothCropW = smoothCropW * (1f - geoAlpha) + rawCropW * geoAlpha
        }

        val t0 = System.nanoTime()
        val ra = predictEye(frame, smoothAx, smoothAy, smoothAngle, smoothCropW)
        val rb = predictEye(frame, smoothBx, smoothBy, smoothAngle, smoothCropW)
        val ms = (System.nanoTime() - t0) / 1_000_000

        val lo = face.leftEyeOpenProbability
        val ro = face.rightEyeOpenProbability
        val blinking = lo != null && ro != null && lo < 0.22f && ro < 0.22f

        val eyeYaw = (ra.yaw + rb.yaw) / 2f
        val eyePitch = (ra.pitch + rb.pitch) / 2f

        // 3. Smooth head pose from ML Kit in radians
        val rawHeadYawRad = Math.toRadians(face.headEulerAngleY.toDouble()).toFloat()
        val rawHeadPitchRad = Math.toRadians(face.headEulerAngleX.toDouble()).toFloat()

        if (!hasHeadPoseInit) {
            smoothHeadYaw = rawHeadYawRad
            smoothHeadPitch = rawHeadPitchRad
            hasHeadPoseInit = true
        } else {
            val headAlpha = 0.20f
            smoothHeadYaw = smoothHeadYaw * (1f - headAlpha) + rawHeadYawRad * headAlpha
            smoothHeadPitch = smoothHeadPitch * (1f - headAlpha) + rawHeadPitchRad * headAlpha
        }

        // 4. Combined gaze vector
        val rawTotalYaw = eyeYaw + smoothHeadYaw * 0.30f
        val rawTotalPitch = eyePitch + smoothHeadPitch * 0.30f

        // 5. Pre-filter output gaze angles to feed clean, flutter-free signals to Calibration & Tracking
        if (!hasOutputGazeInit) {
            smoothOutputYaw = rawTotalYaw
            smoothOutputPitch = rawTotalPitch
            hasOutputGazeInit = true
        } else {
            val gazeAlpha = 0.35f
            smoothOutputYaw = smoothOutputYaw * (1f - gazeAlpha) + rawTotalYaw * gazeAlpha
            smoothOutputPitch = smoothOutputPitch * (1f - gazeAlpha) + rawTotalPitch * gazeAlpha
        }

        return GazePrediction(
            pitch = smoothOutputPitch,
            yaw = smoothOutputYaw,
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
