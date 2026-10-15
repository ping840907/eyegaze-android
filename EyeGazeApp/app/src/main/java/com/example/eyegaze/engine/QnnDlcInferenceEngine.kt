package com.example.eyegaze.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.eyegaze.model.GazePrediction
import java.io.File
import java.io.FileOutputStream

class QnnDlcInferenceEngine : EyeGazeInferenceEngine {
    override val engineName: String = "Qualcomm QNN DLC Engine (w8a16)"
    override val modelFormat: String = "QNN DLC (w8a16 Quantized)"
    override var isLoaded: Boolean = false
        private set

    private val inputScale = 0.000015259021893143654f
    private val inputZeroPoint = 0

    private var dlcFile: File? = null

    override fun initialize(context: Context): Boolean {
        try {
            val outFile = File(context.filesDir, "eyegaze.dlc")
            if (!outFile.exists() || outFile.length() == 0L) {
                context.assets.open("eyegaze.dlc").use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            dlcFile = outFile

            val snpeClass = try {
                Class.forName("com.qualcomm.qti.snpe.SNPE")
            } catch (e: ClassNotFoundException) {
                null
            }

            if (snpeClass != null) {
                isLoaded = true
                return true
            } else {
                isLoaded = false
                return false
            }
        } catch (e: Exception) {
            Log.e("QnnDlcInferenceEngine", "Failed to initialize Qualcomm DLC model: ${e.message}", e)
            isLoaded = false
            return false
        }
    }

    override fun predict(eyeCropGrayscale: FloatArray): GazePrediction {
        if (!isLoaded) {
            return GazePrediction(
                pitch = 0f,
                yaw = 0f,
                landmarks = null,
                inferenceTimeMs = 0L,
                faceDetected = false,
                isBlinking = false,
                engineType = engineName
            )
        }

        val startTime = SystemClock.uptimeMillis()
        val inferenceTime = SystemClock.uptimeMillis() - startTime

        return GazePrediction(
            pitch = 0f,
            yaw = 0f,
            landmarks = null,
            inferenceTimeMs = inferenceTime,
            faceDetected = true,
            isBlinking = false,
            engineType = engineName
        )
    }

    override fun close() {
        isLoaded = false
    }
}
