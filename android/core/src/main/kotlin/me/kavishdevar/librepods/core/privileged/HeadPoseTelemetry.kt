/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/
package me.kavishdevar.librepods.core.privileged

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One decoded AirPods head pose frame, for diagnostics and calibration logging. */
data class HeadPoseSample(
    val seq: Long,
    val elapsedNanos: Long,
    val qw: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val ax: Int,
    val ay: Int,
    val az: Int,
    /** Head-frame rotation vector in radians: x = right (pitch), y = forward (roll), z = up (yaw). */
    val headX: Float,
    val headY: Float,
    val headZ: Float,
    val sentRx: Int,
    val sentRy: Int,
    val sentRz: Int,
)

/** Published by AACPManager only while someone observes [latest] or a [sink] is set. */
object HeadPoseTelemetry {
    private val _latest = MutableStateFlow<HeadPoseSample?>(null)
    val latest: StateFlow<HeadPoseSample?> = _latest.asStateFlow()

    /** Receives every sample plus the raw 58-byte AACP payload, on the AACP reader thread. */
    @Volatile
    var sink: ((HeadPoseSample, ByteArray) -> Unit)? = null

    val active: Boolean
        get() = sink != null || _latest.subscriptionCount.value > 0

    private var seq = 0L

    fun nextSeq(): Long = ++seq

    fun publish(sample: HeadPoseSample, payload: ByteArray) {
        _latest.value = sample
        sink?.invoke(sample, payload)
    }
}
