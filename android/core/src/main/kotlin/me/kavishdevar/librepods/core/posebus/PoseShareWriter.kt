// LibrePods - AirPods liberated from Apple's ecosystem
// Copyright (C) 2025 LibrePods contributors
// GPL-3.0-or-later

package me.kavishdevar.librepods.core.posebus

import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pose bus memory layout v1 (little-endian). Documented in HEADTRACKING_FORK_NOTES.md ("Pose bus").
 *
 * Header at 0: u32 magic 'POSE' (0x45534F50), u32 layoutVersion (1), u32 recordSize (64).
 * Record at [RECORD_OFFSET] (64-byte aligned), offsets relative to the record:
 *  0 u32 seq (seqlock: odd while writing, even when stable)   4 u32 flags   8 u32 sampleCounter   12 u32 discontinuity
 * 16 i64 arrivalElapsedRealtimeNanos   24 i64 sensorTimestampNanos (0 = unknown)
 * 32 f32 rotvec[3] (rad, Android head frame x right / y forward / z up)   44 f32 angvel[3] (rad/s, same frame)
 * 56 f32 reserved[2]
 */
object PoseLayout {
    const val MAGIC = 0x45534F50            // "POSE" in little-endian byte order
    const val VERSION = 1
    const val RECORD_OFFSET = 64
    const val RECORD_SIZE = 64
    const val REGION_SIZE = 4096

    const val H_MAGIC = 0
    const val H_VERSION = 4
    const val H_RECORD_SIZE = 8

    const val SEQ = 0
    const val FLAGS = 4
    const val SAMPLE_COUNTER = 8
    const val DISCONTINUITY = 12
    const val ARRIVAL_NANOS = 16
    const val SENSOR_NANOS = 24
    const val ROTVEC = 32
    const val ANGVEL = 44
    const val RESERVED = 56

    const val FLAG_TRACKING_ACTIVE = 1
    const val FLAG_STREAM_STALLED = 2
    /** angvel is the body-frame rate of rotvec (legacy / fallback head frame); clear = space-frame rate. */
    const val FLAG_BODY_FRAME = 4
}

/** Little-endian absolute-offset access to the region, plus a store-store fence (seqlock ordering). */
interface PoseMemory {
    fun putInt(offset: Int, value: Int)
    fun putLong(offset: Int, value: Long)
    fun putFloat(offset: Int, value: Float)
    fun getInt(offset: Int): Int
    fun fence()
}

class ByteBufferPoseMemory(buffer: ByteBuffer) : PoseMemory {
    private val buf: ByteBuffer = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    override fun putInt(offset: Int, value: Int) { buf.putInt(offset, value) }
    override fun putLong(offset: Int, value: Long) { buf.putLong(offset, value) }
    override fun putFloat(offset: Int, value: Float) { buf.putFloat(offset, value) }
    override fun getInt(offset: Int): Int = buf.getInt(offset)
    // Orders all earlier stores before all later ones (a dmb on arm64); readers in other processes rely on it.
    override fun fence() = VarHandle.releaseFence()
}

/**
 * Single-record seqlock writer for layout v1. Thread-safe (calls are serialized); one writer per region.
 * Each update: seq + 1 (odd), fence, fields, fence, seq + 1 (even).
 */
class PoseShareWriter(private val mem: PoseMemory) {
    private var seq = 0
    private var flags = 0
    private var counter = 0

    init {
        mem.putInt(PoseLayout.H_MAGIC, PoseLayout.MAGIC)
        mem.putInt(PoseLayout.H_VERSION, PoseLayout.VERSION)
        mem.putInt(PoseLayout.H_RECORD_SIZE, PoseLayout.RECORD_SIZE)
        seq = mem.getInt(PoseLayout.RECORD_OFFSET + PoseLayout.SEQ) and 1.inv()
        mem.fence()
    }

    /**
     * One pose: rotation vector [rx, ry, rz] (rad) and angular velocity [vx, vy, vz] (rad/s) in the Android head frame.
     * Sets the tracking-active flag, clears the stalled flag, sets the body-frame flag to [bodyFrame] (angvel is the
     * body-frame rate of rotvec instead of the space-frame rate) and increments sampleCounter.
     */
    @Synchronized
    fun writePose(
        arrivalNanos: Long, sensorNanos: Long,
        rx: Float, ry: Float, rz: Float, vx: Float, vy: Float, vz: Float,
        discontinuity: Int, bodyFrame: Boolean = false
    ) {
        flags = (flags or PoseLayout.FLAG_TRACKING_ACTIVE) and PoseLayout.FLAG_STREAM_STALLED.inv()
        flags = if (bodyFrame) flags or PoseLayout.FLAG_BODY_FRAME else flags and PoseLayout.FLAG_BODY_FRAME.inv()
        counter++
        val r = PoseLayout.RECORD_OFFSET
        begin()
        mem.putInt(r + PoseLayout.FLAGS, flags)
        mem.putInt(r + PoseLayout.SAMPLE_COUNTER, counter)
        mem.putInt(r + PoseLayout.DISCONTINUITY, discontinuity)
        mem.putLong(r + PoseLayout.ARRIVAL_NANOS, arrivalNanos)
        mem.putLong(r + PoseLayout.SENSOR_NANOS, sensorNanos)
        mem.putFloat(r + PoseLayout.ROTVEC, rx)
        mem.putFloat(r + PoseLayout.ROTVEC + 4, ry)
        mem.putFloat(r + PoseLayout.ROTVEC + 8, rz)
        mem.putFloat(r + PoseLayout.ANGVEL, vx)
        mem.putFloat(r + PoseLayout.ANGVEL + 4, vy)
        mem.putFloat(r + PoseLayout.ANGVEL + 8, vz)
        end()
    }

    /** Sets or clears flag bits; writes (one seqlock cycle) only when they change. */
    @Synchronized
    fun setFlag(bit: Int, on: Boolean) {
        val f = if (on) flags or bit else flags and bit.inv()
        if (f == flags) return
        flags = f
        begin()
        mem.putInt(PoseLayout.RECORD_OFFSET + PoseLayout.FLAGS, flags)
        end()
    }

    val sampleCounter: Int @Synchronized get() = counter

    private fun begin() {
        seq++
        mem.putInt(PoseLayout.RECORD_OFFSET + PoseLayout.SEQ, seq)
        mem.fence()
    }

    private fun end() {
        mem.fence()
        seq++
        mem.putInt(PoseLayout.RECORD_OFFSET + PoseLayout.SEQ, seq)
    }
}
