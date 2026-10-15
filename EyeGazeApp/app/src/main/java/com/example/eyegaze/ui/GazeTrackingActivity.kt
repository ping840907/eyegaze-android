package com.example.eyegaze.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.eyegaze.calibration.OneEuroFilter
import com.example.eyegaze.camera.CameraManager
import com.example.eyegaze.databinding.ActivityGazeTrackingBinding
import com.example.eyegaze.engine.EyeGazeInferenceEngine
import com.example.eyegaze.engine.GazeEngineFactory
import com.example.eyegaze.model.CalibrationProfile
import com.example.eyegaze.model.GazePrediction

class GazeTrackingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGazeTrackingBinding
    private lateinit var engine: EyeGazeInferenceEngine
    private var cameraManager: CameraManager? = null

    private var profile: CalibrationProfile? = null

    // Adaptive running baseline for uncalibrated mode
    private var runningBaselineYaw = 0f
    private var runningBaselinePitch = 0f
    private var hasBaseline = false

    // Angle EMA Pre-Filter to eliminate raw sensor jitter
    private var smoothYaw = 0f
    private var smoothPitch = 0f
    private var hasAngleInit = false

    // Screen coordinate 1€ Filters (Calm fixation tuning)
    private val filterX = OneEuroFilter(minCutoff = 0.22f, beta = 0.018f)
    private val filterY = OneEuroFilter(minCutoff = 0.22f, beta = 0.018f)

    // Sensitivity gain: 1.0 maps the calibration targets exactly onto their screen positions
    private var gainX = 1.0f
    private var gainY = 1.0f
    private val gainLevels = listOf(1.0f, 1.25f, 1.5f)
    private var currentGainIndex = 0

    // Smoothing Levels
    private var currentSmoothLevel = 0 // 0: High, 1: Medium, 2: Responsive

    // Last stable screen coordinates (used during blink freeze)
    private var lastValidScreenX = -1f
    private var lastValidScreenY = -1f

    private var lastFrameTime = 0L
    private var frameCount = 0
    private var currentFps = 30
    private var logCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGazeTrackingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profile = CalibrationProfile.load(this)
        engine = GazeEngineFactory.createEngine(this)

        val statusText = if (profile != null) "已載入穩定校準" else "自適應基準"
        binding.tvEngineBadge.text = "${engine.engineName} | $statusText"

        setupCamera()
        setupControls()
    }

    private fun setupCamera() {
        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            previewView = binding.cameraPreview,
            engine = engine
        ) { prediction ->
            runOnUiThread {
                handleGazePrediction(prediction)
            }
        }
        cameraManager?.startCamera()
    }

    private fun handleGazePrediction(prediction: GazePrediction) {
        val now = SystemClock.uptimeMillis()
        frameCount++
        if (now - lastFrameTime >= 1000L) {
            currentFps = frameCount
            frameCount = 0
            lastFrameTime = now
        }

        val screenW = binding.gazeOverlay.width.toFloat()
        val screenH = binding.gazeOverlay.height.toFloat()

        if (screenW <= 0f || screenH <= 0f) return

        if (!prediction.faceDetected) {
            binding.tvGazeCoords.text = "視線點位: 未檢測到臉部"
            binding.tvAngles.text = "請正對前置鏡頭"
            binding.gazeOverlay.updateGaze(-1f, -1f, null)
            return
        }

        // Freeze cursor during blink to prevent wild coordinate jump
        if (prediction.isBlinking && lastValidScreenX > 0 && lastValidScreenY > 0) {
            binding.tvAngles.text = "眨眼中 (位置暫留)"
            binding.gazeOverlay.updateGaze(lastValidScreenX, lastValidScreenY, prediction.landmarks)
            return
        }

        // 1. Angle-level EMA Smoothing (filters sensor jitter before mapping)
        if (!hasAngleInit) {
            smoothYaw = prediction.yaw
            smoothPitch = prediction.pitch
            hasAngleInit = true
        } else {
            val alpha = when (currentSmoothLevel) {
                0 -> 0.20f // Ultra-stable fixation
                1 -> 0.35f // Balanced
                else -> 0.55f // High speed
            }
            smoothYaw = smoothYaw * (1f - alpha) + prediction.yaw * alpha
            smoothPitch = smoothPitch * (1f - alpha) + prediction.pitch * alpha
        }

        // 2. Compute Screen (X, Y)
        val (rawX, rawY) = if (profile != null) {
            profile!!.mapGazeToScreen(smoothPitch, smoothYaw, screenW, screenH, gainX, gainY)
        } else {
            // Adaptive uncalibrated baseline
            if (!hasBaseline) {
                runningBaselineYaw = smoothYaw
                runningBaselinePitch = smoothPitch
                hasBaseline = true
            } else {
                runningBaselineYaw = runningBaselineYaw * 0.992f + smoothYaw * 0.008f
                runningBaselinePitch = runningBaselinePitch * 0.992f + smoothPitch * 0.008f
            }

            val dYaw = smoothYaw - runningBaselineYaw
            val dPitch = smoothPitch - runningBaselinePitch

            val defX = (0.5f + (dYaw / 0.35f) * gainX).coerceIn(0.04f, 0.96f) * screenW
            val defY = (0.5f + (dPitch / 0.30f) * gainY).coerceIn(0.04f, 0.96f) * screenH
            Pair(defX, defY)
        }

        // 3. Second-layer OneEuroFilter for silky cursor motion
        val finalX = filterX.filter(rawX, now)
        val finalY = filterY.filter(rawY, now)

        lastValidScreenX = finalX
        lastValidScreenY = finalY

        if (++logCounter % 30 == 0) {
            Log.d("GazeTracking", "Raw=(%.3f, %.3f), Smooth=(%.3f, %.3f) -> Screen=(%.1f, %.1f)".format(
                prediction.pitch, prediction.yaw, smoothPitch, smoothYaw, finalX, finalY
            ))
        }

        // Update Overlay View (Cursor, trail, landmarks, dwell targets)
        binding.gazeOverlay.updateGaze(finalX, finalY, prediction.landmarks)

        // Update HUD
        binding.tvGazeCoords.text = "視線點位: X: ${finalX.toInt()}, Y: ${finalY.toInt()}"
        binding.tvAngles.text = "Pitch: %.2f  Yaw: %.2f".format(smoothPitch, smoothYaw)
        binding.tvFpsLatency.text = "FPS: $currentFps | ${prediction.inferenceTimeMs} ms"
    }

    private fun setupControls() {
        // Sensitivity Toggle
        binding.btnSensitivity.setOnClickListener {
            currentGainIndex = (currentGainIndex + 1) % gainLevels.size
            gainX = gainLevels[currentGainIndex]
            gainY = gainX
            binding.btnSensitivity.text = "靈敏度: ${gainX}x"
        }
        binding.btnSensitivity.text = "靈敏度: ${gainX}x"

        // Smoothing Toggle
        binding.btnSmooth.setOnClickListener {
            currentSmoothLevel = (currentSmoothLevel + 1) % 3
            when (currentSmoothLevel) {
                0 -> {
                    filterX.setParameters(0.22f, 0.018f)
                    filterY.setParameters(0.22f, 0.018f)
                    binding.btnSmooth.text = "平滑: 高 (超穩定)"
                }
                1 -> {
                    filterX.setParameters(0.50f, 0.035f)
                    filterY.setParameters(0.50f, 0.035f)
                    binding.btnSmooth.text = "平滑: 中 (均衡)"
                }
                2 -> {
                    filterX.setParameters(1.0f, 0.06f)
                    filterY.setParameters(1.0f, 0.06f)
                    binding.btnSmooth.text = "平滑: 低 (靈敏)"
                }
            }
        }

        var isTrailEnabled = true
        binding.btnToggleTrail.setOnClickListener {
            isTrailEnabled = !isTrailEnabled
            binding.gazeOverlay.showTrail = isTrailEnabled
            binding.btnToggleTrail.text = if (isTrailEnabled) "軌跡 (開)" else "軌跡 (關)"
        }

        var isCameraVisible = true
        binding.btnToggleCamera.setOnClickListener {
            isCameraVisible = !isCameraVisible
            binding.cameraPreview.visibility = if (isCameraVisible) View.VISIBLE else View.GONE
            binding.btnToggleCamera.text = if (isCameraVisible) "相機 (開)" else "相機 (關)"
        }

        binding.btnRecalibrate.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
            finish()
        }

        binding.btnExit.setOnClickListener {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager?.stopCamera()
        engine.close()
    }
}
