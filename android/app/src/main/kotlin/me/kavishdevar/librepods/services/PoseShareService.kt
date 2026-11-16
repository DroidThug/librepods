/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/
package me.kavishdevar.librepods.services

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SharedMemory
import android.util.Log
import me.kavishdevar.librepods.core.posebus.IPoseShare
import me.kavishdevar.librepods.core.posebus.PoseLayout
import me.kavishdevar.librepods.core.posebus.PoseShare

/**
 * Exported bound service (action [ACTION]) handing the read-only pose bus region to allowlisted packages
 * ([PoseShare.ALLOWED_PACKAGES]). The region is written by AACPManager while head tracking runs; see
 * HEADTRACKING_FORK_NOTES.md, "Pose bus".
 */
class PoseShareService : Service() {
    private val binder = object : IPoseShare.Stub() {
        override fun getPoseMemory(): SharedMemory {
            checkCaller()
            return PoseShare.memory()
        }

        override fun getLayoutVersion(): Int {
            checkCaller()
            return PoseLayout.VERSION
        }
    }

    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        val pkgs = packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        if (pkgs.none { it in PoseShare.ALLOWED_PACKAGES }) {
            Log.w(TAG, "rejected uid $uid ($pkgs)")
            throw SecurityException("caller not allowed to read the pose bus")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "PoseShareService"
        const val ACTION = "me.kavishdevar.librepods.POSE_SHARE"
    }
}
