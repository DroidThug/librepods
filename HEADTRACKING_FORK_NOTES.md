# Head-tracked spatial audio via Shizuku (no root)

Feeds AirPods head orientation to Android's own audio Spatializer as a standard `TYPE_HEAD_TRACKER` dynamic sensor, so
system-wide spatial audio (here Dolby, via the OEM effect) follows your head. No root: a virtual `/dev/uhid` HID device is
created from a Shizuku UserService running with shell identity.

Tested only on a OnePlus 11 (CPH2447), ColorOS 16 / Android 16, with AirPods Pro. It needs an OEM spatializer that supports
head tracking; other phones and AirPods models are untested. Credits and licenses: `THIRD_PARTY_NOTICES.md`.

## What works

- `dumpsys sensorservice` lists `#AndroidHeadTracker#1.0 ... type: android.sensor.head_tracker(37)`, advertising 10-100 Hz.
- With a spatialized stream playing (for example Apple Music Atmos or Spotify), `dumpsys audio` shows
  `headtracker available:true` and `mActualHeadTrackingMode: HEAD_TRACKING_MODE_RELATIVE_WORLD`; audioserver subscribes to
  our sensor at 10 ms.
- Head turns, nods and body rotation audibly anchor the sound field. Verified by ear on one device; a forced "head turned 90
  degrees left" pose puts the sound in the right ear, which confirms the yaw sign convention.
- The AirPods stream unique samples at 100 Hz at the 10 ms setting (device timestamps exactly 10.0 ms apart).

## How it works

```
AirPods IMU --AACP 0x17 (L2CAP, LibrePods)--> AACPManager.handleSensorData()
   decode quaternion, neutral pose, head frame, gyro --> ShizukuHeadTracker.sendPose()   (app process)
   --Binder (oneway)--> HeadTrackerUserService --> UhidHeadTracker --> /dev/uhid          (shell process)
   --> kernel hid-sensor --> DynamicSensorHal --> SensorService --> audioserver Spatializer
```

### Why a Shizuku UserService (Binder), not a socket

A separately launched `app_process` talking to the app over a Unix socket does not work here. SELinux denies
`unix_stream_socket connectto` between `untrusted_app` and `shell` in both directions:

```
avc: denied { connectto } scontext=u:r:shell:s0 tcontext=u:r:untrusted_app:s0 tclass=unix_stream_socket
avc: denied { connectto } scontext=u:r:untrusted_app:s0 tcontext=u:r:shell:s0 tclass=unix_stream_socket
```

Binder crosses that boundary (it is how Shizuku itself works), so `HeadTrackerUserService` is launched by Shizuku in its own
process with shell identity and the app calls it through `IHeadTrackerService` (AIDL; `sendPose` is `oneway` so the AACP
reader thread never blocks on it). On start the service kills any sibling process with the same command line, because
Shizuku does not reliably reap an old instance after a reinstall and a stale one keeps its own sensor registered.

### The HID device (AOSP Head Tracker HID protocol)

Follows https://source.android.com/docs/core/sensors/head-tracker-hid-protocol:

- Sensors page `0x20`, usage Custom `0xE1`; `bus` virtual, vendor/product copied from the O-Pose reference device.
- Feature report 2 (read): `SENSOR_DESCRIPTION` = the exact 23 ASCII bytes `#AndroidHeadTracker#1.0` (no terminator),
  `PERSISTENT_UNIQUE_ID` = 8 zero bytes, `BT`, then the 6 address bytes of the AirPods. The generic `#ANDROID#type,...` tag or
  a numeric type only gives a generic custom sensor (and this ROM rejects type 37 that way).
- Feature report 1: reporting state, power state, report interval. The report interval field is 6 bits mapping 0..63 to a
  physical 10..100 ms range, as the spec describes. Copying a descriptor that fixes it at 40-41 ms caps audioserver at 25 Hz.
- Input report 1 (14 bytes): rotation vector 3 x int16 (+/-32767 = +/-pi rad), angular velocity 3 x int16 (+/-32767 = +/-32 rad/s),
  discontinuity counter (uint8, currently constant 1).
