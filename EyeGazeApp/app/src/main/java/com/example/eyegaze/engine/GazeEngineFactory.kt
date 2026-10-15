package com.example.eyegaze.engine

import android.content.Context
import android.util.Log

object GazeEngineFactory {
    /**
     * Creates and initializes the EyeGaze inference engine.
     * Prioritizes the user's provided QNN DLC model (eyegaze.dlc).
     * Automatically falls back to TFLite (eyegaze.tflite) when proprietary Qualcomm runtime is absent.
     */
    fun createEngine(context: Context): EyeGazeInferenceEngine {
        // Priority 1: User's provided Qualcomm QNN DLC model
        val dlcEngine = QnnDlcInferenceEngine()
        if (dlcEngine.initialize(context)) {
            Log.i("GazeEngineFactory", "Successfully initialized user's provided Qualcomm QNN DLC engine.")
            return dlcEngine
        }

        // Priority 2: Universal TFLite fallback engine
        Log.i("GazeEngineFactory", "Activating TFLite engine fallback with identical EyeNet weights.")
        val tfliteEngine = TFLiteInferenceEngine()
        tfliteEngine.initialize(context)
        return tfliteEngine
    }
}
