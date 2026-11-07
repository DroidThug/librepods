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
  discontinuity counter (uint8). It starts at 1 and increments (wrapping) on every `recenterPose()` that replaces an
  existing neutral pose, because the output then jumps back to about zero.
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
2. **Relative rotation** (default, gravity-aligned frame, `core/.../privileged/HeadFrame.kt`): `q * conj(neutral)`, the
   rotation in the quaternion's own world frame, shortest path (w >= 0), as a rotation vector in radians. The AirPods
   quaternion maps sensor vectors into a world frame with +z up: `R(q)^T (0,0,-1)` matches the accelerometer vector (which
   points down) with a median of 1.2 deg on the 2026-11-07 chair log and 3.6 deg on a 2026-11-06 log.
3. **Head frame, default**: z = world up exactly, x = the fixed ear axis (below) mapped into the world at the neutral pose and
   projected onto the horizontal (it only splits pitch from roll), y = z cross x. A turn about the vertical is then exactly
   head z. If the ear axis is within ~11.5 deg of vertical at the neutral pose, the legacy frame below is used instead.
   **Legacy head frame** (preference `spatial_head_tracking_legacy_frame` = true, or toggle "Legacy head frame" on Head
   Gestures; output bit-identical to before): relative rotation `conj(neutral) * q` in the sensor frame, and
   (Android: x right, y forward, z up; +z = turn left): x is the AirPods' ear axis, fixed and measured on-device;
   z is "up" from the averaged accelerometer at the neutral pose, orthogonalized against x; y = z cross x. The measured sensor
   axes were: turning left reads negative along (-0.726, 0.680, 0.106), nodding down reads positive along (-0.091, -0.172, 0.981).
   Taking "up" from gravity instead of a fixed axis removes yaw/pitch cross-talk when an earbud is seated differently.
4. **Angular velocity**: default: the gyro rotated sensor -> world with the current attitude, then onto the neutral head
   axes. Legacy: the gyro projected on the neutral sensor-frame basis. Both times 2 (gyro 1/512 rad/s per LSB vs HID 1/1024). A
   least-squares fit of gyro against the quaternion's rate of change on a 65 s capture gave an identity mapping, R^2 0.999. The
   platform uses velocity to predict about 120 ms ahead.
5. Scale rotation to `32768 == pi` and send.

AirPods stream rate is the interval in the 0x17 start packet (Diagnostics selector: 40 / 20 / 10 ms). Measured at 10 ms: about
99 Hz over 43 s, almost no repeated samples. Bluetooth arrival is bursty (median gap 8 ms, p95 33 ms, max 132 ms).

### Platform behaviour (read from AOSP, not verified on ColorOS)

`SpatializerPoseController` recenters when the head stays within about 10.5 degrees for 6 s, and rate-limits the jump
(0.8 rad/s). That bounds yaw drift in normal use, so we do not add our own recenter leak.

### Why the gravity-aligned frame (evidence)

On a chair rotation (2026-11-07, 25 Hz) the true rotation axis from the gyro is within a few degrees of gravity, but the
legacy frame keeps the measured ear axis fixed and bends "up" around it, so its z was ~12.5 deg off vertical for this
pair (product 0x2027): the fixed ear/yaw constants were off by about 12 deg. Measured with `op11/ap/re/tempest/identity_check.sh`
(`tilt_metric.py`), legacy -> new frame, same payloads:

| log | tilt change per yaw change in pure turns | euler tilt sd per 100 deg yaw | off-axis share of HID velocity |
|---|---|---|---|
| 20261107_114918 chair | 23.8% -> 7.9% (3.5% on the cleanest samples) | 2.79 -> 0.89 | 31.5% -> 9.0% |
| 20261106_002956 free head motion | 15.6% -> 16.1% (11.0% -> 9.2% at a 3 deg axis gate) | 3.79 -> 3.55 | 32.3% -> 27.6% |

The second log contains real nods and neck turns (not about the vertical), so it cannot separate leak from motion; the
chair log is the controlled one. Rotation-vector x/y components still vary with yaw when the head holds a pitch offset;
that is the representation (the consumer rebuilds the exact pose), not a leak. The ear axis still only sets the pitch/roll
split, so a ~12 deg error there mixes pitch and roll slightly; a per-device ear-axis calibration would fix that.

### Per-bud ear-axis mirror (pitch/roll sign)

The fixed ear axis was measured with one bud as primary. If the left and right sensors are mirror images, the axis points
the wrong way when the other bud supplies the stream, which inverts pitch and roll but not yaw (z stays up, x flips, so
y = z cross x flips too). Hypothesis only: on log 20261107_121606.csv pitch went from 0 to -19 deg while the user looked up,
and yaw was correct (to be confirmed). Setting: `spatial_head_tracking_mirror_ear_left` / `_right` (default false), toggle
"Flip pitch/roll" on Head Gestures, which writes the key for the bud that is primary now (`AppleState.primaryComponent`,
read every frame, so a primary swap applies at once). It applies to both the new and the legacy frame; with it off, output
is unchanged. Caveat: it assumes the primary bud is the one streaming sensor data, and `primaryComponent` reads LEFT until
the first primary update arrives.

### Optional Apple-style recentering (off by default)

`core/.../privileged/TempestRecenter.kt`, enabled by the `spatial_head_tracking_apple_recenter` preference (toggle under the
spatial audio switch on Head Gestures). When the AirPods gyro rate stays at or below 10 deg/s for 5 s, the neutral reference is
rotated (slerp) onto the head pose at that moment, so the reported pose returns smoothly to zero. Quiet time drains while
moving instead of resetting. Numbers from CoreMotion (`op11/ap/re/binary/facts.md`): 10 deg/s, 5 s, 2.25 s (music horizon,
constant only), and the sweep-duration table S(offset): 10 s up to 5 deg, linear down to 0.5 s at 35 deg, 0.5 s beyond,
1.0 s outside [0, 2 pi], floored at 0.1 s. Apple overwrites its reference instantly and eases the audible output at
offset / S; moving our reference is the equivalent for our single output.

Which offset the speed uses is not settled by the facts, so there are two modes (`TempestConstants.SWEEP_MODE`):
`FIXED_AT_START` (default) takes S from the initial offset and moves at constant speed (90 deg in 0.5 s, 3 deg in 10 s).
`PER_FRAME` re-reads the remaining offset every sample, which gives an exponential-like decay (time constant ~10 s below
5 deg, ~0.5 s above 35 deg; 90% of a 90 deg offset takes ~8.5 s, of a small one ~23 s), and a new re-zero during a sweep
retargets it. The facts wording ("removes the offset in S seconds") leans to the fixed reading; the default is a choice, not a fact.
A smooth re-zero does not change the discontinuity counter (`TempestConstants.BUMP_DISCONTINUITY_ON_REZERO`), because AOSP
recenters its own head pose when the counter changes, which would combine with the sweep into an overshoot (read from
AOSP, unverified on a phone). Apple's rule needs both its own IMU and the source device's to be quiet; we only use the
AirPods, so holding a turned head still for 5 s also re-zeros there. With the option off the output is bit-identical to
before (checked by `op11/ap/re/tempest/identity_check.sh`). Not tested on a phone.

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
- Ear-axis constants were measured on one pair of AirPods Pro and look ~12 deg off for the 0x2027 pair; with the
  gravity-aligned frame they only affect the pitch/roll split, but a per-device calibration would still help.
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
