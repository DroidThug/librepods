/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/
package me.kavishdevar.librepods.services

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.core.posebus.PoseShare
import me.kavishdevar.librepods.core.privileged.HeadFrame
import me.kavishdevar.librepods.core.privileged.ShizukuHeadTracker
import me.kavishdevar.librepods.core.privileged.StreamWatchdog
import rikka.shizuku.Shizuku
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the experimental Shizuku head tracker bridge running while the
 * user's toggle is on and an AirPods device is connected, and keeps the
 * AirPods sensor stream alive (except during calls, which stop it).
 * Bridge restarts and stream restarts back off (5 s doubling to 60 s) and
 * give up after [MAX_ATTEMPTS] consecutive failures until the next
 * reconnect, toggle change or (for the stream) call end.
 */
class SpatialHeadTrackingController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val devices: StateFlow<Map<MacAddress, Device<*, *, *>>>
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val toggle = MutableStateFlow(prefs.getBoolean(PREF_KEY, false))
    private val shizukuReady = MutableStateFlow(false)
    private val inCall = MutableStateFlow(false)

    private val lock = Any()
    private var active: AppleDevice? = null
    private var job: Job? = null

    @Volatile
    private var stopped = false

    private val events = HeadTrackingEventLog(context)
    private val watchdog = StreamWatchdog()
    private val wakeLock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "librepods:headtracking")
            .apply { setReferenceCounted(false) }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == PREF_KEY) toggle.value = p.getBoolean(PREF_KEY, false)
        if (key == APPLE_RECENTER_PREF_KEY) ShizukuHeadTracker.appleRecenter = p.getBoolean(APPLE_RECENTER_PREF_KEY, false)
        if (key == MIRROR_EAR_LEFT_PREF_KEY) ShizukuHeadTracker.mirrorEarLeft = p.getBoolean(MIRROR_EAR_LEFT_PREF_KEY, HeadFrame.MIRROR_EAR_DEFAULT)
        if (key == MIRROR_EAR_RIGHT_PREF_KEY) ShizukuHeadTracker.mirrorEarRight = p.getBoolean(MIRROR_EAR_RIGHT_PREF_KEY, HeadFrame.MIRROR_EAR_DEFAULT)
        if (key == LEGACY_FRAME_PREF_KEY) ShizukuHeadTracker.legacyFrame = p.getBoolean(LEGACY_FRAME_PREF_KEY, HeadFrame.LEGACY_FRAME_DEFAULT)
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { shizukuReady.value = true }
    private val binderDead = Shizuku.OnBinderDeadListener { shizukuReady.value = false }

    fun setInCall(value: Boolean) {
        inCall.value = value
    }

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        ShizukuHeadTracker.appleRecenter = prefs.getBoolean(APPLE_RECENTER_PREF_KEY, false)
        ShizukuHeadTracker.legacyFrame = prefs.getBoolean(LEGACY_FRAME_PREF_KEY, HeadFrame.LEGACY_FRAME_DEFAULT)
        ShizukuHeadTracker.mirrorEarLeft = prefs.getBoolean(MIRROR_EAR_LEFT_PREF_KEY, HeadFrame.MIRROR_EAR_DEFAULT)
        ShizukuHeadTracker.mirrorEarRight = prefs.getBoolean(MIRROR_EAR_RIGHT_PREF_KEY, HeadFrame.MIRROR_EAR_DEFAULT)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)

        job = scope.launch {
            combine(toggle, shizukuReady, connectedAppleDevice()) { on, ready, device ->
                device.takeIf { on && ready }
            }
                .distinctUntilChanged()
                .collectLatest { device ->
                    withContext(Dispatchers.IO) { synchronized(lock) { stopBridgeLocked() } }
                    if (device != null) runBridge(device)
                }
        }
        scope.launch {
            var last: String? = null
            connectedAppleDevice().map { it?.macAddress?.toRedactedString() }.distinctUntilChanged().collect { mac ->
                if (toggle.value) {
                    if (mac != null) events.log("AACP_CONNECTED", mac)
                    else if (last != null) events.log("AACP_DISCONNECTED", last!!)
                }
                last = mac
            }
        }
    }

    fun stop() {
        stopped = true
        job?.cancel()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        // startBridge() may be mid-bind (blocking up to 5 s); don't stall the caller.
        Thread({ synchronized(lock) { stopBridgeLocked() } }, "headtracking-stop").start()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun connectedAppleDevice(): Flow<AppleDevice?> = devices.flatMapLatest { map ->
        val apple = map.values.filterIsInstance<AppleDevice>()
        if (apple.isEmpty()) {
            flowOf(null)
        } else {
            combine(apple.map { d ->
                d.connectionState.map { if (it == ConnectionState.CONNECTED) d else null }
            }) { connected -> connected.firstOrNull { it != null } }
        }
    }

    private suspend fun runBridge(device: AppleDevice) {
        var failures = 0
        while (failures < MAX_ATTEMPTS) {
            if (failures > 0) delay(backoff(failures - 1))
            if (!withContext(Dispatchers.IO) { startBridge(device) }) {
                failures++
                continue
            }
            val upSince = SystemClock.elapsedRealtime()
            coroutineScope {
                val streaming = launch { keepStreaming(device) }
                val watching = launch { watchStream(device) }
                val primary = launch {
                    device.state.map { it.primaryComponent }.distinctUntilChanged().drop(1).collect { c ->
                        events.log("PRIMARY_CHANGED", c.name)
                        recenter(device, "primary changed")
                    }
                }
                ShizukuHeadTracker.connected.first { !it }
                streaming.cancel()
                watching.cancel()
                primary.cancel()
            }
            val upFor = SystemClock.elapsedRealtime() - upSince
            failures = if (upFor >= MAX_BACKOFF.inWholeMilliseconds) 1 else failures + 1
            Log.w(TAG, "bridge died after $upFor ms (failure $failures)")
            events.log("HELPER_RESTART", "up ${upFor} ms, failure $failures")
        }
        Log.w(TAG, "bridge failed $MAX_ATTEMPTS times, giving up until the next state change")
        withContext(Dispatchers.IO) { synchronized(lock) { stopBridgeLocked() } }
    }

    private fun startBridge(device: AppleDevice): Boolean = synchronized(lock) {
        if (stopped) return false
        recenter(device, "bridge start")
        val ok = ShizukuHeadTracker.start(context.applicationContext, device.macAddress.value)
        ShizukuHeadTracker.enabled = ok
        Log.i(TAG, "bridge start for ${device.macAddress.toRedactedString()} -> $ok")
        if (ok) {
            active = device
            PoseShare.setTrackingActive(true)
            events.log("TRACKING_START", device.macAddress.toRedactedString())
            logBatteryState()
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            ShizukuHeadTracker.onSensorPacket = { now ->
                val ev = watchdog.onPacket(now)
                if (ev is StreamWatchdog.Event.Resume) {
                    PoseShare.setStalled(false)
                    events.log("RESUME", "gap ${ev.gapMs} ms")
                    recenter(device, "resume after ${ev.gapMs} ms")
                }
            }
            if (!ShizukuHeadTracker.startKeepAlive(intArrayOf(Process.myUid()))) {
                events.log("KEEPALIVE_FAIL", "helper does not support keep-alive (restart it after a reinstall)")
            }
            events.log("DEVICEIDLE_WHITELIST", ShizukuHeadTracker.whitelistPackage(context.packageName).replace('\n', ' '))
        }
        ok
    }

    private fun stopBridgeLocked() {
        ShizukuHeadTracker.enabled = false
        ShizukuHeadTracker.onSensorPacket = null
        PoseShare.setTrackingActive(false)
        ShizukuHeadTracker.stopKeepAlive()
        ShizukuHeadTracker.shutdown(context.applicationContext)
        if (wakeLock.isHeld) wakeLock.release()
        val prev = active ?: return
        active = null
        events.log("TRACKING_STOP", prev.macAddress.toRedactedString())
        if (prev.connectionState.value == ConnectionState.CONNECTED) prev.stopHeadTracking()
        Log.i(TAG, "bridge stopped for ${prev.macAddress.toRedactedString()}")
    }

    // The head gestures screen and call handling stop the sensor stream on
    // their own; restart it whenever it drops while no call is in progress,
    // and retry while the AirPods have not started reporting yet (in case,
    // out of ear, unsupported firmware: hence the give-up limit).
    private suspend fun keepStreaming(device: AppleDevice) {
        var attempts = 0
        combine(
            device.state.map { it.headTrackingState == BuddyState.ACTIVE }.distinctUntilChanged(),
            inCall
        ) { streaming, call -> streaming to call }
            .collectLatest { (streaming, call) ->
                if (streaming || call) {
                    attempts = 0
                    return@collectLatest
                }
                // Someone else just asked for the stream; give it time to arrive first.
                if (device.state.value.headTrackingState == BuddyState.WAITING) delay(BASE_BACKOFF)
                while (attempts < MAX_ATTEMPTS) {
                    device.startHeadTracking()
                    delay(backoff(attempts++))
                }
                Log.w(TAG, "no head tracking data after $MAX_ATTEMPTS requests, giving up until the next state change")
            }
    }

    private fun recenter(device: AppleDevice, reason: String) {
        device.aacp.recenterPose()
        events.log("RECENTER", reason)
    }

    private fun logBatteryState() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val bucket = try {
            (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket.toString()
        } catch (t: Throwable) {
            "unknown"
        }
        events.log("BATTERY_STATE", "ignoringOptimizations=${pm.isIgnoringBatteryOptimizations(context.packageName)} standbyBucket=$bucket")
    }

    // Stall watchdog: every 500 ms while the bridge runs. No packet for > 1.5 s re-sends the stream
    // start (backing off 3 s to 15 s); also refreshes the wake lock and reports keep-alive failures.
    private suspend fun watchStream(device: AppleDevice) {
        var lastWakeRefresh = SystemClock.elapsedRealtime()
        watchdog.check(lastWakeRefresh, false) // re-arm for this bridge run
        while (currentCoroutineContext().isActive) {
            val now = SystemClock.elapsedRealtime()
            val on = device.connectionState.value == ConnectionState.CONNECTED && !inCall.value
            for (ev in watchdog.check(now, on)) when (ev) {
                is StreamWatchdog.Event.Stall -> {
                    PoseShare.setStalled(true)
                    events.log("STALL", "gap ${ev.gapMs} ms")
                }
                StreamWatchdog.Event.Request -> {
                    events.log("STREAM_REQUESTED")
                    device.startHeadTracking()
                }
                is StreamWatchdog.Event.Resume -> Unit
            }
            if (now - lastWakeRefresh > WAKE_LOCK_REFRESH_MS) {
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                lastWakeRefresh = now
            }
            withContext(Dispatchers.IO) { ShizukuHeadTracker.takeKeepAliveFailure() }?.let { events.log("KEEPALIVE_FAIL", it) }
            delay(WATCHDOG_PERIOD_MS)
        }
    }

    companion object {
        private const val TAG = "SpatialHeadTracking"
        private const val WATCHDOG_PERIOD_MS = 500L
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_REFRESH_MS = 60 * 1000L
        const val PREF_KEY = "spatial_head_tracking_enabled"
        /** Optional Apple-style bleed-to-zero recentering of the pose; default off. */
        const val APPLE_RECENTER_PREF_KEY = "spatial_head_tracking_apple_recenter"
        /** A/B switch: true = old sensor-frame head basis, false (default) = gravity-aligned HeadFrame. */
        const val LEGACY_FRAME_PREF_KEY = "spatial_head_tracking_legacy_frame"
        /** Per-bud ear-axis mirror (flips pitch/roll), applied for whichever bud is primary. */
        const val MIRROR_EAR_LEFT_PREF_KEY = "spatial_head_tracking_mirror_ear_left"
        const val MIRROR_EAR_RIGHT_PREF_KEY = "spatial_head_tracking_mirror_ear_right"
        private const val MAX_ATTEMPTS = 10
        private val BASE_BACKOFF = 5.seconds
        private val MAX_BACKOFF = 60.seconds

        private fun backoff(attempt: Int): Duration =
            (BASE_BACKOFF * (1 shl attempt.coerceAtMost(4))).coerceAtMost(MAX_BACKOFF)

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(PREF_KEY, false)
    }
}
