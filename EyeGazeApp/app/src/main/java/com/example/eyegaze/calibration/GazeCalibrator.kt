package com.example.eyegaze.calibration

import android.util.Log
import com.example.eyegaze.model.CalibrationPoint
import com.example.eyegaze.model.CalibrationProfile
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

data class CalibrationStepResult(
    val progress: Float,
    val isStable: Boolean,
    val statusMessage: String,
    val dispersion: Float = 0f,
    val threshold: Float = 0f
)

class GazeCalibrator {
    val points: List<CalibrationPoint> = create5Points()
    var currentPointIndex: Int = 0
        private set

    val totalPoints: Int = points.size
    val currentPoint: CalibrationPoint?
        get() = if (currentPointIndex in points.indices) points[currentPointIndex] else null

    val isFinished: Boolean
        get() = currentPointIndex >= totalPoints

    var lastFailure: String = ""
        private set

    // --- tunables (radians) ---
    /** Time after a target appears during which nothing is recorded (eye is still travelling there). */
    private val SETTLE_MS = 800L
    /** Sliding window used to judge fixation. */
    private val WINDOW_N = 12
    /** Max distance of any sample in the window from the window mean. Wandering gaze exceeds this easily. */
    private val STABLE_RADIUS = 0.07f
    private val REQUIRED_SAMPLES = 24

    private val window = ArrayDeque<Pair<Float, Float>>()  // (pitch, yaw)
    private var pointShownAtMs = -1L
    private var logCounter = 0

    private fun progressOf(pt: CalibrationPoint) =
        (pt.recordedSamples.size.toFloat() / REQUIRED_SAMPLES).coerceIn(0f, 1f)

    fun addSample(pitch: Float, yaw: Float, isBlinking: Boolean, nowMs: Long): CalibrationStepResult {
        val pt = currentPoint ?: return CalibrationStepResult(1f, true, "校準完成")
        if (pointShownAtMs < 0) pointShownAtMs = nowMs

        if (isBlinking) {
            window.clear()
            return CalibrationStepResult(progressOf(pt), false, "偵測到眨眼，請睜開眼睛", 0f, STABLE_RADIUS)
        }

        // 1) settle phase: the eye is still moving to the new target, record nothing
        if (nowMs - pointShownAtMs < SETTLE_MS) {
            window.clear()
            return CalibrationStepResult(0f, false, "請將視線移到圓點上", 0f, STABLE_RADIUS)
        }

        // 2) sliding-window dispersion
        window.addLast(Pair(pitch, yaw))
        while (window.size > WINDOW_N) window.removeFirst()
        if (window.size < WINDOW_N) {
            return CalibrationStepResult(progressOf(pt), false, "正在鎖定視線...", 0f, STABLE_RADIUS)
        }
        val mp = window.map { it.first }.average().toFloat()
        val my = window.map { it.second }.average().toFloat()
        val dispersion = window.maxOf { hypot(it.first - mp, it.second - my) }

        if (++logCounter % 10 == 0) {
            Log.d("GazeCalibrator", "pt=$currentPointIndex pitch=$pitch yaw=$yaw dispersion=$dispersion (thr=$STABLE_RADIUS) n=${pt.recordedSamples.size}")
        }

        if (dispersion > STABLE_RADIUS) {
            return CalibrationStepResult(progressOf(pt), false, "視線不穩定，請專注凝視圓點", dispersion, STABLE_RADIUS)
        }

        // 3) only stable samples are recorded
        pt.recordedSamples.add(Pair(pitch, yaw))

        if (pt.recordedSamples.size >= REQUIRED_SAMPLES) {
            if (!collectedSetIsConsistent(pt)) {
                // eyes drifted around while the individual windows looked ok -> throw the point away
                Log.w("GazeCalibrator", "Point #$currentPointIndex rejected: inconsistent samples, redoing")
                pt.recordedSamples.clear()
                window.clear()
                pointShownAtMs = nowMs
                return CalibrationStepResult(0f, false, "資料不一致，請重新注視此點", dispersion, STABLE_RADIUS)
            }
            pt.computeAverage()
            Log.i("GazeCalibrator", "Point #$currentPointIndex done: pitch=${pt.averagePitch} yaw=${pt.averageYaw}")
            currentPointIndex++
            window.clear()
            pointShownAtMs = -1L
            return CalibrationStepResult(1f, true, "此點完成", dispersion, STABLE_RADIUS)
        }

        return CalibrationStepResult(progressOf(pt), true, "正在穩定採集...", dispersion, STABLE_RADIUS)
    }

