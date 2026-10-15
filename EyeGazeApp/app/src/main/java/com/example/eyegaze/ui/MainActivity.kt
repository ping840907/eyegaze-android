package com.example.eyegaze.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.eyegaze.R
import com.example.eyegaze.databinding.ActivityMainBinding
import com.example.eyegaze.engine.GazeEngineFactory
import com.example.eyegaze.model.CalibrationProfile

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "相機權限已取得", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要相機權限以進行雙眼視線追蹤", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkCameraPermission()
        setupEngineInfo()
        setupListeners()

        if (intent.getBooleanExtra("selftest", false)) {
            Thread { com.example.eyegaze.engine.SelfTest.run(applicationContext) }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        updateCalibrationStatus()
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupEngineInfo() {
        // Initialize inference engine to detect whether Qualcomm DLC or TFLite is active
        val engine = GazeEngineFactory.createEngine(this)
        binding.tvActiveEngine.text = "運作推論引擎: ${engine.engineName} (${engine.modelFormat})"
        engine.close()
    }

    private fun updateCalibrationStatus() {
        val isCalibrated = CalibrationProfile.isCalibrated(this)
        if (isCalibrated) {
            val profile = CalibrationProfile.load(this)
            binding.tvCalibrationStatus.text = "狀態：已完成校準 (5點多項式映射已就緒)"
            binding.tvCalibrationStatus.setTextColor(ContextCompat.getColor(this, R.color.calibration_success))
            binding.tvCalibrationDetails.text = "校準點數: ${profile?.pointsCount ?: 5} 點 | 多項式係數已儲存於本地設定"
            binding.btnStartTracking.isEnabled = true
            binding.btnStartTracking.alpha = 1.0f
        } else {
            binding.tvCalibrationStatus.text = "狀態：尚未校準 (建議先進行校準以獲得精確視線座標)"
            binding.tvCalibrationStatus.setTextColor(ContextCompat.getColor(this, R.color.gaze_dot))
            binding.tvCalibrationDetails.text = "請點擊下方「進入校準頁面」，依照螢幕圓點指引完成 5 點注視校準。"
        }
    }

    private fun setupListeners() {
        binding.btnStartCalibration.setOnClickListener {
            if (hasCameraPermission()) {
                startActivity(Intent(this, CalibrationActivity::class.java))
            } else {
                checkCameraPermission()
            }
        }

        binding.btnStartTracking.setOnClickListener {
            if (hasCameraPermission()) {
                startActivity(Intent(this, GazeTrackingActivity::class.java))
            } else {
                checkCameraPermission()
            }
        }

        binding.btnResetCalibration.setOnClickListener {
            CalibrationProfile.clear(this)
            updateCalibrationStatus()
            Toast.makeText(this, "校準記錄已清除", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
}