- Every `UHID_GET_REPORT` and `UHID_SET_REPORT` must be answered with the matching id, or the consumer closes the device.
  Sizes matter: report 2 must be 1 + 23 + 16 = 40 bytes.
- This ROM's HAL never sends `SET_REPORT` when enabling the sensor, so output is not gated on it.

### AirPods data

The AACP sensor stream (`SensorDataWX`, DEVMOTION6) carries a 58-byte payload. Offsets are into the payload:

| Offset | Field |
|---|---|
| 20, 22, 24 | attitude: x, y, z of a unit quaternion, int16 LE / 32768; w = sqrt(1 - x^2 - y^2 - z^2) >= 0 |
| 26, 28, 30 | gyro, int16, about 1/512 rad/s per LSB (same frame as the quaternion) |
| 44, 46, 48 | gravity / accelerometer, about 1000 per g |

(Offsets into a whole AACP packet are these plus a 23-byte header; older code and issues quote 43/45/47 for the attitude.)
Frames whose quaternion vector part has |v|^2 outside 0.01..1.01 are rejected.

Conversion in `AACPManager`:

1. **Neutral pose**: hemisphere-aligned mean of 12 frames; the window restarts if the head moves more than about 6 degrees
   (up to 5 restarts). The accelerometer vector is averaged over the same window. `recenterPose()` repeats this.
2. **Relative rotation** `conj(neutral) * q`, shortest path (w >= 0), converted to a rotation vector in radians.
3. **Head frame** (Android: x right, y forward, z up; +z = turn left): x is the AirPods' ear axis, fixed and measured on-device;
   z is "up" from the averaged accelerometer at the neutral pose, orthogonalized against x; y = z cross x. The measured sensor
   axes were: turning left reads negative along (-0.726, 0.680, 0.106), nodding down reads positive along (-0.091, -0.172, 0.981).
   Taking "up" from gravity instead of a fixed axis removes yaw/pitch cross-talk when an earbud is seated differently.
4. **Angular velocity**: the gyro rotated into the same head frame, times 2 (gyro 1/512 rad/s per LSB vs HID 1/1024). A
   least-squares fit of gyro against the quaternion's rate of change on a 65 s capture gave an identity mapping, R^2 0.999. The
   platform uses velocity to predict about 120 ms ahead.
5. Scale rotation to `32768 == pi` and send.

AirPods stream rate is the interval in the 0x17 start packet (Diagnostics selector: 40 / 20 / 10 ms). Measured at 10 ms: about
99 Hz over 43 s, almost no repeated samples. Bluetooth arrival is bursty (median gap 8 ms, p95 33 ms, max 132 ms).

### Platform behaviour (read from AOSP, not verified on ColorOS)

`SpatializerPoseController` recenters when the head stays within about 10.5 degrees for 6 s, and rate-limits the jump
(0.8 rad/s). That bounds yaw drift in normal use, so we do not add our own recenter leak.

## Using it

- Enable: AirPods -> Head Gestures -> "Spatial audio head tracking (experimental, needs Shizuku)". It toasts if Shizuku is not
  running and requests Shizuku permission if missing. Stored as `spatial_head_tracking_enabled` in the app's `settings`
  SharedPreferences (not Room).
- `services/SpatialHeadTrackingController.kt` (owned by `LibrePodsService`): while the toggle is on, Shizuku is up and the AirPods
  are connected, it starts the bridge and the AACP sensor stream; it stops on toggle off or disconnect and restarts on reconnect.
  Retries back off 5/10/20/40/60 s and stop after 10 failures; it pauses during calls. A dead bridge process is detected
  (`ShizukuHeadTracker.connected`) and restarted. While the toggle is on the Head Gestures pause button is disabled.
