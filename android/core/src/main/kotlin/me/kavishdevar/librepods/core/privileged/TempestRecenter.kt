// LibrePods - AirPods liberated from Apple's ecosystem
// Copyright (C) 2025 LibrePods contributors
// GPL-3.0-or-later

package me.kavishdevar.librepods.core.privileged

import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How the re-zero sweep eases the reference onto the target pose. */
enum class SweepMode {
    /**
     * Every sample: theta = remaining offset, S = sweepDurationS(theta), rotate by
     * min(theta, theta / S * dt). Decays roughly exponentially with time constant S(theta):
     * ~10 s for offsets <= 5 deg, ~0.5 s for >= 35 deg.
     */
    PER_FRAME,

    /** S from the offset when the re-zero starts, then constant angular speed theta0 / S. */
    FIXED_AT_START,
}

/**
 * Numbers for the optional "Apple-style recentering" stage, read from macOS 27.3 CoreMotion
 * (op11/ap/re/binary/facts.md; section and row given). Only the numbers are used; the logic here
 * is our own.
 *
 * Not modelled (TODO, facts.md section 1): the loose-quiescence counter and its 2x-horizon rule
 * (1.4, 1.5), the 5 s floor on the firing threshold in the default tracking behaviour (1.5), the
 * 20 s extended horizon after a camera anchor (1.6), the pedestrian head-turn lengthening (1.8;
 * needs a pedestrian/boresight classifier we do not have), the complex-transition and
 * slow-body-turn shortening (1.9), keep-boresight-centered (1.10), and not bleeding yaw when the
 * face is aligned with gravity (second pass 2.9).
 */
object TempestConstants {
    /** Quiet when |gyro| <= 10 deg/s (facts 1.3; also 4.1). */
    const val QUIET_RATE_RAD_S = 0.174533f

    /** Default bleed-to-zero horizon: minimum quiet time before a re-zero (facts 1.1, 1.2). */
    const val HORIZON_S = 5.0f

    /** Shortened horizon used in the steady-state pedestrian / music case (facts 1.7). */
    const val MUSIC_HORIZON_S = 2.25f

    /**
     * Sweep duration S (s) against offset angle theta (rad), piecewise linear (facts second pass
     * 2.7): 0..5 deg -> 10 s, 35..360 deg -> 0.5 s, linear in between.
     */
    val SWEEP_TABLE_RAD = floatArrayOf(0f, 0.0872665f, 0.6108652f, 6.2831855f)
    val SWEEP_TABLE_S = floatArrayOf(10f, 10f, 0.5f, 0.5f)

    /** S for an offset outside [0, 2 pi] (facts second pass 2.7). */
    const val SWEEP_OUT_OF_RANGE_S = 1.0f

    /** Floor on the sweep duration (facts 2.7). */
    const val MIN_SWEEP_S = 0.1f

    /**
     * facts 2.7 says the angular velocity is "offset angle / S", but not whether the angle is
     * re-read every sample (PER_FRAME) or held from the start of the sweep (FIXED_AT_START); the
     * wording ("removes the offset angle theta in S seconds") leans to the latter. FIXED_AT_START is
     * the default because PER_FRAME turns S into a decay time constant (a 90 deg offset needs ~8 s to
     * reach 90% done, small offsets ~23 s), which does not read as "a sweep of S seconds".
     * Unverified against Apple's actual behaviour.
     */
    val SWEEP_MODE = SweepMode.FIXED_AT_START

    /** PER_FRAME sweep ends (snaps to the target) once the remaining offset is below this, rad. */
    const val SWEEP_DONE_RAD = 0.001f

    /** A gap between samples longer than this counts as a stall (facts 3.6) and restarts the timer. */
    const val MAX_GAP_S = 0.5f

    /** AirPods gyro scaling, LSB per rad/s (fork measurement, HEADTRACKING_FORK_NOTES.md; not from facts). */
    const val GYRO_LSB_PER_RAD_S = 512f

