// LibrePods - AirPods liberated from Apple's ecosystem
// Copyright (C) 2025 LibrePods contributors
// GPL-3.0-or-later

package me.kavishdevar.librepods.core.privileged

import android.util.Log
import java.io.File

/**
 * Runs in the Shizuku (shell) process. While head tracking is on, writes "0" to the cgroup v2
 * freeze files of the given uids every [PERIOD_MS], so an OEM freezer (ColorOS freezes background
 * apps with the screen off) cannot leave the app frozen for long. Tries
 * /sys/fs/cgroup/apps/uid_<uid>/ first, then /sys/fs/cgroup/uid_<uid>/, and also the pid_* child
 * cgroups below the uid directory (a frozen child stays frozen under a thawed parent).
 *
 * Failures are ignored; the first failure per uid is kept for the app to log
 * ([takeFirstFailure]). Assumption: shell may write these files on the target ROM (the O-Pose app
 * does the same through Shizuku); not verified here.
 */
class CgroupKeepAlive {
    private var thread: Thread? = null
    private val failedUids = HashSet<Int>()
    private var pendingFailure: String? = null

    @Synchronized
    fun start(uids: IntArray) {
        stop()
        Log.i(TAG, "keep-alive start for uids ${uids.joinToString()}")
        thread = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    for (uid in uids) thaw(uid)
                    Thread.sleep(PERIOD_MS)
                }
            } catch (_: InterruptedException) {
            }
        }, "cgroup-keepalive").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    /** First failure (per uid) not yet reported, or null. */
    @Synchronized
    fun takeFirstFailure(): String? = pendingFailure.also { pendingFailure = null }

    private fun thaw(uid: Int) {
        val dir = ROOTS.map { File(it, "uid_$uid") }.firstOrNull { it.isDirectory }
        if (dir == null) {
            fail(uid, "no cgroup dir for uid $uid under ${ROOTS.joinToString()}")
            return
        }
        try {
            File(dir, FREEZE).writeText("0")
        } catch (t: Throwable) {
            fail(uid, "write ${dir.path}/$FREEZE: $t")
        }
        dir.listFiles()?.forEach { child ->
            if (child.name.startsWith("pid_")) {
                try { File(child, FREEZE).takeIf { it.exists() }?.writeText("0") } catch (_: Throwable) { }
            }
        }
    }

    @Synchronized
    private fun fail(uid: Int, msg: String) {
        if (failedUids.add(uid)) {
            Log.w(TAG, "keep-alive: $msg")
            pendingFailure = msg
        }
    }

    companion object {
        private const val TAG = "CgroupKeepAlive"
        const val PERIOD_MS = 300L
        private const val FREEZE = "cgroup.freeze"
        private val ROOTS = listOf("/sys/fs/cgroup/apps", "/sys/fs/cgroup")
    }
}
