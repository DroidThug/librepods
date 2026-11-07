/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/
package me.kavishdevar.librepods.services

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.core.privileged.HeadFrame
import me.kavishdevar.librepods.core.privileged.ShizukuHeadTracker
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

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == PREF_KEY) toggle.value = p.getBoolean(PREF_KEY, false)
        if (key == APPLE_RECENTER_PREF_KEY) ShizukuHeadTracker.appleRecenter = p.getBoolean(APPLE_RECENTER_PREF_KEY, false)
        if (key == MIRROR_EAR_LEFT_PREF_KEY) ShizukuHeadTracker.mirrorEarLeft = p.getBoolean(MIRROR_EAR_LEFT_PREF_KEY, false)
        if (key == MIRROR_EAR_RIGHT_PREF_KEY) ShizukuHeadTracker.mirrorEarRight = p.getBoolean(MIRROR_EAR_RIGHT_PREF_KEY, false)
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
        ShizukuHeadTracker.mirrorEarLeft = prefs.getBoolean(MIRROR_EAR_LEFT_PREF_KEY, false)
        ShizukuHeadTracker.mirrorEarRight = prefs.getBoolean(MIRROR_EAR_RIGHT_PREF_KEY, false)
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
                ShizukuHeadTracker.connected.first { !it }
                streaming.cancel()
            }
            val upFor = SystemClock.elapsedRealtime() - upSince
            failures = if (upFor >= MAX_BACKOFF.inWholeMilliseconds) 1 else failures + 1
            Log.w(TAG, "bridge died after $upFor ms (failure $failures)")
        }
        Log.w(TAG, "bridge failed $MAX_ATTEMPTS times, giving up until the next state change")
        withContext(Dispatchers.IO) { synchronized(lock) { stopBridgeLocked() } }
    }

    private fun startBridge(device: AppleDevice): Boolean = synchronized(lock) {
        if (stopped) return false
        device.aacp.recenterPose()
        val ok = ShizukuHeadTracker.start(context.applicationContext, device.macAddress.value)
        ShizukuHeadTracker.enabled = ok
        Log.i(TAG, "bridge start for ${device.macAddress.toRedactedString()} -> $ok")
        if (ok) active = device
        ok
    }

    private fun stopBridgeLocked() {
        ShizukuHeadTracker.enabled = false
        ShizukuHeadTracker.shutdown(context.applicationContext)
        val prev = active ?: return
        active = null
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

    companion object {
        private const val TAG = "SpatialHeadTracking"
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
