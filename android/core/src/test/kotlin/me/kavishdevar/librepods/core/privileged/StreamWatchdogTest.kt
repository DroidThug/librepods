// LibrePods - AirPods liberated from Apple's ecosystem
// Copyright (C) 2025 LibrePods contributors
// GPL-3.0-or-later

package me.kavishdevar.librepods.core.privileged

import me.kavishdevar.librepods.core.privileged.StreamWatchdog.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamWatchdogTest {
    /** Runs checks every 500 ms from [from] to [to] (exclusive); returns (time, event) pairs. */
    private fun checks(w: StreamWatchdog, from: Long, to: Long, active: Boolean = true) =
        (from until to step 500).flatMap { t -> w.check(t, active).map { t to it } }

    @Test
    fun noFalseStallAtHundredMsPackets() {
        val w = StreamWatchdog()
        val out = ArrayList<Event>()
        for (t in 0L until 60_000L step 100) {
            w.onPacket(t)?.let { out.add(it) }
            if (t % 500 == 0L) out.addAll(w.check(t, true))
        }
        assertTrue(out.isEmpty())
    }

    @Test
    fun noFalseStallWithBurstyArrival() {
        val w = StreamWatchdog()
        var t = 0L
        val out = ArrayList<Event>()
        val gaps = longArrayOf(8, 8, 33, 132, 8, 400, 8, 900, 10)
        var nextCheck = 0L
        repeat(2000) { i ->
            t += gaps[i % gaps.size]
            while (nextCheck <= t) { out.addAll(w.check(nextCheck, true)); nextCheck += 500 }
            w.onPacket(t)?.let { out.add(it) }
        }
        assertTrue(out.toString(), out.isEmpty())
    }

    @Test
    fun stallThenBackoff() {
        val w = StreamWatchdog()
        w.onPacket(0)
        w.check(0, true)
        val ev = checks(w, 500, 60_000)
        val stall = ev.filter { it.second is Event.Stall }
        assertEquals(1, stall.size)
        assertEquals(2_000L, stall[0].first) // first check with gap > 1.5 s
        val req = ev.filter { it.second == Event.Request }.map { it.first }
        // at the stall, then +3, +6, +12, +15, +15 ... s
        assertEquals(listOf(2_000L, 5_000L, 11_000L, 23_000L, 38_000L, 53_000L), req)
    }

    @Test
    fun stallMeasuredFromArmingWhenNoPacketYet() {
        val w = StreamWatchdog()
        val ev = checks(w, 10_000, 13_000)
        assertEquals(Event.Stall(2_000), ev.first().second)
        assertEquals(12_000L, ev.first().first)
    }

    @Test
    fun resumeAfterGap() {
        val w = StreamWatchdog()
        assertNull(w.onPacket(0))
        assertNull(w.onPacket(1_900))
        val r = w.onPacket(4_000)
        assertEquals(Event.Resume(2_100), r)
        assertNull(w.onPacket(4_010))
    }

    @Test
    fun resumeAfterStallResetsBackoff() {
        val w = StreamWatchdog()
        w.onPacket(0)
        w.check(0, true)
        checks(w, 500, 12_000) // stalled, backoff grown
        assertEquals(Event.Resume(12_000), w.onPacket(12_000))
        // a new stall starts again at the 3 s minimum
        val ev = checks(w, 12_500, 20_000)
        val req = ev.filter { it.second == Event.Request }.map { it.first }
        assertEquals(listOf(14_000L, 17_000L), req)
    }

    @Test
    fun nothingWhileInactiveAndRearmsAfter() {
        val w = StreamWatchdog()
        w.onPacket(0)
        assertTrue(checks(w, 0, 30_000, active = false).isEmpty())
        // becoming active re-arms: no stall until 1.5 s after the first active check
        val ev = checks(w, 30_000, 33_000)
        assertEquals(32_000L, ev.first().first)
        assertTrue(ev.first().second is Event.Stall)
    }

    @Test
    fun disconnectMidStallStopsRequests() {
        val w = StreamWatchdog()
        w.onPacket(0)
        checks(w, 0, 5_000)
        assertTrue(checks(w, 5_000, 40_000, active = false).isEmpty())
    }
}
