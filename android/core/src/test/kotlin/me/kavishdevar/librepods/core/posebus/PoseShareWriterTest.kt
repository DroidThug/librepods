// LibrePods - AirPods liberated from Apple's ecosystem
// Copyright (C) 2025 LibrePods contributors
// GPL-3.0-or-later

package me.kavishdevar.librepods.core.posebus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PoseShareWriterTest {
    /** ByteBuffer-backed region that records every store and fence in order. */
    private class FakeMemory : PoseMemory {
        val buf: ByteBuffer = ByteBuffer.allocate(PoseLayout.REGION_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val ops = ArrayList<String>()
        private val seqOff = PoseLayout.RECORD_OFFSET + PoseLayout.SEQ
        private fun log(off: Int) { ops += if (off == seqOff) "seq=${buf.getInt(seqOff)}" else "w$off" }
        override fun putInt(offset: Int, value: Int) { buf.putInt(offset, value); log(offset) }
        override fun putLong(offset: Int, value: Long) { buf.putLong(offset, value); log(offset) }
        override fun putFloat(offset: Int, value: Float) { buf.putFloat(offset, value); log(offset) }
        override fun getInt(offset: Int): Int = buf.getInt(offset)
        override fun fence() { ops += "fence" }
    }

    private val r = PoseLayout.RECORD_OFFSET

    @Test
    fun headerAndRecordBytes() {
        val m = FakeMemory()
        val w = PoseShareWriter(m)
        w.writePose(123_456_789_012L, 0L, 0.5f, -0.25f, 3.0f, 1.5f, -2f, 0.125f, 7)
        val b = m.buf
        // header: "POSE" as bytes, version 1, record size 64
        assertEquals('P'.code.toByte(), b.get(0)); assertEquals('O'.code.toByte(), b.get(1))
        assertEquals('S'.code.toByte(), b.get(2)); assertEquals('E'.code.toByte(), b.get(3))
        assertEquals(1, b.getInt(4)); assertEquals(64, b.getInt(8))
        // record at 64
        assertEquals(2, b.getInt(r))                                // one write: seq 0 -> 1 -> 2
        assertEquals(PoseLayout.FLAG_TRACKING_ACTIVE, b.getInt(r + 4))
        assertEquals(1, b.getInt(r + 8))                            // sampleCounter
        assertEquals(7, b.getInt(r + 12))                           // discontinuity
        assertEquals(123_456_789_012L, b.getLong(r + 16))
        assertEquals(0L, b.getLong(r + 24))
        assertEquals(0.5f, b.getFloat(r + 32)); assertEquals(-0.25f, b.getFloat(r + 36)); assertEquals(3.0f, b.getFloat(r + 40))
        assertEquals(1.5f, b.getFloat(r + 44)); assertEquals(-2f, b.getFloat(r + 48)); assertEquals(0.125f, b.getFloat(r + 52))
        assertEquals(0, b.getInt(r + 56)); assertEquals(0, b.getInt(r + 60))
        // explicit little-endian check of one field
        assertEquals(7.toByte(), b.get(r + 12))
        assertEquals(0.toByte(), b.get(r + 13))
        assertEquals(0x50.toByte(), b.get(0))   // magic 0x45534F50 stored low byte first
    }

    @Test
    fun seqlockSequence() {
        val m = FakeMemory()
        val w = PoseShareWriter(m)
        m.ops.clear()
        w.writePose(1L, 0L, 0f, 0f, 0f, 0f, 0f, 0f, 1)
        // seq odd, fence, all 11 field stores, fence, seq even
        assertEquals("seq=1", m.ops.first())
        assertEquals("fence", m.ops[1])
        assertEquals("fence", m.ops[m.ops.size - 2])
        assertEquals("seq=2", m.ops.last())
        val fields = m.ops.subList(2, m.ops.size - 2)
        assertEquals(11, fields.size)
        assertTrue(fields.all { it.startsWith("w") })

        m.ops.clear()
        w.setFlag(PoseLayout.FLAG_STREAM_STALLED, true)
        assertEquals(listOf("seq=3", "fence", "w${r + 4}", "fence", "seq=4"), m.ops)
        assertEquals(PoseLayout.FLAG_TRACKING_ACTIVE or PoseLayout.FLAG_STREAM_STALLED, m.buf.getInt(r + 4))

        m.ops.clear()
        w.setFlag(PoseLayout.FLAG_STREAM_STALLED, true)   // unchanged: no write
        assertTrue(m.ops.isEmpty())

        w.writePose(2L, 0L, 0f, 0f, 0f, 0f, 0f, 0f, 1)   // a pose clears the stall bit
        assertEquals(PoseLayout.FLAG_TRACKING_ACTIVE, m.buf.getInt(r + 4))
        assertEquals(2, m.buf.getInt(r + 8))
        assertEquals(6, m.buf.getInt(r))

        w.setFlag(PoseLayout.FLAG_TRACKING_ACTIVE, false)
        assertEquals(0, m.buf.getInt(r + 4))
        assertEquals(8, m.buf.getInt(r))
    }

    @Test
    fun bodyFrameFlagFollowsEachPose() {
        val m = FakeMemory()
        val w = PoseShareWriter(m)
        w.writePose(1L, 0L, 0f, 0f, 0f, 0f, 0f, 0f, 1, bodyFrame = true)
        assertEquals(PoseLayout.FLAG_TRACKING_ACTIVE or PoseLayout.FLAG_BODY_FRAME, m.buf.getInt(r + 4))
        w.setFlag(PoseLayout.FLAG_STREAM_STALLED, true)   // flag-only update keeps bit2
        assertEquals(
            PoseLayout.FLAG_TRACKING_ACTIVE or PoseLayout.FLAG_STREAM_STALLED or PoseLayout.FLAG_BODY_FRAME,
            m.buf.getInt(r + 4)
        )
        w.writePose(2L, 0L, 0f, 0f, 0f, 0f, 0f, 0f, 1)   // default frame clears it
        assertEquals(PoseLayout.FLAG_TRACKING_ACTIVE, m.buf.getInt(r + 4))
        assertEquals(4, PoseLayout.FLAG_BODY_FRAME)
    }

    @Test
    fun resumesFromAnOddSeqLeftInTheRegion() {
        val m = FakeMemory()
        m.buf.putInt(r, 41)   // e.g. a writer that died mid-update
        val w = PoseShareWriter(m)
        w.writePose(1L, 0L, 0f, 0f, 0f, 0f, 0f, 0f, 1)
        assertEquals(42, m.buf.getInt(r))
    }
}
