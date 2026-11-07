// LibrePods - AirPods liberated from Apple's ecosystem
// Copyright (C) 2025 LibrePods contributors
// GPL-3.0-or-later

package me.kavishdevar.librepods.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

class TempestRecenterTest {
    private val dtNs = 10_000_000L // 100 Hz
    private val identity = floatArrayOf(1f, 0f, 0f, 0f)

    private fun yawQ(rad: Float) = floatArrayOf(cos(rad / 2), 0f, 0f, sin(rad / 2))

    /** Rotation angle between two unit quaternions, rad. */
    private fun angle(a: FloatArray, b: FloatArray): Float = TempestRecenter.angle(a, b)

    @Test
    fun noTriggerWhileMoving() {
        val r = TempestRecenter()
        var ref = identity
        for (i in 0 until 3000) { // 30 s at 0.5 rad/s
            ref = r.update(i * dtNs, 0.5f, yawQ(i * 0.005f), ref)
        }
        assertEquals(0, r.rezeroCount)
        assertTrue(ref === identity)
    }

    @Test
    fun noTriggerJustAboveThreshold() {
        val r = TempestRecenter()
        for (i in 0 until 3000) r.update(i * dtNs, TempestConstants.QUIET_RATE_RAD_S + 1e-4f, identity, identity)
        assertEquals(0, r.rezeroCount)
    }

    @Test
    fun triggersAfterFiveSecondsQuiet() {
        val r = TempestRecenter()
        val cur = yawQ(0.785f)
        var firedAt = -1
        for (i in 0 until 700) {
            r.update(i * dtNs, 0.05f, cur, identity)
            if (r.rezeroStarted && firedAt < 0) firedAt = i
        }
        // first sample has no dt, so 5.0 s of quiet is reached at sample 500
        assertEquals(500, firedAt)
        assertEquals(1, r.rezeroCount)
    }

    @Test
    fun motionDrainsQuietTime() {
        val r = TempestRecenter()
        var firedAt = -1
        for (i in 0 until 1000) {
            val rate = if (i in 401..450) 1f else 0f // 0.5 s of motion after 4 s quiet
            r.update(i * dtNs, rate, identity, identity)
            if (r.rezeroStarted && firedAt < 0) firedAt = i
        }
        // 4.0 s quiet, drained to 3.5 s, then 1.5 s more quiet
        assertEquals(600, firedAt)
    }

    @Test
    fun gapRestartsTimer() {
        val r = TempestRecenter()
        var t = 0L
        repeat(400) { r.update(t, 0f, identity, identity); t += dtNs }
        t += 600_000_000L // 0.6 s gap
        repeat(400) { r.update(t, 0f, identity, identity); t += dtNs }
        assertEquals(0, r.rezeroCount)
    }

    private fun deg(d: Float) = d * PI.toFloat() / 180f

    /**
     * Holds still at [offsetRad] yaw from an identity reference until a re-zero fires, then keeps
     * feeding with frame gaps from [gapMs]. Returns (seconds since the re-zero, remaining offset)
     * per sample, and checks every step for overshoot and step > remaining offset.
     */
    private fun runSweep(
        offsetRad: Float, mode: SweepMode, seconds: Float, gapMs: () -> Long = { 10L }
    ): List<Pair<Float, Float>> {
        val r = TempestRecenter(mode = mode)
        val cur = yawQ(offsetRad)
        var ref = identity
        var t = 0L
        while (r.rezeroCount == 0) { ref = r.update(t, 0f, cur, ref); t += dtNs }
        val t0 = t - dtNs
        val out = ArrayList<Pair<Float, Float>>()
        var remaining = angle(ref, cur)
        while ((t - t0) / 1e9f < seconds) {
            val prev = ref
            ref = r.update(t, 0f, cur, ref)
            val rem = angle(ref, cur)
            assertTrue("overshoot/increase at ${(t - t0) / 1e9f}: $remaining -> $rem", rem <= remaining + 2e-5f)
            assertTrue("step larger than remaining offset", angle(prev, ref) <= remaining + 2e-5f)
            remaining = rem
            out.add((t - t0) / 1e9f to rem)
            t += gapMs() * 1_000_000L
        }
        return out
    }

    private fun timeBelow(trace: List<Pair<Float, Float>>, rad: Float) = trace.first { it.second <= rad }.first

    @Test
    fun sweepTable() {
        val d = 1e-4f
        assertEquals(10f, TempestRecenter.sweepDurationS(0f), d)
        assertEquals(10f, TempestRecenter.sweepDurationS(deg(3f)), d)
        assertEquals(10f, TempestRecenter.sweepDurationS(0.0872665f), d)
        assertEquals(5.25f, TempestRecenter.sweepDurationS(deg(20f)), 1e-3f) // halfway 5..35 deg
        assertEquals(0.5f, TempestRecenter.sweepDurationS(0.6108652f), d)
        assertEquals(0.5f, TempestRecenter.sweepDurationS(deg(90f)), d)
        assertEquals(0.5f, TempestRecenter.sweepDurationS(6.2831855f), d)
        assertEquals(1f, TempestRecenter.sweepDurationS(-0.01f), d)
        assertEquals(1f, TempestRecenter.sweepDurationS(7f), d)
        assertEquals(1f, TempestRecenter.sweepDurationS(Float.NaN), d)
    }

