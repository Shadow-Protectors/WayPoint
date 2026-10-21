package com.shadowprotectors.alarmapp.engine

import kotlin.math.exp

/** Time-based smoothing makes the estimate independent of GPS callback frequency. */
class MotionSpeedEstimator {
    private var speed: Double? = null
    private var lastTimeMs: Long? = null

    fun reset() {
        speed = null
        lastTimeMs = null
    }

    fun update(timeMs: Long, measuredKmh: Double?): Double? {
        val previousTime = lastTimeMs
        if (previousTime != null && timeMs <= previousTime) return speed
        if (measuredKmh == null || !measuredKmh.isFinite() || measuredKmh < 0) {
            reset()
            return null
        }
        val elapsed = previousTime?.let { (timeMs - it) / 1000.0 }
        val previousSpeed = speed
        speed = if (previousSpeed == null || elapsed == null || elapsed > 60) measuredKmh else {
            val weight = 1 - exp(-elapsed / 20.0)
            previousSpeed + weight * (measuredKmh - previousSpeed)
        }
        lastTimeMs = timeMs
        return speed
    }
}
