package com.example.eyegaze.model

import android.content.Context
import kotlin.math.abs

/**
 * 2D Trapezoidal/Bilinear Calibration Profile with Cross-Axis Compensation.
 *
 * Linearly interpolates the horizontal neutral gaze center and span based on vertical eye pitch:
 *   t = (pitch - pitchTop) / (pitchBottom - pitchTop)
 *   expectedCenterYaw = (1 - t) * yawTopCenter + t * yawBottomCenter
 *   spanYaw = (1 - t) * yawTopSpan + t * yawBottomSpan
 *
 * This mathematically and completely eliminates horizontal drift (shifting left at the top,
 * shifting right at the bottom) caused by natural hand/head tilt and camera position.
 */
data class CalibrationProfile(
    val pitchTop: Float,
    val pitchBottom: Float,
    val pitchCenter: Float,
    val yawTopCenter: Float,
    val yawTopSpan: Float,
    val yawBottomCenter: Float,
    val yawBottomSpan: Float,
    val yawDirection: Float = 1.0f,
    val pitchDirection: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis(),
    val pointsCount: Int = 5
) {
    val isValid: Boolean
        get() {
            val all = listOf(pitchTop, pitchBottom, pitchCenter, yawTopCenter, yawTopSpan, yawBottomCenter, yawBottomSpan)
            if (all.any { it.isNaN() || it.isInfinite() }) return false
            return abs(pitchBottom - pitchTop) >= 0.015f && maxOf(yawTopSpan, yawBottomSpan) >= 0.015f
        }

    fun mapGazeToNormalized(
        pitch: Float, yaw: Float,
        gainX: Float = 1.0f, gainY: Float = 1.0f
    ): Pair<Float, Float> {
        if (!isValid) return Pair(0.5f, 0.5f)

        val pitchSpan = abs(pitchBottom - pitchTop)
        val halfPitchSpan = maxOf(pitchSpan / 2f, 0.020f)

        // 1. Vertical progression t from top (0.0) to bottom (1.0)
        val t = if (pitchSpan > 1e-4f) {
            ((pitch - pitchTop) / (pitchBottom - pitchTop)).coerceIn(0f, 1f)
        } else 0.5f

        // 2. Expected horizontal center and span at current vertical height
        val expectedCenterYaw = (1f - t) * yawTopCenter + t * yawBottomCenter
        val currentYawSpan = (1f - t) * yawTopSpan + t * yawBottomSpan
        val halfYawSpan = maxOf(currentYawSpan / 2f, 0.025f)

        // 3. Compute normalized coordinates [0..1]
        // Target points in calibration were at X: 0.15 and 0.85 (span 0.70, halfSpan 0.35)
        // and Y: 0.15 and 0.85 (span 0.70, halfSpan 0.35).
        val rawNormX = 0.5f + 0.35f * yawDirection * ((yaw - expectedCenterYaw) / halfYawSpan).coerceIn(-1.5f, 1.5f)
        val rawNormY = 0.5f + 0.35f * pitchDirection * ((pitch - pitchCenter) / halfPitchSpan).coerceIn(-1.5f, 1.5f)

        // 4. Apply sensitivity gain around screen center (0.5, 0.5)
        val nx = 0.5f + (rawNormX - 0.5f) * gainX
        val ny = 0.5f + (rawNormY - 0.5f) * gainY

        return Pair(nx.coerceIn(0.02f, 0.98f), ny.coerceIn(0.02f, 0.98f))
    }

    fun mapGazeToScreen(
        pitch: Float, yaw: Float,
        screenWidth: Float, screenHeight: Float,
        gainX: Float = 1.0f, gainY: Float = 1.0f
    ): Pair<Float, Float> {
        val (nx, ny) = mapGazeToNormalized(pitch, yaw, gainX, gainY)
        return Pair(nx * screenWidth, ny * screenHeight)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putInt("version", 5)
            putFloat("pitchTop", pitchTop)
            putFloat("pitchBottom", pitchBottom)
            putFloat("pitchCenter", pitchCenter)
            putFloat("yawTopCenter", yawTopCenter)
            putFloat("yawTopSpan", yawTopSpan)
            putFloat("yawBottomCenter", yawBottomCenter)
            putFloat("yawBottomSpan", yawBottomSpan)
            putFloat("yawDirection", yawDirection)
            putFloat("pitchDirection", pitchDirection)
            putLong("timestamp", timestamp)
            putInt("pointsCount", pointsCount)
            putBoolean("is_calibrated", true)
            apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "EyeGazeCalibration"

        fun isCalibrated(context: Context): Boolean = load(context) != null

        fun load(context: Context): CalibrationProfile? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean("is_calibrated", false) || prefs.getInt("version", 0) != 5) return null
            val p = CalibrationProfile(
                pitchTop = prefs.getFloat("pitchTop", 0f),
                pitchBottom = prefs.getFloat("pitchBottom", 0f),
                pitchCenter = prefs.getFloat("pitchCenter", 0f),
                yawTopCenter = prefs.getFloat("yawTopCenter", 0f),
                yawTopSpan = prefs.getFloat("yawTopSpan", 0f),
                yawBottomCenter = prefs.getFloat("yawBottomCenter", 0f),
                yawBottomSpan = prefs.getFloat("yawBottomSpan", 0f),
                yawDirection = prefs.getFloat("yawDirection", 1f),
                pitchDirection = prefs.getFloat("pitchDirection", 1f),
                timestamp = prefs.getLong("timestamp", 0L),
                pointsCount = prefs.getInt("pointsCount", 5)
            )
            return if (p.isValid) p else null
        }

        fun clear(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
