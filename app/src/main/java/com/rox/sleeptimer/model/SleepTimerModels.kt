package com.rox.sleeptimer.model

enum class TransitionMode {
    GRADUAL,
    STEP
}

data class VolumeStep(
    val id: Long,
    val minute: Int,
    val volumePercent: Int
)

data class TimerPreset(
    val id: String,
    val name: String,
    val transitionMode: TransitionMode,
    val pauseAtEnd: Boolean,
    val steps: List<VolumeStep>
)

data class TimerStatus(
    val isRunning: Boolean = false,
    val startVolumePercent: Int = 60,
    val currentVolumePercent: Int = 60,
    val elapsedSeconds: Int = 0,
    val totalSeconds: Int = 0,
    val nextStepMinute: Int? = null,
    val nextStepVolume: Int? = null,
    val lastFinishedAt: Long? = null
)

object VolumeScheduleMath {
    /**
     * Calcula el volumen multimedia objetivo (0..100) para un tiempo transcurrido en segundos,
     * partiendo de startVolumePercent en el minuto 0.
     */
    fun computeVolumePercent(
        elapsedSeconds: Double,
        startVolumePercent: Int,
        steps: List<VolumeStep>,
        mode: TransitionMode
    ): Int {
        val sorted = steps.filter { it.minute > 0 }.sortedBy { it.minute }
        if (sorted.isEmpty()) return startVolumePercent.coerceIn(0, 100)

        val elapsedMinutes = (elapsedSeconds / 60.0).coerceAtLeast(0.0)
        val last = sorted.last()
        if (elapsedMinutes >= last.minute) {
            return last.volumePercent.coerceIn(0, 100)
        }

        val points = buildList {
            add(0 to startVolumePercent.coerceIn(0, 100))
            sorted.forEach { add(it.minute to it.volumePercent.coerceIn(0, 100)) }
        }

        for (i in 0 until points.lastIndex) {
            val (m1, v1) = points[i]
            val (m2, v2) = points[i + 1]
            if (elapsedMinutes >= m1 && elapsedMinutes < m2) {
                if (mode == TransitionMode.STEP) {
                    return v1.coerceIn(0, 100)
                }
                val span = (m2 - m1).coerceAtLeast(1).toDouble()
                val ratio = ((elapsedMinutes - m1) / span).coerceIn(0.0, 1.0)
                return (v1 + (v2 - v1) * ratio).toInt().coerceIn(0, 100)
            }
        }

        return last.volumePercent.coerceIn(0, 100)
    }

    fun formatDuration(totalSeconds: Int): String {
        val safe = totalSeconds.coerceAtLeast(0)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val seconds = safe % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
