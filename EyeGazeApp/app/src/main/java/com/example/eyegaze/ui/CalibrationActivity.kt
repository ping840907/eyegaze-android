package com.example.eyegaze.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.eyegaze.calibration.GazeCalibrator
import com.example.eyegaze.camera.CameraManager
import com.example.eyegaze.databinding.ActivityCalibrationBinding
import com.example.eyegaze.engine.EyeGazeInferenceEngine
import com.example.eyegaze.engine.GazeEngineFactory

class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var engine: EyeGazeInferenceEngine
    private var cameraManager: CameraManager? = null

    private val calibrator = GazeCalibrator()

    private val stepNames = listOf(
        "【第 1/5 點】請注視螢幕「中央」圓點",
        "【第 2/5 點】請注視螢幕「左上角」圓點",
        "【第 3/5 點】請注視螢幕「右上角」圓點",
        "【第 4/5 點】請注視螢幕「左下角」圓點",
        "【第 5/5 點】請注視螢幕「右下角」圓點"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = GazeEngineFactory.createEngine(this)

        setupCamera()
        binding.btnCancel.setOnClickListener { finish() }
        updateTargetUi(0f, false)
    }

    private fun setupCamera() {
        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            previewView = binding.cameraPreview,
            engine = engine
        ) { prediction ->
            runOnUiThread {
                if (!isFinishing) {
                    handleGazePrediction(prediction.pitch, prediction.yaw, prediction.faceDetected, prediction.isBlinking)
                }
            }
        }
        cameraManager?.startCamera()
    }

    private fun handleGazePrediction(pitch: Float, yaw: Float, faceDetected: Boolean, isBlinking: Boolean) {
        if (calibrator.isFinished) return

        if (!faceDetected) {
            binding.tvInstruction.text = "⚠️ 請將臉部對準前置鏡頭並保持穩定"
            updateTargetUi(0f, false)
            return
        }

        val idx = calibrator.currentPointIndex.coerceIn(0, stepNames.size - 1)
        binding.tvCalibrationStep.text = stepNames[idx]

        val r = calibrator.addSample(pitch, yaw, isBlinking, SystemClock.uptimeMillis())
        binding.tvInstruction.text = r.statusMessage
        binding.tvLiveAngles.text = "Pitch %.3f  Yaw %.3f  |  擾動 %.3f / 門檻 %.3f".format(
            pitch, yaw, r.dispersion, r.threshold
        )
        updateTargetUi(r.progress, r.isStable)

        if (calibrator.isFinished) finishCalibration()
    }

    private fun updateTargetUi(progress: Float, isStable: Boolean) {
        binding.calibrationOverlay.updateTarget(
            calibrator.currentPoint,
            progress,
            calibrator.points.filter { it.isCompleted },
            isStable
        )
    }

    private fun finishCalibration() {
        val profile = calibrator.computeProfile()
        if (profile != null) {
            profile.save(this)
            Toast.makeText(this, "校準成功，進入視線跟隨", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, GazeTrackingActivity::class.java))
            finish()
        } else {
            Toast.makeText(this, calibrator.lastFailure, Toast.LENGTH_LONG).show()
            calibrator.reset()
            updateTargetUi(0f, false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager?.stopCamera()
        engine.close()
    }
}
