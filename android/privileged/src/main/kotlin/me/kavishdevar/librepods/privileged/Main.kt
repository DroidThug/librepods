package me.kavishdevar.librepods.privileged

import android.annotation.SuppressLint
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Looper
import android.util.Log
import kotlin.system.exitProcess

/**
 * `android.app.ActivityThread.initializeMainlineModules()` /
 * `.systemMain()`, and `BluetoothHeadset.setConnectionPolicy()` /
 * `BluetoothProfile.CONNECTION_POLICY_FORBIDDEN` are hidden (non-SDK) APIs:
 * real, present and callable at runtime on-device (this process runs with
 * shell's hidden-API exemption via Shizuku), but absent from the public
 * compileSdk stub used to build against, so direct syntax won't compile.
 * Called via reflection instead - verified working pattern this session.
 */
private object Hidden {
    private val activityThreadClass = Class.forName("android.app.ActivityThread")

    fun initializeMainlineModules() {
        activityThreadClass.getMethod("initializeMainlineModules").invoke(null)
    }

    fun systemMainContext(): Context {
        val activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
        val field = activityThreadClass.getMethod("getSystemContext")
        return field.invoke(activityThread) as Context
    }

    fun setHeadsetConnectionPolicyForbidden(headset: BluetoothHeadset, device: android.bluetooth.BluetoothDevice): Boolean {
        val forbidden = BluetoothProfile::class.java.getField("CONNECTION_POLICY_FORBIDDEN").getInt(null)
        val method = BluetoothHeadset::class.java.getMethod(
            "setConnectionPolicy", android.bluetooth.BluetoothDevice::class.java, Int::class.javaPrimitiveType
        )
        return method.invoke(headset, device, forbidden) as Boolean
    }
}

/**
 * Entry point for the `:privileged` helper, meant to be launched via
 * Shizuku (CLASSPATH=<installed privileged.apk path> app_process ...), so
 * it runs with shell's uid/gid.
 *
 * NOTE: head tracking used to be dispatched from here (a separate process
 * + LocalSocket to the app), but that hit a hard SELinux wall - raw Unix
 * domain sockets are denied between the `untrusted_app` and `shell`
 * domains in BOTH directions on this device (confirmed via audit log).
 * It has been replaced with a Shizuku UserService
 * (`core/privileged/HeadTrackerUserService.kt`), which communicates over
 * Binder instead and doesn't need this standalone process/APK at all for
 * that feature. See HEADTRACKING_FORK_NOTES.md.
 *
 * This module/entry point is kept for the unrelated `disable-hfp`
 * experiment, which still uses the plain-exec model (a one-shot call,
 * not an ongoing data channel, so it was never affected by the socket
 * issue above).
 *
 * Dispatches on args[0]:
 *   "disable-hfp" <mac>        - existing experimental HFP-disable path
 */
object Main {
    private const val TAG = "LibrePodsPrivileged"

    @JvmStatic
    fun main(args: Array<String>) {
        Log.i(TAG, "Starting, args=${args.joinToString(" ")}")

        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()

        Hidden.initializeMainlineModules()

        val context = Hidden.systemMainContext()

        when (args.getOrNull(0)) {
            "disable-hfp" -> runDisableHfp(context, args.getOrNull(1))
            else -> {
                Log.e(TAG, "usage: Main <disable-hfp> <mac>")
                exitProcess(1)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runDisableHfp(context: Context, mac: String?) {
        check(!mac.isNullOrBlank()) { "disable-hfp requires a MAC address argument" }

        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        check(adapter != null) { "Bluetooth adapter unavailable" }
        check(adapter.isEnabled) { "Bluetooth adapter not enabled" }

        val device = adapter.getRemoteDevice(mac)
        Log.i(TAG, "Got remote device: ${device.name} (${device.address})")

        val started = adapter.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.HEADSET) return
                    val headset = proxy as BluetoothHeadset
                    try {
                        val success = Hidden.setHeadsetConnectionPolicyForbidden(headset, device)
                        Log.i(TAG, "Set HFP forbidden: success=$success")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to disable HFP", e)
                    } finally {
                        adapter.closeProfileProxy(profile, proxy)
                        exitProcess(0)
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    Log.w(TAG, "Profile disconnected: $profile")
                }
            },
            BluetoothProfile.HEADSET
        )

        check(started) { "getProfileProxy() rejected the request" }
        Log.i(TAG, "Waiting for HFP proxy")
        Looper.loop()
    }
}