    /** The whole recorded set must be tight AND must not drift between its first and second half. */
    private fun collectedSetIsConsistent(pt: CalibrationPoint): Boolean {
        val s = pt.recordedSamples
        val mp = s.map { it.first }.average().toFloat()
        val my = s.map { it.second }.average().toFloat()
        val std = sqrt(s.map { (it.first - mp) * (it.first - mp) + (it.second - my) * (it.second - my) }.average()).toFloat()

        val half = s.size / 2
        val aP = s.subList(0, half).map { it.first }.average().toFloat()
        val aY = s.subList(0, half).map { it.second }.average().toFloat()
        val bP = s.subList(half, s.size).map { it.first }.average().toFloat()
        val bY = s.subList(half, s.size).map { it.second }.average().toFloat()
        val drift = hypot(aP - bP, aY - bY)

        Log.i("GazeCalibrator", "set check: std=$std drift=$drift")
        return std <= STABLE_RADIUS * 0.7f && drift <= STABLE_RADIUS * 0.6f
    }

    fun computeProfile(): CalibrationProfile? {
        if (points.any { !it.isCompleted }) {
            lastFailure = "尚有點位未完成"
            return null
        }
        val c = points[0]; val tl = points[1]; val tr = points[2]; val bl = points[3]; val br = points[4]

        val pitchTop = (tl.averagePitch + tr.averagePitch) / 2f
        val pitchBottom = (bl.averagePitch + br.averagePitch) / 2f
        val pitchCenter = c.averagePitch

        val yawTopCenter = (tl.averageYaw + tr.averageYaw) / 2f
        val yawTopSpan = abs(tr.averageYaw - tl.averageYaw)

        val yawBottomCenter = (bl.averageYaw + br.averageYaw) / 2f
        val yawBottomSpan = abs(br.averageYaw - bl.averageYaw)

        // Direction: +1 if looking right produces more positive yaw, -1 if mirrored
        val yawDirection = if ((tr.averageYaw - tl.averageYaw) + (br.averageYaw - bl.averageYaw) >= 0f) 1.0f else -1.0f
        // Pitch Direction: +1 if looking bottom produces more positive pitch, -1 if inverted
        val pitchDirection = if (pitchBottom >= pitchTop) 1.0f else -1.0f

        val profile = CalibrationProfile(
            pitchTop = pitchTop,
            pitchBottom = pitchBottom,
            pitchCenter = pitchCenter,
            yawTopCenter = yawTopCenter,
            yawTopSpan = maxOf(yawTopSpan, 0.003f),
            yawBottomCenter = yawBottomCenter,
            yawBottomSpan = maxOf(yawBottomSpan, 0.003f),
            yawDirection = yawDirection,
            pitchDirection = pitchDirection,
            pointsCount = 5
        )

        Log.i("GazeCalibrator", "Calibrated Bilinear Profile: $profile (valid=${profile.isValid})")

        if (!profile.isValid) {
            lastFailure = "眼動幅度過小，請重新校準"
            return null
        }
        return profile
    }

    fun reset() {
        currentPointIndex = 0
        window.clear()
        pointShownAtMs = -1L
        points.forEach {
            it.recordedSamples.clear()
            it.averagePitch = 0f
            it.averageYaw = 0f
            it.isCompleted = false
        }
    }

    companion object {
        fun create5Points(): List<CalibrationPoint> = listOf(
            CalibrationPoint(0, 0.50f, 0.50f), // Center
            CalibrationPoint(1, 0.15f, 0.15f), // Top-Left
            CalibrationPoint(2, 0.85f, 0.15f), // Top-Right
            CalibrationPoint(3, 0.15f, 0.85f), // Bottom-Left
            CalibrationPoint(4, 0.85f, 0.85f)  // Bottom-Right
        )
    }
}