    /**
     * Whether a smooth re-zero also increments the HID discontinuity counter. Off: in AOSP,
     * SpatializerPoseController treats a counter change as a new reference and recenters its own
     * head pose at once, so bumping at the start of a sweep would snap the sound field to the
     * front and then the sweep would push it the other way. A sweep is continuous, so it is not a
     * discontinuity. Unverified on a phone; flip to test.
     */
    const val BUMP_DISCONTINUITY_ON_REZERO = false
}

/**
 * Bleed-to-zero recentering: after the head has been quiet (gyro rate <= [quietRadS]) for
 * [horizonS] seconds, the neutral reference quaternion is rotated (slerp) onto the head pose at
 * that moment, eased per [mode] with the sweep table, so the reported pose returns smoothly to zero.
 * (Apple overwrites its reference instantly and eases the audible output instead, facts 2.9 and
 * 2.7; moving our reference smoothly is the equivalent for a single output.)
 *
 * Quiet time accumulates while quiet and drains by dt while moving, floored at 0 (facts 1.4), so a
 * single noisy sample does not restart the whole horizon. When a re-zero starts the quiet timer is
 * cleared, so another one needs another full horizon. In PER_FRAME mode a new re-zero during a
 * sweep retargets it; in FIXED_AT_START mode it waits for the sweep to finish.
 * Time comes from the caller (sample arrival time in nanoseconds) so the class is deterministic
 * and testable. Quaternions are (w, x, y, z).
 *
 * Not thread safe; call from the sensor thread.
 */
