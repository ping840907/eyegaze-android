package com.example.eyegaze.engine

import android.content.Context
import com.example.eyegaze.model.GazePrediction

interface EyeGazeInferenceEngine {
    val engineName: String
    val isLoaded: Boolean
    val modelFormat: String

    fun initialize(context: Context): Boolean
    fun predict(eyeCropGrayscale: FloatArray): GazePrediction
    fun close()
}
