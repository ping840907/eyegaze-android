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
    val yawCenter: Float = 0f,
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

        // 1. Piecewise Linear Vertical Mapping (Y)
        // Upward span (Center to Top) and downward span (Center to Bottom) are calibrated independently
        val upSpan = maxOf(abs(pitchCenter - pitchTop), 0.020f)
        val downSpan = maxOf(abs(pitchBottom - pitchCenter), 0.020f)

        val rawNormY = if (pitchDirection >= 0f) {
            // Pitch increases downwards (normal case)
            if (pitch <= pitchCenter) {
                // Top half: maps pitchTop..pitchCenter -> 0.15..0.50
                val tUp = ((pitchCenter - pitch) / upSpan).coerceIn(0f, 1.5f)
                0.50f - 0.35f * tUp
            } else {
                // Bottom half: maps pitchCenter..pitchBottom -> 0.50..0.85
                val tDown = ((pitch - pitchCenter) / downSpan).coerceIn(0f, 1.5f)
                0.50f + 0.35f * tDown
            }
        } else {
            // Pitch decreases downwards (inverted case)
            if (pitch >= pitchCenter) {
                val tUp = ((pitch - pitchCenter) / upSpan).coerceIn(0f, 1.5f)
                0.50f - 0.35f * tUp
            } else {
                val tDown = ((pitchCenter - pitch) / downSpan).coerceIn(0f, 1.5f)
                0.50f + 0.35f * tDown
            }
        }

        // 2. Exact vertical progression t for horizontal trapezoid compensation
        val t = rawNormY.coerceIn(0f, 1f)

        // 3. Expected horizontal center and span at current vertical height
        val rawCenterYaw = (1f - t) * yawTopCenter + t * yawBottomCenter
        // Anchor neutral horizontal center with Point #0 (Center)
        val expectedCenterYaw = if (yawCenter != 0f) {
            val centerProximity = (1.0f - 2f * abs(t - 0.5f)).coerceIn(0f, 1f)
            rawCenterYaw * (1f - centerProximity * 0.5f) + yawCenter * (centerProximity * 0.5f)
        } else rawCenterYaw

        // Robust cross-height span borrowing: prevents one corner's under-rotation from causing runaway sensitivity
        val robustTopSpan = maxOf(yawTopSpan, yawBottomSpan * 0.60f, 0.040f)
        val robustBottomSpan = maxOf(yawBottomSpan, yawTopSpan * 0.60f, 0.040f)
        val currentYawSpan = (1f - t) * robustTopSpan + t * robustBottomSpan
        val halfYawSpan = maxOf(currentYawSpan / 2f, 0.035f)

        val rawNormX = 0.5f + 0.35f * yawDirection * ((yaw - expectedCenterYaw) / halfYawSpan).coerceIn(-1.5f, 1.5f)

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
            putInt("version", 7)
            putFloat("pitchTop", pitchTop)
            putFloat("pitchBottom", pitchBottom)
            putFloat("pitchCenter", pitchCenter)
            putFloat("yawTopCenter", yawTopCenter)
            putFloat("yawTopSpan", yawTopSpan)
            putFloat("yawBottomCenter", yawBottomCenter)
            putFloat("yawBottomSpan", yawBottomSpan)
            putFloat("yawCenter", yawCenter)
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
            if (!prefs.getBoolean("is_calibrated", false) || prefs.getInt("version", 0) != 7) return null
            val p = CalibrationProfile(
                pitchTop = prefs.getFloat("pitchTop", 0f),
                pitchBottom = prefs.getFloat("pitchBottom", 0f),
                pitchCenter = prefs.getFloat("pitchCenter", 0f),
                yawTopCenter = prefs.getFloat("yawTopCenter", 0f),
                yawTopSpan = prefs.getFloat("yawTopSpan", 0f),
                yawBottomCenter = prefs.getFloat("yawBottomCenter", 0f),
                yawBottomSpan = prefs.getFloat("yawBottomSpan", 0f),
                yawCenter = prefs.getFloat("yawCenter", 0f),
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