class TempestRecenter(
    var horizonS: Float = TempestConstants.HORIZON_S,
    private val mode: SweepMode = TempestConstants.SWEEP_MODE,
    private val quietRadS: Float = TempestConstants.QUIET_RATE_RAD_S,
) {
    /** Accumulated quiet time, seconds. */
    var quietS = 0f
        private set

    /** True if the last [update] started a re-zero. */
    var rezeroStarted = false
        private set

    /** Number of re-zeros started since construction. */
    var rezeroCount = 0
        private set

    /** Offset (rad) and sweep duration S (s) picked when the last re-zero started. */
    var lastStartOffsetRad = 0f
        private set
    var lastStartSweepS = 0f
        private set

    val sweeping: Boolean get() = sweepTo != null

    private var lastNanos = NO_TIME
    private var sweepFrom: FloatArray? = null
    private var sweepTo: FloatArray? = null
    private var sweepS = 0f
    private var sweepElapsedS = 0f

    /** Forgets timers and any sweep in progress (call when the neutral pose is recalibrated). */
    fun reset() {
        quietS = 0f
        rezeroStarted = false
        lastNanos = NO_TIME
        sweepFrom = null
        sweepTo = null
    }

    /**
     * Feeds one sample and returns the reference to use from now on: [ref] itself when nothing
     * changes, or a new array while a sweep is running.
     *
     * @param nanos sample arrival time, monotonic nanoseconds
     * @param rateRadS gyro rate magnitude, rad/s
     * @param cur current attitude (w, x, y, z), unit
     * @param ref current neutral reference (w, x, y, z), unit
     */
    fun update(nanos: Long, rateRadS: Float, cur: FloatArray, ref: FloatArray): FloatArray {
        rezeroStarted = false
        val last = lastNanos
        lastNanos = nanos
        if (last == NO_TIME) return ref
        val dt = (nanos - last) / 1e9f
        if (dt <= 0f || dt > TempestConstants.MAX_GAP_S) {
            // Stall or clock trouble: stop where we are, no jump, start counting again.
            quietS = 0f
            sweepFrom = null
            sweepTo = null
            return ref
        }

        quietS = if (rateRadS <= quietRadS) quietS + dt else max(0f, quietS - dt)

        // PER_FRAME: a new re-zero during a sweep retargets it. FIXED_AT_START: a sweep (up to 10 s)
        // runs to the end first, so it stays linear.
        if (quietS >= horizonS && (mode == SweepMode.PER_FRAME || !sweeping)) {
            quietS = 0f
            val theta = angle(ref, cur)
            sweepFrom = ref.copyOf()
            sweepTo = cur.copyOf()
            sweepS = sweepDurationS(theta)
            sweepElapsedS = 0f
            lastStartOffsetRad = theta
            lastStartSweepS = sweepS
            rezeroStarted = true
            rezeroCount++
            if (theta <= TempestConstants.SWEEP_DONE_RAD) {
                // Nothing to move: do not hold off the next re-zero for a 10 s no-op sweep.
                sweepTo = null
            }
            return ref
        }

        val to = sweepTo ?: return ref
        return when (mode) {
            SweepMode.PER_FRAME -> {
                val theta = angle(ref, to)
                if (theta <= TempestConstants.SWEEP_DONE_RAD) {
                    sweepTo = null
                    to.copyOf()
                } else {
                    val step = min(theta, theta / sweepDurationS(theta) * dt)
                    slerp(ref, to, step / theta)
                }
            }
            SweepMode.FIXED_AT_START -> {
                sweepElapsedS += dt
                val t = min(1f, sweepElapsedS / sweepS)
                if (t >= 1f) sweepTo = null
                slerp(sweepFrom!!, to, t)
            }
        }
    }

    companion object {
        private const val NO_TIME = Long.MIN_VALUE

        /** Sweep duration S (s) for an offset of [thetaRad], from the table in [TempestConstants]. */
        fun sweepDurationS(thetaRad: Float): Float {
            val xs = TempestConstants.SWEEP_TABLE_RAD
            val ys = TempestConstants.SWEEP_TABLE_S
            if (!(thetaRad >= xs[0] && thetaRad <= xs[xs.size - 1])) {
                return max(TempestConstants.SWEEP_OUT_OF_RANGE_S, TempestConstants.MIN_SWEEP_S)
            }
            var i = 1
            while (i < xs.size - 1 && thetaRad > xs[i]) i++
            val f = (thetaRad - xs[i - 1]) / (xs[i] - xs[i - 1])
            return max(ys[i - 1] + f * (ys[i] - ys[i - 1]), TempestConstants.MIN_SWEEP_S)
        }

        /** Rotation angle between two unit quaternions, rad, in [0, pi] (atan2 form: exact near 0). */
        fun angle(a: FloatArray, b: FloatArray): Float {
            // conj(a) * b
            val w = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
            val x = a[0] * b[1] - a[1] * b[0] - a[2] * b[3] + a[3] * b[2]
            val y = a[0] * b[2] + a[1] * b[3] - a[2] * b[0] - a[3] * b[1]
            val z = a[0] * b[3] - a[1] * b[2] + a[2] * b[1] - a[3] * b[0]
            return 2f * kotlin.math.atan2(sqrt(x * x + y * y + z * z), kotlin.math.abs(w))
        }

        /** Next value of the HID head tracker discontinuity counter (uint8, wraps). */
        fun nextDiscontinuity(count: Int): Int = (count + 1) and 0xFF

        /** |gyro| in rad/s from the AirPods int16 gyro triple. */
        fun gyroRateRadS(gx: Int, gy: Int, gz: Int): Float {
            val x = gx.toFloat(); val y = gy.toFloat(); val z = gz.toFloat()
            return sqrt(x * x + y * y + z * z) / TempestConstants.GYRO_LSB_PER_RAD_S
        }

        /** Shortest-path spherical interpolation between unit quaternions (w, x, y, z). */
        fun slerp(a: FloatArray, b: FloatArray, t: Float): FloatArray {
            var d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
            val sg = if (d < 0f) -1f else 1f
            d *= sg
            val wa: Float
            val wb: Float
            if (d > 0.9995f) {
                wa = 1f - t
                wb = t * sg
            } else {
                val theta = acos(d.coerceAtMost(1f))
                val s = sin(theta)
                wa = sin((1f - t) * theta) / s
                wb = sin(t * theta) / s * sg
            }
            val r = FloatArray(4) { wa * a[it] + wb * b[it] }
            val n = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2] + r[3] * r[3])
            for (i in 0..3) r[i] /= n
            return r
        }
    }
}
