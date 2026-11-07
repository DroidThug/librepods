/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/
package me.kavishdevar.librepods.core.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bridges LibrePods' already-decoded AirPods head-tracking data into a
 * real, OS-native head-tracked spatial-audio pipeline on Oplus/ColorOS
 * devices, via a virtual uhid device created by [HeadTrackerUserService],
 * which Shizuku launches with shell's identity/privilege.
 *
 * EXPERIMENTAL. Requires Shizuku (installed, running, authorized for this
 * app) - no root. See HEADTRACKING_FORK_NOTES.md for background, how the
 * technique works, and known uncertainties.
 *
 * Communicates over Binder via Shizuku's UserService mechanism - NOT a raw
 * Unix domain socket. That distinction matters: this device's SELinux
 * policy denies `unix_stream_socket connectto` between the `untrusted_app`
 * and `shell` domains in both directions (confirmed via audit log this
 * session), which is why an earlier exec()+LocalSocket design didn't
 * work. Binder IPC crosses that same boundary fine - it's how Shizuku's
 * own app<->shell communication already works.
 */
object ShizukuHeadTracker {
    private const val TAG = "ShizukuHeadTracker"

    /** Set by the app's head tracking controller; gates [sendPose]. */
    @Volatile
    var enabled: Boolean = false

    /**
     * Optional Apple-style bleed-to-zero recentering of the pose (see TempestRecenter). Set by the
     * app's head tracking controller from the `spatial_head_tracking_apple_recenter` preference.
     */
    @Volatile
    var appleRecenter: Boolean = false

    /**
     * Use the old sensor-frame head basis instead of the gravity-aligned HeadFrame (A/B switch).
     * Set by the app's head tracking controller from `spatial_head_tracking_legacy_frame`.
     */
    @Volatile
    var legacyFrame: Boolean = HeadFrame.LEGACY_FRAME_DEFAULT

    /**
     * Mirror the fixed ear axis (flips pitch and roll) when the left / right bud is primary. Set by
     * the app's head tracking controller from `spatial_head_tracking_mirror_ear_left` / `_right`.
     */
    @Volatile
    var mirrorEarLeft: Boolean = false

    @Volatile
    var mirrorEarRight: Boolean = false

    @Volatile
    private var service: IHeadTrackerService? = null

    @Volatile
    private var connection: ServiceConnection? = null

    private val _connected = MutableStateFlow(false)

    /** True between a successful [start] and [shutdown] or the privileged process dying. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var lastSendFailureLog = 0L

    fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (t: Throwable) {
            Log.e(TAG, "requestPermission failed", t)
        }
    }

    /**
     * Binds the Shizuku UserService (spawning it with shell's identity if
     * not already running) and starts the uhid device for `mac`. Blocks
     * (with a timeout) until the service is bound, since binding is
     * normally asynchronous - callers should invoke this off the main
     * thread. Always starts from a fresh privileged process.
     */
    @Synchronized
    fun start(context: Context, mac: String): Boolean {
        if (!hasPermission()) {
            Log.w(TAG, "start() called without Shizuku permission")
            return false
        }

        // Shizuku keeps UserService processes alive across app restarts and
        // reinstalls, so a process we never bound in this app process may
        // still be holding an old uhid device (or running old code).
        if (connection != null) shutdown(context) else removeStale(context)

        if (!bindBlocking(context)) {
            Log.e(TAG, "failed to bind HeadTrackerUserService")
            return false
        }

        val ok = try {
            service?.start(mac) ?: false
        } catch (t: Throwable) {
            Log.e(TAG, "service.start() threw", t)
            false
        }
        Log.i(TAG, "service.start($mac) -> $ok")
        _connected.value = ok && service != null
        return _connected.value
    }

    /**
     * Feeds one orientation sample. rx/ry/rz are signed int16-range values; [discontinuity] is the
     * HID discontinuity counter (uint8), changed whenever the pose jumps (recenter).
     */
    fun sendPose(rx: Int, ry: Int, rz: Int, vx: Int = 0, vy: Int = 0, vz: Int = 0, discontinuity: Int = 1) {
        if (!enabled) return
        try {
            service?.sendPose(rx, ry, rz, vx, vy, vz, discontinuity)
        } catch (t: Throwable) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSendFailureLog >= 1000) {
                lastSendFailureLog = now
                Log.w(TAG, "sendPose failed: $t")
            }
        }
    }

    fun stop() {
        try {
            service?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stop() threw", t)
        }
    }

    /** Fully tears down the uhid device and unbinds/kills the privileged process. */
    @Synchronized
    fun shutdown(context: Context) {
        try {
            service?.destroy()
        } catch (_: Throwable) {
            // expected: destroy() calls exitProcess() on the other side,
            // which usually tears down the Binder connection before a
            // reply can be sent.
        }
        unbind(context)
    }

    private fun bindBlocking(context: Context, timeoutMs: Long = 5000): Boolean {
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IHeadTrackerService.Stub.asInterface(binder)
                Log.i(TAG, "UserService connected")
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "UserService disconnected")
                // A late callback for a connection we already tore down must
                // not clobber a newer one.
                if (connection !== this) return
                enabled = false
                service = null
                _connected.value = false
            }
        }
        connection = conn

        return try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, HeadTrackerUserService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("headtracker")
                .version(1)

            Shizuku.bindUserService(args, conn)
            val connected = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!connected) Log.e(TAG, "bindUserService timed out after ${timeoutMs}ms")
            connected
        } catch (t: Throwable) {
            Log.e(TAG, "bindUserService failed", t)
            false
        }
    }

    private fun unbind(context: Context) {
        val conn = connection ?: return
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, HeadTrackerUserService::class.java.name)
            )
            Shizuku.unbindUserService(args, conn, true)
        } catch (t: Throwable) {
            Log.w(TAG, "unbindUserService failed", t)
        }
        connection = null
        service = null
        _connected.value = false
    }

    /** Asks Shizuku to kill any HeadTrackerUserService process, bound or not. */
    private fun removeStale(context: Context) {
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, HeadTrackerUserService::class.java.name)
            )
            Shizuku.unbindUserService(args, null, true)
        } catch (t: Throwable) {
            Log.w(TAG, "removing stale UserService failed", t)
        }
    }
}