- Diagnostics screen (button on Head Gestures): live rate, head rotation, quaternion, accelerometer and bridge state; "Recenter
  now"; AirPods stream interval selector; yaw drift test; CSV logging to
  `Android/data/me.kavishdevar.librepods/files/headtracking_logs/*.csv` with Share (flushes every 2 s, stops at 20 MB or when
  the screen closes); and a 3D head visualizer with a live mode and a replay mode for those CSVs (play/pause, speed,
  scrub). Columns: `elapsed_ms,payload_hex,qw,qx,qy,qz,ax,ay,az,head_x_rad,head_y_rad,head_z_rad,sent_rx,sent_ry,sent_rz`.
  The visualizer and the screens were only compile-checked beyond basic use; layout and frame pacing are unverified.

### Debugging commands

```
adb shell dumpsys sensorservice | grep -A1 head_tracker        # one sensor expected, 10-100 Hz
adb shell dumpsys audio | grep -A14 "SpatializerHelper:"        # headtracker available / actual mode
adb shell dumpsys audio | grep AudioPlaybackConfiguration | grep started   # isSpatialized, channelMask
adb shell ps -A | grep librepods:headtracker                    # exactly one process expected
```

## Gotchas

- A reinstall does not restart the Shizuku UserService; check `ps` or toggle off/on so you are not testing old code.
- `logcat -d` drops lines from a small noisy buffer; stream to a file while testing.
- Do not cycle the Bluetooth adapter or force-stop `com.android.bluetooth` while testing; it can drop or unpair the AirPods.
- Another virtual head tracker (for example O-Pose's `vtracker3`) registers its own sensor; stop it when testing ours.
- After many quick reinstalls the AirPods can keep the old AACP channel half-open. `socket.connect()` then returns but
  `isConnected` stays false and `sendDelayedInitPackets()` loops on "waiting for connection..." forever. Reconnecting the
  AirPods clears it. Not fixed: that loop should time out and retry.
- A phone Spatializer sounding different from Apple's Atmos on a Mac is the renderer and HRTF, not head tracking. An Atmos
  stream reaches the phone as a 7.1.4 spatialized PCM stream (`channelMask=0x2d63f`).

## Open items

- Bluetooth arrival jitter is stamped on arrival; a jitter buffer or timestamp-based prediction could smooth it.
- The discontinuity counter should increment on recenter, as the spec requires.
- Ear-axis constants were measured on one pair of AirPods Pro; other models may need a per-device calibration.
- Not looked at: AirPods 3 (reportedly use RemoteHID instead of `SensorDataWX`), AirPods ear-detection and primary-bud changes
  (which can cancel the stream), and devices whose spatializer lacks head tracking.

## Code map

- `core/.../privileged/UhidHeadTracker.kt`: `/dev/uhid` wire protocol (from the public kernel UAPI headers) and the HID
  descriptor/identity above.
- `core/.../privileged/HeadTrackerUserService.kt`, `IHeadTrackerService.aidl`: the Shizuku UserService.
- `core/.../privileged/ShizukuHeadTracker.kt`: app-side bridge (bind, `sendPose`, connection state).
- `core/.../privileged/HeadPoseTelemetry.kt`: latest sample for Diagnostics, published only while observed or logging.
- `core/.../bluetooth/aacp/AACPManager.kt`: payload decode and the pose conversion in `handleSensorData()`.
- `app/.../services/SpatialHeadTrackingController.kt`: lifecycle and retries.
- `app/.../screens/apple/HeadTrackingScreen.kt` (toggle), `HeadTrackingDiagnosticsScreen.kt`, `HeadOrientationView.kt`,
  `HeadPoseReplay.kt`.
- `AndroidManifest.xml` declares `rikka.shizuku.ShizukuProvider` manually (it does not merge from the dependency).
- `:privileged` module (`Main.kt`): unrelated `disable-hfp` experiment, a one-shot `exec()` call. Its hidden-API uses
  (`ActivityThread`, `BluetoothHeadset.setConnectionPolicy`) were converted to reflection because they never compiled against
  the public stubs. Writing Bluetooth device metadata through Shizuku was a dead end: ColorOS overrides `METADATA_DEVICE_TYPE`.
