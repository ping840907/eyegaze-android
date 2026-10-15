package com.example.eyegaze.calibration

import kotlin.math.PI
import kotlin.math.abs

/**
 * 1€ (One Euro) Filter tuned for eye gaze tracking.
 * Features ultra-low cutoff at low velocities to eliminate jitter completely during fixation,
 * and high responsiveness during fast eye saccades.
 */
class OneEuroFilter(
    private var minCutoff: Float = 0.35f,
    private var beta: Float = 2.5f,
    private var dCutoff: Float = 1.0f
) {
    private var xPrev = 0f
    private var dxPrev = 0f
    private var tPrev = -1L

    fun filter(value: Float, timestampMs: Long = System.currentTimeMillis()): Float {
        if (value.isNaN() || value.isInfinite()) return xPrev

        if (tPrev < 0L) {
            xPrev = value
            dxPrev = 0f
            tPrev = timestampMs
            return value
        }

        val dt = (timestampMs - tPrev).toFloat() / 1000f
        tPrev = timestampMs

        // If called twice in the same frame/millisecond, preserve filtered state without resetting
        if (dt <= 0.001f) {
            return xPrev
        }

        if (dt > 1.0f) {
            xPrev = value
            dxPrev = 0f
            return value
        }

        // Derivative
        val dx = (value - xPrev) / dt
        val aD = smoothingFactor(dt, dCutoff)
        val dxHat = aD * dx + (1f - aD) * dxPrev
        dxPrev = dxHat

        // Dynamic cutoff frequency
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = smoothingFactor(dt, cutoff)
        val xHat = a * value + (1f - a) * xPrev
        xPrev = xHat

        return xHat
    }

    fun setParameters(newMinCutoff: Float, newBeta: Float) {
        minCutoff = newMinCutoff
        beta = newBeta
    }

    private fun smoothingFactor(dt: Float, cutoff: Float): Float {
        val r = 2f * PI.toFloat() * cutoff * dt
        return r / (r + 1f)
    }

    fun reset() {
        tPrev = -1L
        xPrev = 0f
        dxPrev = 0f
    }
}
