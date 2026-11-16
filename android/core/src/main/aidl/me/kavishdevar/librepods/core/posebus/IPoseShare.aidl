// LibrePods - AirPods liberated from Apple's ecosystem
// Copyright (C) 2025 LibrePods contributors
// GPL-3.0-or-later

package me.kavishdevar.librepods.core.posebus;

import android.os.SharedMemory;

/**
 * Head pose bus (see HEADTRACKING_FORK_NOTES.md, "Pose bus"). Served by the app's PoseShareService
 * (action me.kavishdevar.librepods.POSE_SHARE) to allowlisted packages only (SecurityException otherwise).
 * Wire contract for clients without this file: descriptor "me.kavishdevar.librepods.core.posebus.IPoseShare",
 * transaction 1 = getPoseMemory, 2 = getLayoutVersion. Do not reorder; append new methods at the end.
 */
interface IPoseShare {
    /** The read-only pose region (layout v1). The same region for the lifetime of the app process. */
    SharedMemory getPoseMemory();

    /** Layout version of the region, currently 1. */
    int getLayoutVersion();
}
