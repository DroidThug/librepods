/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/
package me.kavishdevar.librepods.core.privileged

import android.util.Log

/**
 * Shizuku UserService implementation: Shizuku launches this class in its
 * own process with shell's identity/privilege (the same way it launches
 * its own server), using the app's own installed APK - no separate
 * privileged APK needed for this feature. The app binds this via
 * `Shizuku.bindUserService()` and talks to it over Binder from then on,
 * which is what actually works: Binder crosses the untrusted_app<->shell
 * SELinux boundary (confirmed - Shizuku's own plumbing depends on it);
 * raw Unix domain sockets between those two domains do not (confirmed via
 * SELinux audit log this session in both directions - see
 * HEADTRACKING_FORK_NOTES.md). This replaces an earlier design that tried
 * a separate process + LocalSocket, which hit that wall.
 *
 * Shizuku's UserService convention expects a no-argument constructor.
 */
class HeadTrackerUserService : IHeadTrackerService.Stub() {
    private val tracker = UhidHeadTracker()
    private var eventReaderThread: Thread? = null

    // Shizuku does not reliably reap an earlier instance of this process after the app is reinstalled
    // or killed; a stale one keeps its own uhid sensor registered next to ours.
    private fun killStaleSiblings() {
        try {
            val self = android.os.Process.myPid()
            val name = java.io.File("/proc/self/cmdline").readText()
            java.io.File("/proc").listFiles()?.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                if (pid == self) return@forEach
                val other = try { java.io.File(dir, "cmdline").readText() } catch (_: Throwable) { return@forEach }
                if (other == name) {
                    Log.i(TAG, "killing stale sibling pid=$pid")
                    android.os.Process.killProcess(pid)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "killStaleSiblings failed", t)
        }
    }

    override fun start(mac: String): Boolean {
        Log.i(TAG, "start(mac=$mac)")
        killStaleSiblings()
        val ok = tracker.create(macTag = mac.replace(":", "").uppercase())
        if (ok) {
            eventReaderThread = Thread({
                while (true) {
                    val type = tracker.readEventBlocking()
                    if (type == -1) break
                }
            }, "uhid-event-reader").apply { isDaemon = true; start() }
        }
        return ok
    }

    override fun sendPose(rx: Int, ry: Int, rz: Int, vx: Int, vy: Int, vz: Int) {
        tracker.sendPose(rx, ry, rz, vx, vy, vz)
    }

    override fun stop() {
        Log.i(TAG, "stop()")
        tracker.destroy()
        eventReaderThread?.interrupt()
        eventReaderThread = null
    }

    override fun destroy() {
        Log.i(TAG, "destroy(), exiting process")
        stop()
        // Shizuku UserService processes are meant to be killed by Shizuku
        // itself when unbound, but exit explicitly too for a clean stop
        // when the app asks for it directly.
        kotlin.system.exitProcess(0)
    }

    companion object {
        private const val TAG = "HeadTrackerUserService"
    }
}
