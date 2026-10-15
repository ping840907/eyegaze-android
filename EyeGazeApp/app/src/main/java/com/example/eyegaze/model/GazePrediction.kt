package com.example.eyegaze.model

data class GazePrediction(
    val pitch: Float,
    val yaw: Float,
    val landmarks: Array<FloatArray>? = null,
    val inferenceTimeMs: Long = 0L,
    val faceDetected: Boolean = false,
    val isBlinking: Boolean = false,
    val engineType: String = "",
    val screenX: Float = -1f,
    val screenY: Float = -1f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GazePrediction
        if (pitch != other.pitch) return false
        if (yaw != other.yaw) return false
        if (isBlinking != other.isBlinking) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pitch.hashCode()
        result = 31 * result + yaw.hashCode()
        result = 31 * result + isBlinking.hashCode()
        return result
    }
}
