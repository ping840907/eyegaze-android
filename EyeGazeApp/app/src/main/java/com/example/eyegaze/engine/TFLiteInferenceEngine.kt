package com.example.eyegaze.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.eyegaze.model.GazePrediction
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class TFLiteInferenceEngine : EyeGazeInferenceEngine {
    override val engineName: String = "TensorFlow Lite Engine (EyeNet)"
    override val modelFormat: String = "TFLite (float32)"
    override var isLoaded: Boolean = false
        private set

    private var interpreter: Interpreter? = null

    // Dynamic output tensor indices
    private var pitchYawTensorIndex = -1
    private var landmarksTensorIndex = -1
    private var heatmapsTensorIndex = -1

    // Preallocated buffers
    private var outputPitchYaw = Array(1) { FloatArray(2) }
    private var outputLandmarks = Array(1) { Array(34) { FloatArray(2) } }

    override fun initialize(context: Context): Boolean {
        try {
            val assetManager = context.assets
            val fileDescriptor = assetManager.openFd("eyegaze.tflite")
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val modelByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(false)
            }

            val interp = Interpreter(modelByteBuffer, options)
            interpreter = interp

            val outputCount = interp.outputTensorCount
            Log.i("TFLiteEngine", "Total output tensors: $outputCount")

            for (i in 0 until outputCount) {
                val tensor = interp.getOutputTensor(i)
                val shape = tensor.shape()
                val name = tensor.name().lowercase()
                Log.i("TFLiteEngine", "Output #$i: name='$name', shape=${shape.contentToString()}")

                if (shape.contentEquals(intArrayOf(1, 2)) || name.contains("gaze") || name.contains("pitch")) {
                    pitchYawTensorIndex = i
                    Log.i("TFLiteEngine", "-> Identified Output #$i as gaze_pitchyaw [1, 2]")
                } else if (shape.contentEquals(intArrayOf(1, 34, 2)) || name.contains("landmark")) {
                    landmarksTensorIndex = i
                    Log.i("TFLiteEngine", "-> Identified Output #$i as landmarks [1, 34, 2]")
                } else if (shape.size == 5 || name.contains("heat")) {
                    heatmapsTensorIndex = i
                    Log.i("TFLiteEngine", "-> Identified Output #$i as heatmaps [1, 3, 34, 48, 80]")
                }
            }

            if (pitchYawTensorIndex == -1) {
                for (i in 0 until outputCount) {
                    if (interp.getOutputTensor(i).shape().contentEquals(intArrayOf(1, 2))) {
                        pitchYawTensorIndex = i
                        break
                    }
                }
            }

            isLoaded = true
            Log.i("TFLiteEngine", "eyegaze.tflite loaded successfully. PitchYaw index: $pitchYawTensorIndex")
            return true
        } catch (e: Exception) {
            Log.e("TFLiteEngine", "Error loading eyegaze.tflite: ${e.message}", e)
            isLoaded = false
            return false
        }
    }

    override fun predict(eyeCropGrayscale: FloatArray): GazePrediction {
        val interp = interpreter ?: return GazePrediction(
            pitch = 0f,
            yaw = 0f,
            landmarks = null,
            inferenceTimeMs = 0L,
            faceDetected = false,
            isBlinking = false,
            engineType = engineName
        )

        val startTime = SystemClock.uptimeMillis()

        val inputBuffer = ByteBuffer.allocateDirect(1 * 96 * 160 * 4).apply {
            order(ByteOrder.nativeOrder())
            rewind()
            for (v in eyeCropGrayscale) {
                putFloat(v)
            }
        }

        // Heatmaps (1.5 MB per run) are not needed; only request the two small outputs.
        val outputsMap = mutableMapOf<Int, Any>()
        if (pitchYawTensorIndex >= 0) {
            outputsMap[pitchYawTensorIndex] = outputPitchYaw
        }
        if (landmarksTensorIndex >= 0) {
            outputsMap[landmarksTensorIndex] = outputLandmarks
        }

        try {
            interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputsMap)
        } catch (e: Exception) {
            Log.e("TFLiteEngine", "Inference error: ${e.message}", e)
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

        val inferenceTime = SystemClock.uptimeMillis() - startTime
        val pitch = outputPitchYaw[0][0]
        val yaw = outputPitchYaw[0][1]

        val landmarksCopy = if (landmarksTensorIndex >= 0) {
            Array(34) { i ->
                floatArrayOf(outputLandmarks[0][i][0], outputLandmarks[0][i][1])
            }
        } else {
            null
        }

        return GazePrediction(
            pitch = pitch,
            yaw = yaw,
            landmarks = landmarksCopy,
            inferenceTimeMs = inferenceTime,
            faceDetected = true,
            isBlinking = false,
            engineType = engineName
        )
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
    }
}
