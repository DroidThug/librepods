// LibrePods - AirPods liberated from Apple's ecosystem
// Copyright (C) 2025 LibrePods contributors
// GPL-3.0-or-later

package me.kavishdevar.librepods.core.privileged;

/**
 * Bound via Shizuku's UserService mechanism, so this implementation runs
 * with shell's privilege (needed for /dev/uhid group membership) while
 * being called over Binder from the app's own process - Binder crosses
 * the untrusted_app<->shell SELinux boundary where raw Unix domain
 * sockets do not (confirmed via audit log this session; see
 * HEADTRACKING_FORK_NOTES.md for the full story).
 */
interface IHeadTrackerService {
    /** Opens /dev/uhid and creates the virtual head-tracker device. */
    boolean start(String mac);

    /**
     * Feeds one orientation sample. rx/ry/rz are signed int16-range values.
     * discontinuity is the HID discontinuity counter (uint8); it changes when the pose jumps.
     * oneway: called per frame from the AACP reader thread, which must not block.
     */
    oneway void sendPose(int rx, int ry, int rz, int vx, int vy, int vz, int discontinuity);

    /** Destroys the uhid device and releases /dev/uhid. */
    void stop();

    /** Lets the app ask the privileged process to exit entirely. */
    void destroy();

    // Appended methods only (transaction codes of the ones above must not move). A UserService
    // left over from an older install lacks these; callers catch the failure. Restart the
    // helper (toggle off/on) after a reinstall.

    /** Starts writing 0 to the cgroup freeze files of these uids every 300 ms. */
    void startKeepAlive(in int[] uids);

    void stopKeepAlive();

    /** First keep-alive failure not yet reported (one per uid), or null. */
    String takeKeepAliveFailure();

    /** Runs `cmd deviceidle whitelist +pkg` as shell; returns its output or the error. */
    String whitelistPackage(String pkg);
}
