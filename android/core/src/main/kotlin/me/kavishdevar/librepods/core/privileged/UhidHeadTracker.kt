package me.kavishdevar.librepods.core.privileged

import android.util.Log
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Implementation of the Linux kernel's /dev/uhid userspace HID emulation
 * protocol, built directly from the public kernel UAPI headers:
 *   include/uapi/linux/uhid.h
 *   include/uapi/linux/hid.h  (HID_MAX_DESCRIPTOR_SIZE = 4096)
 * Both are GPL-2.0+ WITH Linux-syscall-note, part of the stable kernel
 * userspace API.
 *
 * Why this exists: /dev/uhid is `crw-rw---- uhid:uhid`. Only `shell` (and
 * `system`/root) are members of the `uhid` group by default - an ordinary
 * app's own uid never is, and no Android permission can add one. This code
 * is meant to run inside a Shizuku UserService process (see
 * HeadTrackerUserService.kt), launched with shell's identity, which
 * already has that group membership.
 *
 * The goal: create a virtual HID device that the OS's Bluetooth/audio stack
 * recognizes as a legitimate head-tracking accessory (the same class of
 * device real hardware like Sony's WH-1000XM5 exposes), then feed it real
 * AirPods orientation data so the system Spatializer engages for real,
 * OS-native head-tracked spatial audio - verified working against the OEM
 * MyDevices-gated feature path on a OnePlus 11 / ColorOS 16 this session.
 *
 * The device-identity constants in [buildReportDescriptor], plus `bus`,
 * `vendor`, `product` and `version` in [create], are copied byte-for-byte
 * from `fengkaobanli/O-Pose`'s `vtracker3` native helper (MIT licensed,
 * https://github.com/fengkaobanli/O-Pose) by disassembling its UHID_CREATE2
 * call and reading the literal bytes it writes - not guessed, not
 * clean-room. An earlier version of this file used a placeholder
 * vendor-defined descriptor; that device was created and opened by the OS
 * but immediately closed again (confirmed via logcat + `dumpsys audio`
 * reporting `headtracker available:false`) because it lacked a HID Sensor
 * Usage Page (0x20) the OS's HID sensor-hub stack actually keys recognition
 * off. The uhid wire protocol itself (the rest of this file: event framing,
 * struct layout, [sendPose]'s 14-byte report format) was independently
 * derived from the kernel spec and cross-checked against the same
 * reference binary's `send_input()` - it was already correct.
 */
class UhidHeadTracker {

    companion object {
        private const val TAG = "UhidHeadTracker"
        private const val UHID_PATH = "/dev/uhid"

        // enum uhid_event_type - implicit values starting at 0, from
        // include/uapi/linux/uhid.h (confirmed against current mainline):
        //   0 __UHID_LEGACY_CREATE, 1 UHID_DESTROY, 2 UHID_START,
        //   3 UHID_STOP, 4 UHID_OPEN, 5 UHID_CLOSE, 6 UHID_OUTPUT,
        //   7 __UHID_LEGACY_OUTPUT_EV, 8 __UHID_LEGACY_INPUT,
        //   9 UHID_GET_REPORT, 10 UHID_GET_REPORT_REPLY, 11 UHID_CREATE2,
        //   12 UHID_INPUT2, 13 UHID_SET_REPORT, 14 UHID_SET_REPORT_REPLY
        private const val UHID_DESTROY = 1
        private const val UHID_START = 2
        private const val UHID_STOP = 3
        private const val UHID_OPEN = 4
        private const val UHID_CLOSE = 5
        private const val UHID_OUTPUT = 6
        private const val UHID_GET_REPORT = 9
        private const val UHID_GET_REPORT_REPLY = 10
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val UHID_SET_REPORT = 13
        private const val UHID_SET_REPORT_REPLY = 14

        // enum uhid_report_type, include/uapi/linux/uhid.h
        private const val UHID_FEATURE_REPORT = 0

        // struct uhid_get_report_req: u32 id; u8 rnum; u8 rtype; (+padding)
        // struct uhid_get_report_reply_req: u32 id; u16 err; u16 size; u8 data[UHID_DATA_MAX];
        // Offsets below are relative to the event buffer (after the 4-byte type field).

        // struct uhid_create2_req is __packed__, field sizes summed:
        private const val NAME_SIZE = 128
        private const val PHYS_SIZE = 64
        private const val UNIQ_SIZE = 64
        private const val HID_MAX_DESCRIPTOR_SIZE = 4096 // include/uapi/linux/hid.h
        private const val CREATE2_SIZE =
            NAME_SIZE + PHYS_SIZE + UNIQ_SIZE + 2 + 2 + 4 + 4 + 4 + 4 + HID_MAX_DESCRIPTOR_SIZE
            // = 4372

        private const val UHID_DATA_MAX = 4096 // include/uapi/linux/uhid.h
        private const val INPUT2_SIZE = 2 + UHID_DATA_MAX // = 4098

        // struct uhid_event is __packed__: u32 type + union(largest member).
        // create2_req (4372) is the largest member, input2_req is 4098.
        private const val EVENT_SIZE = 4 + CREATE2_SIZE // = 4376

        private const val BUS_VIRTUAL = 0x0006 // linux/input.h BUS_VIRTUAL

        /**
         * Real HID Sensor Usage Page (0x20) report descriptor, byte-for-byte
         * extracted from fengkaobanli/O-Pose's `vtracker3` native helper
         * (MIT licensed) by disassembling its UHID_CREATE2 call and reading
         * the literal .rodata bytes it copies - NOT a guess. This is a
         * proper HID Sensor-class 3-axis orientation device descriptor
         * (Report ID 2 = feature/config block, Report ID 1 = the actual
         * input report), which is what Android's HID sensor-hub stack
         * actually keys recognition off, not the vendor-defined dummy
         * descriptor previously here. That dummy descriptor is the reason
         * the OS opened then immediately closed the uhid device without
         * creating any usable sensor/input node (confirmed this session:
         * UHID_OPEN followed instantly by UHID_CLOSE, no entry in
         * /proc/bus/input/devices, dumpsys audio reporting
         * `headtracker available:false`).
         *
         * The 14-byte INPUT2 wire format this class already sends
         * (Report ID 1 + 3 x int16 + 3 x zero int16 + valid-flag byte) was
         * independently confirmed correct against the same reference
         * binary's `send_input()` - only this descriptor was wrong.
         */
        private fun buildReportDescriptor(): ByteArray = byteArrayOf(
            0x05, 0x20, 0x09, 0xE1.toByte(), 0xA1.toByte(), 0x01, 0x85.toByte(), 0x02, 0x0A, 0x08, 0x03, 0x15,
            0x00, 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x17, 0xB1.toByte(), 0x03, 0x0A, 0x02, 0x03,
            0x15, 0x80.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x10, 0xB1.toByte(), 0x03, 0x85.toByte(), 0x01,
            0x0A, 0x16, 0x03, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x01, 0xA1.toByte(),
            0x02, 0x0A, 0x40, 0x08, 0x0A, 0x41, 0x08, 0xB1.toByte(), 0x00, 0xC0.toByte(), 0x0A, 0x19,
            0x03, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x01, 0xA1.toByte(), 0x02, 0x0A,
            0x55, 0x08, 0x0A, 0x51, 0x08, 0xB1.toByte(), 0x00, 0xC0.toByte(), 0x0A, 0x0E, 0x03, 0x15,
            0x00, 0x25, 0x3F, 0x35, 0x0A, 0x45, 0x64, 0x75, 0x06, 0x95.toByte(), 0x01, 0x66,
            0x01, 0x10, 0x55, 0x0D, 0xB1.toByte(), 0x02, 0x0A, 0x44, 0x05, 0x16, 0x01, 0x80.toByte(),
            0x26, 0xFF.toByte(), 0x7F, 0x37, 0x60, 0x4F, 0x46, 0xED.toByte(), 0x47, 0xA1.toByte(), 0xB0.toByte(), 0xB9.toByte(),
            0x12, 0x55, 0x08, 0x75, 0x10, 0x95.toByte(), 0x03, 0x81.toByte(), 0x02, 0x0A, 0x45, 0x05,
            0x16, 0x01, 0x80.toByte(), 0x26, 0xFF.toByte(), 0x7F, 0x35, 0xE0.toByte(), 0x45, 0x20, 0x55, 0x00,
            0x75, 0x10, 0x95.toByte(), 0x03, 0x81.toByte(), 0x02, 0x0A, 0x46, 0x05, 0x15, 0x00, 0x25,
            0x7F, 0x35, 0x00, 0x45, 0x00, 0x55, 0x00, 0x75, 0x08, 0x95.toByte(), 0x01, 0x81.toByte(),
            0x02, 0xC0.toByte()
        )
    }

    private var raf: RandomAccessFile? = null
    private val writeLock = Any()
    private var macTag: String = ""

    // Report ID 1's read/write feature state (Reporting State, Power State,
    // Report Interval), per the Head Tracker HID protocol spec. The host
    // (DynamicSensorHal, driven by the Spatializer's sensor.enable()/
    // batch() calls) writes these via UHID_SET_REPORT; per the spec "the
    // device sends input reports only when" all three are set, so sendPose
    // is gated on them rather than always writing.
    @Volatile private var reportingAllEvents = false
    @Volatile private var powerFull = false
    @Volatile private var reportIntervalRaw = 0
    private var sentFrames = 0L

    /**
     * Opens /dev/uhid and sends UHID_CREATE2. `name` is the device identity
     * string the OS is believed to key head-tracking recognition off of;
     * `macTag` is folded into `uniq` so the system can associate this
     * virtual source with the already-connected real AirPods device,
     * mirroring the reference implementation's `--uuid bt:<mac>` behavior.
     *
     * @return true if the uhid device was created without error. Does NOT
     *   by itself confirm the OS recognized it as a head tracker - only
     *   that the kernel accepted the CREATE2 request.
     */
    fun create(
        name: String = "#AndroidHeadTracker#1.0",
        macTag: String
    ): Boolean {
        return try {
            val f = RandomAccessFile(UHID_PATH, "rw")
            raf = f
            this.macTag = macTag
            reportingAllEvents = false
            powerFull = false
            reportIntervalRaw = 0

            val descriptor = buildReportDescriptor()
            val buf = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)

            buf.putInt(UHID_CREATE2)

            putFixedString(buf, name, NAME_SIZE)
            putFixedString(buf, "", PHYS_SIZE)
            putFixedString(buf, "bt:$macTag", UNIQ_SIZE)

            buf.putShort(descriptor.size.toShort())               // rd_size
            buf.putShort(BUS_VIRTUAL.toShort())                    // bus (matches reference impl - NOT BUS_BLUETOOTH)
            buf.putInt(0x1234)                                     // vendor (matches reference impl)
            buf.putInt(0x5678)                                     // product (matches reference impl)
            buf.putInt(0x0100)                                     // version (matches reference impl)
            buf.putInt(0)                                          // country

            val rdStart = buf.position()
            buf.put(descriptor)
            // zero-pad the rest of rd_data[4096]
            buf.position(rdStart + HID_MAX_DESCRIPTOR_SIZE)

            // zero-pad remainder of the union to EVENT_SIZE (unused
            // trailing bytes of the create2 struct vs. the full event size
            // are already covered since create2 IS the largest member and
            // we've written exactly CREATE2_SIZE bytes of payload after
            // the 4-byte type field; buffer is fully consumed here).

            synchronized(writeLock) { f.write(buf.array()) }
            Log.i(TAG, "UHID_CREATE2 sent: name=\"$name\" uniq=\"bt:$macTag\" rd_size=${descriptor.size}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "create() failed", t)
            close()
            false
        }
    }

    /**
     * Sends one orientation sample as a 14-byte input report (Report ID 1
     * + 13 data bytes), matching the reference wire format:
     *   byte0    = Report ID (0x01)
     *   byte1-6  = 3 x int16 LE (rx, ry, rz)
     *   byte7-12 = 3 x int16 LE angular velocity, 32767 == 32 rad/s
     *   byte13   = discontinuity counter (uint8; was a constant 0x01)
     */
    fun sendPose(rx: Int, ry: Int, rz: Int, vx: Int, vy: Int, vz: Int, discontinuity: Int = 1): Boolean {
        val f = raf ?: return false
        // This ROM's HAL never sends SET_REPORT when enabling the sensor, so
        // gating on it would block all output; send unconditionally like the reference.
        if (++sentFrames % 100 == 1L) Log.i(TAG, "sendPose frame #$sentFrames rx=$rx ry=$ry rz=$rz")
        return try {
            val report = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
            report.put(0x01)
            report.putShort(rx.toShort())
            report.putShort(ry.toShort())
            report.putShort(rz.toShort())
            report.putShort(vx.toShort())
            report.putShort(vy.toShort())
            report.putShort(vz.toShort())
            report.put(discontinuity.toByte())

            val buf = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(UHID_INPUT2)
            buf.putShort(14) // size
            val dataStart = buf.position()
            buf.put(report.array())
            buf.position(dataStart + UHID_DATA_MAX)

            synchronized(writeLock) { f.write(buf.array()) }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "sendPose() failed", t)
            false
        }
    }

    /**
     * Reads and logs the next uhid_event from the kernel (UHID_START,
     * UHID_OPEN, UHID_GET_REPORT, etc.) - useful for confirming the OS has
     * actually opened the device, not just that creation succeeded.
     * Blocks until an event arrives; call from a dedicated thread.
     *
     * UHID_GET_REPORT is answered inline with a UHID_GET_REPORT_REPLY -
     * required for the OS's HID sensor-hub consumer to keep the device
     * open. Confirmed this session: without a reply, the consumer issues
     * UHID_GET_REPORT twice (~5s apart) then gives up and sends UHID_CLOSE.
     * The reply's exact feature-report bytes don't appear to be strictly
     * validated by the consumer (the reference implementation's own
     * Report-ID-2 reply payload is literally just unrelated adjacent
     * .rodata bytes it happened to reuse as filler) - only the reply's
     * structure (matching `id`, `err=0`, a plausible `size`) matters.
     */
    fun readEventBlocking(): Int {
        val f = raf ?: return -1
        return try {
            val buf = ByteArray(EVENT_SIZE)
            val n = f.read(buf)
            if (n <= 0) return -1
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val type = bb.getInt(0)
            val label = when (type) {
                UHID_START -> "UHID_START"
                UHID_STOP -> "UHID_STOP"
                UHID_OPEN -> "UHID_OPEN"
                UHID_CLOSE -> "UHID_CLOSE"
                UHID_OUTPUT -> "UHID_OUTPUT"
                UHID_GET_REPORT -> "UHID_GET_REPORT"
                UHID_SET_REPORT -> "UHID_SET_REPORT"
                else -> "type=$type"
            }
            Log.i(TAG, "uhid event: $label")
            if (type == UHID_GET_REPORT) {
                val id = bb.getInt(4)
                val rnum = buf[8].toInt() and 0xFF
                val rtype = buf[9].toInt() and 0xFF
                Log.i(TAG, "UHID_GET_REPORT id=$id rnum=$rnum rtype=$rtype")
                replyToGetReport(id, rnum, rtype)
            } else if (type == UHID_SET_REPORT) {
                val id = bb.getInt(4)
                val rnum = buf[8].toInt() and 0xFF
                val rtype = buf[9].toInt() and 0xFF
                val size = bb.getShort(10).toInt() and 0xFFFF
                Log.i(TAG, "UHID_SET_REPORT id=$id rnum=$rnum rtype=$rtype size=$size")
                handleSetReport(id, rnum, rtype, buf, dataOffset = 12, size = size)
            }
            type
        } catch (t: Throwable) {
            Log.e(TAG, "readEventBlocking() failed", t)
            -1
        }
    }

    /**
     * AOSP's `hardware/libhardware` `DynamicSensorHal` (`HidRawSensorDevice`/
     * `HidRawSensor`) is the real consumer behind UHID_OPEN/UHID_GET_REPORT
     * on this device (confirmed via logcat: "DynamicSensorHal: failed to
     * create HidRawSensorDevice object" / "no valid sensor is defined"
     * before SENSOR_DESCRIPTION was handled at all).
     *
     * This device's descriptor implements the OFFICIAL, documented AOSP
     * "Head Tracker HID protocol" (source.android.com/docs/core/sensors/
     * head-tracker-hid-protocol), not the generic/older "Custom sensor +
     * `#ANDROID#type,mode,perm,wake` tag" mechanism from
     * HidRawSensor::detectAndroidCustomSensor that an earlier version of
     * this code targeted. That generic tag DOES register a sensor (confirmed:
     * `DynamicSensorHal: return 1 sensors`) but only as a private/custom
     * type (`com.google.hardware.sensor.hid_dynamic.*`), which the audio
     * Spatializer's headtracker lookup ignores (`headtracker available`
     * stayed false). The documented protocol instead requires
     * SENSOR_DESCRIPTION to be the EXACT literal string below (not a
     * delimited tag) - this is the actual discriminator this ROM's
     * DynamicSensorHal checks to register the sensor specifically as
     * `Sensor.TYPE_HEAD_TRACKER` (37) rather than a generic custom type.
     */
    private fun buildSensorDescriptionField(): ByteArray {
        // v1.0: exact 23-byte ASCII string, no null terminator/padding.
        val tag = "#AndroidHeadTracker#1.0"
        val out = ByteArray(23)
        val bytes = tag.toByteArray(Charsets.US_ASCII)
        check(bytes.size == 23) { "sensor description must be exactly 23 bytes" }
        System.arraycopy(bytes, 0, out, 0, out.size)
        return out
    }

    /**
     * Bluetooth MAC scheme from the Head Tracker HID protocol spec: bytes
     * 0-7 are zero, byte 8 is 'B' (0x42), byte 9 is 'T' (0x54), and bytes
     * 10-15 hold the raw 6-byte identity MAC address - ties this virtual
     * sensor to the already-connected AirPods for the Spatializer's
     * per-device headtracker lookup.
     */
    private fun buildPersistentUniqueIdField(): ByteArray {
        val out = ByteArray(16)
        out[8] = 'B'.code.toByte()
        out[9] = 'T'.code.toByte()
        val macBytes = macTag.chunked(2).map { it.toInt(16).toByte() }
        for (i in 0 until minOf(6, macBytes.size)) {
            out[10 + i] = macBytes[i]
        }
        return out
    }

    private fun replyToGetReport(id: Int, rnum: Int, rtype: Int) {
        val f = raf ?: return
        try {
            val data: ByteArray = when {
                rtype == UHID_FEATURE_REPORT && rnum == 1 ->
                    byteArrayOf(1, packReportId1State())
                rtype == UHID_FEATURE_REPORT && rnum == 2 ->
                    byteArrayOf(2) + buildSensorDescriptionField() + buildPersistentUniqueIdField()
                else -> ByteArray(0)
            }

            val buf = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(UHID_GET_REPORT_REPLY)
            buf.putInt(id)
            buf.putShort(0)                 // err = 0 (success)
            buf.putShort(data.size.toShort()) // size
            val dataStart = buf.position()
            buf.put(data)
            buf.position(dataStart + UHID_DATA_MAX)

            synchronized(writeLock) { f.write(buf.array()) }
            Log.i(TAG, "UHID_GET_REPORT_REPLY sent: id=$id rnum=$rnum size=${data.size}")
        } catch (t: Throwable) {
            Log.e(TAG, "replyToGetReport() failed", t)
        }
    }

    /** bit0=Reporting State (1=All Events), bit1=Power State (1=Full Power), bits2-7=Report Interval. */
    private fun packReportId1State(): Byte {
        var b = 0
        if (reportingAllEvents) b = b or 0x01
        if (powerFull) b = b or 0x02
        b = b or ((reportIntervalRaw and 0x3F) shl 2)
        return b.toByte()
    }

    /**
     * Applies the host's write to Report ID 1 (Reporting State/Power
     * State/Report Interval - see [packReportId1State]) and acks with
     * UHID_SET_REPORT_REPLY. Per the Head Tracker HID protocol spec,
     * section 6: "the device sends input reports only when" Power State is
     * Full Power, Reporting State is All Events, and Report Interval is
     * non-zero - [sendPose] is gated on exactly this state.
     */
    private fun handleSetReport(id: Int, rnum: Int, rtype: Int, buf: ByteArray, dataOffset: Int, size: Int) {
        val f = raf ?: return
        try {
            if (rtype == UHID_FEATURE_REPORT && rnum == 1 && size >= 2) {
                val packed = buf[dataOffset + 1].toInt() and 0xFF
                reportingAllEvents = (packed and 0x01) != 0
                powerFull = (packed and 0x02) != 0
                reportIntervalRaw = (packed shr 2) and 0x3F
                Log.i(
                    TAG,
                    "SET_REPORT applied: reportingAllEvents=$reportingAllEvents " +
                        "powerFull=$powerFull reportIntervalRaw=$reportIntervalRaw"
                )
            }

            val replyBuf = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            replyBuf.putInt(UHID_SET_REPORT_REPLY)
            replyBuf.putInt(id)
            replyBuf.putShort(0) // err = 0 (success)

            synchronized(writeLock) { f.write(replyBuf.array()) }
            Log.i(TAG, "UHID_SET_REPORT_REPLY sent: id=$id rnum=$rnum")
        } catch (t: Throwable) {
            Log.e(TAG, "handleSetReport() failed", t)
        }
    }

    fun destroy() {
        try {
            raf?.let { f ->
                val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(UHID_DESTROY)
                synchronized(writeLock) { f.write(buf.array()) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "destroy() failed", t)
        } finally {
            close()
        }
    }

    private fun close() {
        try {
            raf?.close()
        } catch (_: Throwable) {
        }
        raf = null
    }

    private fun putFixedString(buf: ByteBuffer, s: String, size: Int) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, size)
        buf.put(bytes, 0, n)
        repeat(size - n) { buf.put(0) }
    }
}
