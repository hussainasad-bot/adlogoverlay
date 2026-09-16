package com.ninedtechnologies.adlogoverlay

import kotlin.math.sqrt

/**
 * Decides whether accelerometer samples add up to a deliberate shake.
 *
 * Pure arithmetic with no Android types, so it is unit-tested on the JVM; [AdLogOverlay] feeds
 * it from a SensorEventListener.
 *
 * ## What counts
 *
 * A shake is several separate jolts in quick succession, so this counts RISING EDGES: the
 * moment the total acceleration crosses [thresholdG] from below. [peaksNeeded] of them inside
 * [windowMs] is a shake. Counting edges rather than samples is what rejects the false positives:
 *
 *  - a phone at rest reads 1g and never crosses;
 *  - one hard knock - setting the phone down, a drop - is a single edge, however many samples
 *    it spans;
 *  - a sustained push, like braking in a car, stays above the line and is still one edge;
 *  - noise jittering around the threshold makes edges only milliseconds apart, and
 *    [minPeakGapMs] collapses those into one.
 *
 * The defaults are tuned for a deliberate wrist shake. Every one is a constructor parameter.
 */
class AdLogShakeDetector(
    private val thresholdG: Float = 2.2f,
    private val peaksNeeded: Int = 3,
    private val windowMs: Long = 1_200L,
    private val minPeakGapMs: Long = 100L
) {

    private val peaks = ArrayDeque<Long>()
    private var lastPeakMs = Long.MIN_VALUE
    private var above = false

    /**
     * Feed one sample - acceleration in m/s² per axis, gravity included, exactly as
     * TYPE_ACCELEROMETER reports it. Returns true on the sample that completes a shake, then
     * starts counting afresh.
     */
    fun onSample(timeMs: Long, x: Float, y: Float, z: Float): Boolean {
        val g = sqrt(x * x + y * y + z * z) / STANDARD_GRAVITY
        val nowAbove = g >= thresholdG
        val risingEdge = nowAbove && !above
        above = nowAbove
        if (!risingEdge) return false

        if (lastPeakMs != Long.MIN_VALUE && timeMs - lastPeakMs < minPeakGapMs) return false
        lastPeakMs = timeMs

        peaks.addLast(timeMs)
        while (peaks.isNotEmpty() && timeMs - peaks.first() > windowMs) peaks.removeFirst()

        if (peaks.size >= peaksNeeded) {
            reset()
            return true
        }
        return false
    }

    fun reset() {
        peaks.clear()
        lastPeakMs = Long.MIN_VALUE
        above = false
    }

    private companion object {
        /** SensorManager.STANDARD_GRAVITY, restated so this class needs no Android import. */
        const val STANDARD_GRAVITY = 9.80665f
    }
}
