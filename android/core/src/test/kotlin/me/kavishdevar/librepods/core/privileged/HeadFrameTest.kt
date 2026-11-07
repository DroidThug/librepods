// LibrePods - AirPods liberated from Apple's ecosystem
// Copyright (C) 2025 LibrePods contributors
// GPL-3.0-or-later

package me.kavishdevar.librepods.core.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class HeadFrameTest {
    private fun axisAngle(ax: Float, ay: Float, az: Float, a: Float): FloatArray {
        val n = sqrt(ax * ax + ay * ay + az * az)
        val s = sin(a / 2) / n
        return floatArrayOf(cos(a / 2), ax * s, ay * s, az * s)
    }

    private fun mul(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
        a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
        a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
        a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]
    )

    private fun conj(q: FloatArray) = floatArrayOf(q[0], -q[1], -q[2], -q[3])

    // Sensor mountings (sensor -> head body), arbitrary but with the ear axis not vertical.
    private val mountings = listOf(
        floatArrayOf(1f, 0f, 0f, 0f),
        axisAngle(0.3f, -0.8f, 0.5f, 1.1f),
        axisAngle(-0.7f, 0.2f, 0.4f, 2.4f),
        axisAngle(0.1f, 0.9f, -0.3f, -0.6f),
    )

    // Neutral head attitudes (head body -> world, z up): facing yaw, a bit of pitch and roll.
    private val neutrals = listOf(
        floatArrayOf(1f, 0f, 0f, 0f),
        mul(axisAngle(0f, 0f, 1f, 0.9f), axisAngle(1f, 0f, 0f, -0.25f)),
        mul(axisAngle(0f, 0f, 1f, -2.0f), axisAngle(0f, 1f, 0f, 0.2f)),
    )

    /** Sensor attitude for head attitude [head] and mounting [m]. */
    private fun sensorQ(head: FloatArray, m: FloatArray) = mul(head, m)

    /** Head +x (right ear) in sensor coordinates for mounting [m]. */
    private fun earAxis(m: FloatArray) = HeadFrame.rotate(conj(m), floatArrayOf(1f, 0f, 0f))

    @Test
    fun pureYawIsPureZ() {
        for (m in mountings) for (h0 in neutrals) for (yaw in listOf(0.3f, -1.2f, 2.5f)) {
            val ref = sensorQ(h0, m)
            val q = sensorQ(mul(axisAngle(0f, 0f, 1f, yaw), h0), m)
            val v = HeadFrame.headVector(ref, q, earAxis(m))!!
            assertEquals(0f, v[0], 1e-4f)
            assertEquals(0f, v[1], 1e-4f)
            assertEquals(yaw, v[2], 1e-4f)
        }
    }

    @Test
    fun nodAndTiltSigns() {
        for (m in mountings) {
            val h0 = axisAngle(0f, 0f, 1f, 0.7f) // level, facing some yaw
            val ref = sensorQ(h0, m)
            // nod down: about head +x (right) by a negative angle
            val nod = HeadFrame.headVector(ref, sensorQ(mul(h0, axisAngle(1f, 0f, 0f, -0.4f)), m), earAxis(m))!!
            assertEquals(-0.4f, nod[0], 1e-4f)
            assertEquals(0f, nod[1], 1e-4f)
            assertEquals(0f, nod[2], 1e-4f)
            // tilt right ear down: about head +y (forward) by a positive angle
            val tilt = HeadFrame.headVector(ref, sensorQ(mul(h0, axisAngle(0f, 1f, 0f, 0.3f)), m), earAxis(m))!!
            assertEquals(0f, tilt[0], 1e-4f)
            assertEquals(0.3f, tilt[1], 1e-4f)
            assertEquals(0f, tilt[2], 1e-4f)
            // turn left: positive z
            val left = HeadFrame.headVector(ref, sensorQ(mul(axisAngle(0f, 0f, 1f, 0.5f), h0), m), earAxis(m))!!
            assertEquals(0.5f, left[2], 1e-4f)
        }
    }

    @Test
    fun mirroredEarAxisFlipsPitchAndRollOnly() {
        for (m in mountings) for (h0 in neutrals) {
            val ref = sensorQ(h0, m)
            val ear = earAxis(m)
            val mir = HeadFrame.mirrored(ear)
            // nod down, tilt, and a combined turn + nod + tilt
            val poses = listOf(
                mul(h0, axisAngle(1f, 0f, 0f, -0.4f)),
                mul(h0, axisAngle(0f, 1f, 0f, 0.3f)),
                mul(axisAngle(0f, 0f, 1f, 0.9f), mul(h0, axisAngle(1f, 0.5f, 0f, -0.35f))),
            )
            for (h in poses) {
                val q = sensorQ(h, m)
                val a = HeadFrame.headVector(ref, q, ear)!!
                val b = HeadFrame.headVector(ref, q, mir)!!
                assertEquals(-a[0], b[0], 1e-5f)
                assertEquals(-a[1], b[1], 1e-5f)
                assertEquals(a[2], b[2], 1e-5f)
                val g = floatArrayOf(0.2f, -0.5f, 0.7f)
                val ra = HeadFrame.headRate(q, g, HeadFrame.basis(ref, ear)!!)
                val rb = HeadFrame.headRate(q, g, HeadFrame.basis(ref, mir)!!)
                assertEquals(-ra[0], rb[0], 1e-5f)
                assertEquals(-ra[1], rb[1], 1e-5f)
                assertEquals(ra[2], rb[2], 1e-5f)
            }
            // nod down with the mirrored axis now reads positive x
            if (h0 === neutrals[0]) {
                val nod = HeadFrame.headVector(ref, sensorQ(mul(h0, axisAngle(1f, 0f, 0f, -0.4f)), m), mir)!!
                assertEquals(0.4f, nod[0], 1e-4f)
            }
        }
    }

    @Test
    fun mirrorOffIsUnchanged() {
        val ear = earAxis(mountings[2])
        val twice = HeadFrame.mirrored(HeadFrame.mirrored(ear))
        for (i in 0..2) assertEquals(ear[i].toRawBits(), twice[i].toRawBits())
    }

    @Test
    fun yawRateUsesCurrentAttitude() {
        for (m in mountings) for (h0 in neutrals) {
            val ref = sensorQ(h0, m)
            val b = HeadFrame.basis(ref, earAxis(m))!!
            // head already turned 1 rad and nodded; now turning about world vertical at 0.8 rad/s
            val q = sensorQ(mul(axisAngle(0f, 0f, 1f, 1f), mul(h0, axisAngle(1f, 0f, 0f, -0.3f))), m)
            val gyroSensor = HeadFrame.rotate(conj(q), floatArrayOf(0f, 0f, 0.8f))
            val r = HeadFrame.headRate(q, gyroSensor, b)
            assertEquals(0f, r[0], 1e-4f)
            assertEquals(0f, r[1], 1e-4f)
            assertEquals(0.8f, r[2], 1e-4f)
        }
    }

    @Test
    fun verticalEarAxisFallsBack() {
        // head rolled 90 deg at neutral: ear axis vertical
        val ref = axisAngle(0f, 1f, 0f, 1.5708f)
        assertNull(HeadFrame.basis(ref, floatArrayOf(1f, 0f, 0f)))
        assertNotNull(HeadFrame.basis(floatArrayOf(1f, 0f, 0f, 0f), floatArrayOf(1f, 0f, 0f)))
    }

    @Test
    fun composesWithRecenterSweep() {
        // After a TempestRecenter sweep onto a turned, nodded pose, that pose reads as zero.
        val m = mountings[1]
        val h0 = neutrals[1]
        val cur = sensorQ(mul(axisAngle(0f, 0f, 1f, 0.8f), mul(h0, axisAngle(1f, 0f, 0f, -0.2f))), m)
        val r = TempestRecenter()
        var ref = sensorQ(h0, m)
        var t = 0L
        repeat(3000) { ref = r.update(t, 0f, cur, ref); t += 10_000_000L }
        val v = HeadFrame.headVector(ref, cur, earAxis(m))!!
        for (c in v) assertEquals(0f, c, 2e-3f)
        // and a further pure yaw from there is still pure z
        val q2 = mul(axisAngle(0f, 0f, 1f, 0.4f), cur)
        val v2 = HeadFrame.headVector(ref, q2, earAxis(m))!!
        assertEquals(0.4f, v2[2], 3e-3f)
        assertEquals(0f, v2[0], 3e-3f)
        assertEquals(0f, v2[1], 3e-3f)
    }
}
