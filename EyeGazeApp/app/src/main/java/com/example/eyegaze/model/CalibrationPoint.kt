package com.example.eyegaze.model

data class CalibrationPoint(
    val id: Int,
    val normalizedX: Float,
    val normalizedY: Float,
    val recordedSamples: MutableList<Pair<Float, Float>> = mutableListOf(),
    var averagePitch: Float = 0f,
    var averageYaw: Float = 0f,
    var isCompleted: Boolean = false
) {
    /**
     * Computes the Interquartile Mean (IQR) across recorded samples.
     * Discards the top 25% and bottom 25% outliers on both pitch and yaw axes,
     * ensuring zero contamination from micro-saccades or momentary blinks.
     */
    fun computeAverage() {
        if (recordedSamples.isEmpty()) return

        if (recordedSamples.size < 6) {
            averagePitch = recordedSamples.map { it.first }.average().toFloat()
            averageYaw = recordedSamples.map { it.second }.average().toFloat()
            isCompleted = true
            return
        }

        val pitches = recordedSamples.map { it.first }.sorted()
        val yaws = recordedSamples.map { it.second }.sorted()

        val trimCount = (pitches.size * 0.25).toInt()
        val validPitches = pitches.subList(trimCount, pitches.size - trimCount)
        val validYaws = yaws.subList(trimCount, yaws.size - trimCount)

        averagePitch = validPitches.average().toFloat()
        averageYaw = validYaws.average().toFloat()
        isCompleted = true
    }
}
