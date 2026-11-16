// LibrePods - AirPods liberated from Apple's ecosystem
// Copyright (C) 2025 LibrePods contributors
// GPL-3.0-or-later

package me.kavishdevar.librepods.core.privileged

/**
 * Detects a stalled AirPods sensor stream. Pure logic: the caller passes monotonic times in ms.
 *
 * - [onPacket] for each sensor packet (any thread). Returns a [Event.Resume] when the gap since
 *   the previous packet exceeds [resumeGapMs] (the neutral pose may be stale: recenter).
 * - [check] periodically (e.g. every 500 ms) with whether tracking is active (enabled, AirPods
 *   connected, no call). Once no packet has arrived for more than [stallMs] it returns
 *   [Event.Stall] plus [Event.Request] (re-send the stream start), then further [Event.Request]s
 *   with a delay doubling from [minRetryMs] to [maxRetryMs]. While inactive it returns nothing and
 *   re-arms, so the stall timer starts again from the next active check.
 */
class StreamWatchdog(
    private val stallMs: Long = 1_500,
    private val resumeGapMs: Long = 2_000,
    private val minRetryMs: Long = 3_000,
    private val maxRetryMs: Long = 15_000,
) {
    sealed class Event {
        data class Stall(val gapMs: Long) : Event()
        data class Resume(val gapMs: Long) : Event()
        object Request : Event()
    }

    private var active = false
    private var armedAt = 0L
    private var lastPacket = NONE
    private var stalled = false
    private var retryDelay = minRetryMs
    private var nextRetry = 0L

    @Synchronized
    fun onPacket(nowMs: Long): Event? {
        val prev = lastPacket
        lastPacket = nowMs
        val wasStalled = stalled
        stalled = false
        retryDelay = minRetryMs
        if (prev == NONE) return null
        val gap = nowMs - prev
        return if (gap > resumeGapMs || (wasStalled && gap > stallMs)) Event.Resume(gap) else null
    }

    @Synchronized
    fun check(nowMs: Long, isActive: Boolean): List<Event> {
        if (!isActive) {
            active = false
            stalled = false
            return emptyList()
        }
        if (!active) {
            active = true
            armedAt = nowMs
            stalled = false
            retryDelay = minRetryMs
        }
        val since = if (lastPacket != NONE && lastPacket > armedAt) lastPacket else armedAt
        val gap = nowMs - since
        if (gap <= stallMs) return emptyList()
        if (!stalled) {
            stalled = true
            nextRetry = nowMs + retryDelay
            return listOf(Event.Stall(gap), Event.Request)
        }
        if (nowMs >= nextRetry) {
            retryDelay = (retryDelay * 2).coerceAtMost(maxRetryMs)
            nextRetry = nowMs + retryDelay
            return listOf(Event.Request)
        }
        return emptyList()
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