    @Test
    fun perFrameSmallOffsetDecaysWithTenSecondTimeConstant() {
        val trace = runSweep(deg(4f), SweepMode.PER_FRAME, 10.05f)
        val at10 = trace.last { it.first <= 10.0f }.second
        assertEquals(deg(4f) * exp(-1f), at10, deg(4f) * 0.02f)
    }

    @Test
    fun perFrameLargeOffsetFastThenSlow() {
        val trace = runSweep(deg(90f), SweepMode.PER_FRAME, 12f)
        // tau 0.5 s above 35 deg: 0.5 * ln(90/35) = 0.47 s
        assertEquals(0.47f, timeBelow(trace, deg(35f)), 0.03f)
        // then S grows along the table: 35 -> 20 deg takes ~1.7 s more, 90% (9 deg) ~7.5 s more
        assertEquals(2.2f, timeBelow(trace, deg(20f)), 0.15f)
        assertEquals(8.0f, timeBelow(trace, deg(9f)), 0.3f)
    }

    @Test
    fun perFrameNoOvershootWithBurstyArrival() {
        val rnd = java.util.Random(7)
        for (offset in listOf(deg(2f), deg(20f), deg(90f), deg(170f))) {
            val trace = runSweep(offset, SweepMode.PER_FRAME, 60f) { 5L + rnd.nextInt(146) } // 5..150 ms
            assertTrue(trace.last().second < deg(0.1f))
        }
    }

    @Test
    fun fixedModeIsLinearOverS() {
        val s = TempestRecenter.sweepDurationS(deg(20f)) // 5.25 s
        val trace = runSweep(deg(20f), SweepMode.FIXED_AT_START, s + 0.5f)
        for (frac in listOf(0.25f, 0.5f, 0.75f)) {
            val rem = trace.last { it.first <= frac * s }.second
            assertEquals(deg(20f) * (1f - frac), rem, deg(0.1f))
        }
        assertTrue(trace.last().second < 1e-3f)
        assertEquals(s, timeBelow(trace, 1e-3f), 0.02f)
    }

    @Test
    fun fixedModeNoOvershootWithBurstyArrival() {
        val rnd = java.util.Random(3)
        val trace = runSweep(deg(90f), SweepMode.FIXED_AT_START, 2f) { 5L + rnd.nextInt(146) }
        assertTrue(trace.last().second < 1e-3f)
    }

    @Test
    fun perFrameStepsAreSmallAtTenMs() {
        // step <= theta / S * dt <= theta * 0.01 / 0.5 per 10 ms frame
        val trace = runSweep(deg(90f), SweepMode.PER_FRAME, 3f)
        var prev = deg(90f)
        for ((_, rem) in trace) {
            assertTrue(prev - rem <= prev * 0.02f + 1e-5f)
            prev = rem
        }
    }

    @Test
    fun secondRezeroNeedsAnotherHorizon() {
        val r = TempestRecenter()
        var ref = identity
        val fired = ArrayList<Int>()
        for (i in 0 until 1300) {
            ref = r.update(i * dtNs, 0f, identity, ref)
            if (r.rezeroStarted) fired.add(i)
        }
        assertEquals(listOf(500, 1000), fired)
    }

    @Test
    fun resetClearsState() {
        val r = TempestRecenter()
        repeat(450) { r.update(it * dtNs, 0f, identity, identity) }
        r.reset()
        assertEquals(0f, r.quietS)
        var fired = -1
        for (i in 450 until 1100) {
            r.update(i * dtNs, 0f, identity, identity)
            if (r.rezeroStarted && fired < 0) fired = i
        }
        assertTrue("fired at $fired", fired in 950..951)
    }

    @Test
    fun musicHorizonIsConfigurable() {
        val r = TempestRecenter(horizonS = TempestConstants.MUSIC_HORIZON_S)
        var fired = -1
        for (i in 0 until 400) {
            r.update(i * dtNs, 0f, identity, identity)
            if (r.rezeroStarted && fired < 0) fired = i
        }
        assertTrue("fired at $fired", fired in 225..226)
    }

    @Test
    fun discontinuityCounterWraps() {
        assertEquals(2, TempestRecenter.nextDiscontinuity(1))
        assertEquals(0, TempestRecenter.nextDiscontinuity(255))
        var c = 1
        repeat(256) { c = TempestRecenter.nextDiscontinuity(c) }
        assertEquals(1, c)
    }

    @Test
    fun gyroScaling() {
        // 10 deg/s on one axis = 0.1745 rad/s = ~89 LSB
        assertEquals(0.1745f, TempestRecenter.gyroRateRadS(89, 0, 0), 0.002f)
        assertEquals(5f / 512f, TempestRecenter.gyroRateRadS(3, 4, 0), 1e-6f)
    }

    @Test
    fun slerpEndpointsAndHemisphere() {
        val b = yawQ(1f)
        val negB = floatArrayOf(-b[0], -b[1], -b[2], -b[3])
        assertTrue(angle(TempestRecenter.slerp(identity, b, 0f), identity) < 1e-4f)
        assertTrue(angle(TempestRecenter.slerp(identity, b, 1f), b) < 1e-3f)
        assertTrue(abs(angle(TempestRecenter.slerp(identity, negB, 0.5f), identity) - 0.5f) < 1e-3f)
    }
}
