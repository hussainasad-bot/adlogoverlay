package com.ninedtechnologies.adlogoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdLogShakeDetectorTest {

    private val g = 9.80665f
    private fun rest(d: AdLogShakeDetector, t: Long) = d.onSample(t, 0f, 0f, g)
    private fun jolt(d: AdLogShakeDetector, t: Long, times: Float = 3f) = d.onSample(t, 0f, 0f, g * times)

    /** A stroke: one high sample then back to rest, the way a real shake oscillates. */
    private fun stroke(d: AdLogShakeDetector, t: Long): Boolean {
        val fired = jolt(d, t)
        rest(d, t + 20)
        return fired
    }

    @Test
    fun aPhoneAtRestNeverShakes() {
        val d = AdLogShakeDetector()
        for (t in 0L..5_000L step 20) assertFalse(rest(d, t))
    }

    @Test
    fun threeStrokesInsideTheWindowIsAShake() {
        val d = AdLogShakeDetector()
        assertFalse(stroke(d, 0))
        assertFalse(stroke(d, 250))
        assertTrue(stroke(d, 500))
    }

    @Test
    fun twoStrokesAreNotEnough() {
        val d = AdLogShakeDetector()
        assertFalse(stroke(d, 0))
        assertFalse(stroke(d, 250))
        for (t in 300L..3_000L step 20) assertFalse(rest(d, t))
    }

    @Test
    fun strokesSpreadWiderThanTheWindowDoNotAddUp() {
        val d = AdLogShakeDetector()
        assertFalse(stroke(d, 0))
        assertFalse(stroke(d, 1_300))
        assertFalse(stroke(d, 2_600))
    }

    @Test
    fun aSustainedPushIsOneEdgeNotMany() {
        val d = AdLogShakeDetector()
        var fired = false
        for (t in 0L..1_000L step 20) fired = fired || jolt(d, t)
        assertFalse(fired)
    }

    @Test
    fun jitterAroundTheThresholdCollapsesIntoOnePeak() {
        val d = AdLogShakeDetector()
        var fired = false
        // Crossing every 40ms - faster than minPeakGapMs - is noise, not three jolts.
        for (i in 0 until 6) {
            val t = i * 40L
            fired = fired || if (i % 2 == 0) jolt(d, t, 2.3f) else rest(d, t)
        }
        assertFalse(fired)
    }

    @Test
    fun belowThresholdMovementIsIgnored() {
        val d = AdLogShakeDetector()
        var fired = false
        // Walking or a gentle wave: repeated 1.6g swings.
        for (i in 0 until 20) {
            val t = i * 150L
            fired = fired || jolt(d, t, 1.6f)
            rest(d, t + 20)
        }
        assertFalse(fired)
    }

    @Test
    fun countingStartsAfreshAfterAShake() {
        val d = AdLogShakeDetector()
        stroke(d, 0); stroke(d, 250)
        assertTrue(stroke(d, 500))
        assertFalse(stroke(d, 750))
        assertFalse(stroke(d, 1_000))
        assertTrue(stroke(d, 1_250))
    }

    @Test
    fun resetForgetsPartialProgress() {
        val d = AdLogShakeDetector()
        stroke(d, 0); stroke(d, 250)
        d.reset()
        assertFalse(stroke(d, 500))
        assertEquals(false, stroke(d, 750))
        assertTrue(stroke(d, 1_000))
    }
}
