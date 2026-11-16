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
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Append-only head tracking event log, independent of any screen:
 * `Android/data/<pkg>/files/headtracking_logs/events.log`, rotated to `events.log.1` at ~1 MB.
 * Line: `<wall clock> <elapsedRealtime ms> <EVENT> <details>`. Writes happen on one background
 * thread; failures are logged once and otherwise ignored.
 */
class HeadTrackingEventLog(context: Context) {
    private val dir: File? = context.applicationContext.getExternalFilesDir(null)?.let { File(it, "headtracking_logs") }
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "headtracking-events").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var warned = false

    fun log(event: String, details: String = "") {
        val line = "${fmt.format(Date())} ${SystemClock.elapsedRealtime()} $event${if (details.isEmpty()) "" else " $details"}\n"
        Log.i(TAG, line.trimEnd())
        val d = dir ?: return
        executor.execute {
            try {
                d.mkdirs()
                val f = File(d, NAME)
                if (f.length() > MAX_BYTES) {
                    val old = File(d, "$NAME.1")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(line)
            } catch (t: Throwable) {
                if (!warned) { warned = true; Log.w(TAG, "event log write failed", t) }
            }
        }
    }

    companion object {
        private const val TAG = "HeadTrackingEvents"
        private const val NAME = "events.log"
        private const val MAX_BYTES = 1_000_000L
    }
}
