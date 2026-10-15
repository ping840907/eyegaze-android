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

    private fun noFace() = GazePrediction(
        pitch = 0f, yaw = 0f, landmarks = null, inferenceTimeMs = 0L,
        faceDetected = false, isBlinking = false, engineType = engine.engineName
    )

    private fun runGaze(frame: Bitmap, face: Face): GazePrediction {
        val l = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val r = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        if (l == null || r == null) return noFace()

        // a = eye on the left of the image, b = eye on the right
        val a = if (l.x <= r.x) l else r
        val b = if (l.x <= r.x) r else l
        val dist = hypot(b.x - a.x, b.y - a.y)
        if (dist < 30f) return noFace()
        val angle = atan2(b.y - a.y, b.x - a.x)
        val cropW = dist * 0.55f

        val t0 = System.nanoTime()
        val ra = predictEye(frame, a, angle, cropW)
        val rb = predictEye(frame, b, angle, cropW)
        val ms = (System.nanoTime() - t0) / 1_000_000

        val lo = face.leftEyeOpenProbability
        val ro = face.rightEyeOpenProbability
        val blinking = lo != null && ro != null && lo < 0.22f && ro < 0.22f

        return GazePrediction(
            pitch = (ra.pitch + rb.pitch) / 2f,
            yaw = (ra.yaw + rb.yaw) / 2f,
            landmarks = ra.landmarks,
            inferenceTimeMs = ms,
            faceDetected = true,
            isBlinking = blinking,
            engineType = engine.engineName
        )
    }

    /**
     * Two passes: the first crop comes from the (jittery) face-detector eye position; the model's own
     * 34 eye landmarks then tell us where the eye really is, and we re-crop around that. This removes
     * the crop-position jitter that was being turned into gaze noise.
     */
    private fun predictEye(frame: Bitmap, p: PointF, angle: Float, cropW: Float): GazePrediction {
        var cx = p.x
        var cy = p.y
        val first = engine.predict(EyeRegionExtractor.extract(frame, cx, cy, angle, cropW))
        val lm = first.landmarks ?: return first

        // landmarks are (row, col) in the 48x80 heatmap grid
        var sr = 0f; var sc = 0f
        for (pt in lm) { sr += pt[0]; sc += pt[1] }
        val meanRow = sr / lm.size
        val meanCol = sc / lm.size
        val cropH = cropW * 0.6f
        val maxShift = cropW * 0.25f
        val dx = ((meanCol - 40f) / 80f * cropW).coerceIn(-maxShift, maxShift)
        val dy = ((meanRow - 24f) / 48f * cropH).coerceIn(-maxShift, maxShift)
        // rotate the shift from the crop frame back to image coordinates
        cx += dx * cos(angle) - dy * sin(angle)
        cy += dx * sin(angle) + dy * cos(angle)

        return engine.predict(EyeRegionExtractor.extract(frame, cx, cy, angle, cropW))
    }

    fun stopCamera() {
        cameraExecutor.shutdown()
        faceDetector.close()
    }
}
