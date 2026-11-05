# Third-party notices (head-tracked spatial audio work)

The experimental Shizuku-based head-tracking bridge (`android/core/.../privileged/`, the
Head Gestures toggle, and the Diagnostics screen) builds on the following work.

## fengkaobanli/O-Pose (MIT)

https://github.com/fengkaobanli/O-Pose

The 170-byte HID Sensor-page report descriptor used by `UhidHeadTracker.kt`, the virtual
device's vendor/product identity and the 14-byte input report layout
were read from the prebuilt `vtracker3` binary shipped in that project, which proved on a
OnePlus device that Android's Spatializer accepts a virtual HID head tracker. The descriptor
bytes are therefore derived from MIT-licensed work. Two deliberate deviations: we use the
standard AOSP identity strings and a spec-range report interval (10-100 ms) instead of theirs,
and we run the uhid device in-process in a Shizuku UserService (Binder) instead of a separate
native binary fed over a localhost socket.

```
MIT License

Copyright (c) 2026 SpazPeek Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Android Open Source Project - Head Tracker HID Protocol

https://source.android.com/docs/core/sensors/head-tracker-hid-protocol

The report layout, the `#AndroidHeadTracker#1.0` sensor description, the Bluetooth persistent
unique ID scheme, the report-interval range and the angular-velocity scaling follow this
specification. Platform behaviour referenced in comments (auto-recentering, prediction) is read
from AOSP `SpatializerPoseController.cpp` / `HeadTrackingProcessor.cpp`.

## starman-tech/mousepods

https://github.com/starman-tech/mousepods

Independent documentation of the 58-byte DEVMOTION6 payload (attitude quaternion, gyro and
gravity fields, gyro scale). Used to cross-check our own measurements; no code was copied.

## Shizuku (Apache-2.0)

https://github.com/RikkaApps/Shizuku

Used to run the privileged UserService with shell identity (no root).

## LibrePods and contributors (GPL-3.0-or-later)

AACP transport, the AirPods sensor stream (opcode 0x17) and the RTBuddy packet handling are
LibrePods' own work. Issues #719 and #713 and PR #617 and #887 were used as references for the
data layout and the system-spatializer route.
