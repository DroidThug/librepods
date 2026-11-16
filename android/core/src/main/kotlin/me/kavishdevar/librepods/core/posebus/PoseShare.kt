// LibrePods - AirPods liberated from Apple's ecosystem
// Copyright (C) 2025 LibrePods contributors
// GPL-3.0-or-later

package me.kavishdevar.librepods.core.posebus

import android.os.SharedMemory
import android.system.OsConstants
import android.util.Log

/**
 * Process-wide pose bus: one shared-memory region (layout v1, [PoseLayout]) that other apps map read-only through
 * PoseShareService. The region is created on the first client request; until then the writes below are no-ops.
 */
object PoseShare {
    private const val TAG = "PoseShare"

    /** Packages allowed to bind the pose bus (PoseShareService checks the caller's uid against these). */
    val ALLOWED_PACKAGES: Set<String> = setOf(
        "dev.spatialrenderer.player",
        "com.apple.android.music",
        "com.apple.android.music.sr", // side-by-side patched clone used for renderer testing
    )

    @Volatile
    private var writer: PoseShareWriter? = null
    private var memory: SharedMemory? = null

    // Kept so a region created mid-session starts with the current state.
    @Volatile
    private var trackingActive = false

    @Volatile
    private var stalled = false

    /**
     * The region, created and mapped read/write here on first use, then protected to PROT_READ: the protection applies
     * to every later mapping of the region (also in other processes); our existing writable mapping keeps working.
     */
    @Synchronized
    fun memory(): SharedMemory {
        memory?.let { return it }
        val shm = SharedMemory.create("librepods-pose", PoseLayout.REGION_SIZE)
        val buf = shm.mapReadWrite()          // stays mapped for the life of the process
        val w = PoseShareWriter(ByteBufferPoseMemory(buf))
        w.setFlag(PoseLayout.FLAG_TRACKING_ACTIVE, trackingActive)
        w.setFlag(PoseLayout.FLAG_STREAM_STALLED, stalled)
        if (!shm.setProtect(OsConstants.PROT_READ)) Log.w(TAG, "setProtect(PROT_READ) failed; region stays writable for clients")
        memory = shm
        writer = w
        Log.i(TAG, "pose region created (${PoseLayout.REGION_SIZE} bytes)")
        return shm
    }

    /**
     * Called for every head pose produced (AACPManager), with the float values given to the HID before quantisation.
     * [bodyFrame]: the pose is in the legacy / fallback sensor-fixed frame, whose angvel is the body-frame rate
     * (flags bit2); false for the default world-frame head basis (space-frame rate).
     */
    fun writePose(
        arrivalNanos: Long, rx: Float, ry: Float, rz: Float, vx: Float, vy: Float, vz: Float, discontinuity: Int,
        bodyFrame: Boolean
    ) {
        stalled = false
        trackingActive = true
        writer?.writePose(arrivalNanos, 0L, rx, ry, rz, vx, vy, vz, discontinuity, bodyFrame)
    }

    /** Bit 0: head tracking runs (set on bridge start, cleared on stop; a pose also sets it). Clearing also clears bit 1. */
    fun setTrackingActive(on: Boolean) {
        trackingActive = on
        if (!on) stalled = false
        writer?.let {
            it.setFlag(PoseLayout.FLAG_TRACKING_ACTIVE, on)
            if (!on) it.setFlag(PoseLayout.FLAG_STREAM_STALLED, false)
        }
    }

    /** Bit 1: the stream watchdog saw a stall (set on STALL, cleared on RESUME or the next pose). */
    fun setStalled(on: Boolean) {
        stalled = on
        writer?.setFlag(PoseLayout.FLAG_STREAM_STALLED, on)
    }
}
