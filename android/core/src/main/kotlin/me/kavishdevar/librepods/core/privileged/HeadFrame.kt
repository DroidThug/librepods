// LibrePods - AirPods liberated from Apple's ecosystem
// Copyright (C) 2025 LibrePods contributors
// GPL-3.0-or-later

package me.kavishdevar.librepods.core.privileged

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Head frame built from the AirPods attitude quaternion's own gravity-aligned world frame.
 *
 * Convention (checked on phone logs, see HEADTRACKING_FORK_NOTES.md): q = (w, x, y, z) rotates
 * sensor vectors into a world frame whose +z is up (R(q)^T * (0, 0, -1) matches the accelerometer
 * vector, which the AirPods report pointing down, median 1.2-3.6 deg).
 *
 * The relative rotation from the neutral pose is taken in the world frame (q * conj(ref)), so a
 * turn about the vertical is exactly the head z (yaw) component. The neutral head frame is:
 * z = world up, x = horizontal projection of the ear axis at the neutral pose (only used to split
 * pitch from roll), y = z cross x. Android head frame: x right, y forward, z up; +z = turn left,
 * nod down = negative x.
 */
object HeadFrame {
    /** Default for the A/B preference `spatial_head_tracking_legacy_frame` (false = this frame). */
    const val LEGACY_FRAME_DEFAULT = false

    /** Below this horizontal length the ear axis is too close to vertical to define x. */
    const val MIN_EAR_HORIZONTAL = 0.2f

    /**
     * The ear axis for the mirrored (other) bud: left and right sensors are presumably mirror
     * images, so the measured ear axis points the wrong way there. Flipping it inverts pitch and
     * roll and leaves yaw unchanged (z stays world up, y = z cross x flips with x).
     */
    fun mirrored(earAxisSensor: FloatArray): FloatArray =
        floatArrayOf(-earAxisSensor[0], -earAxisSensor[1], -earAxisSensor[2])

    /** R(q) * v. */
    fun rotate(q: FloatArray, v: FloatArray): FloatArray {
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        return floatArrayOf(
            (1f - 2f * (y * y + z * z)) * v[0] + 2f * (x * y - w * z) * v[1] + 2f * (x * z + w * y) * v[2],
            2f * (x * y + w * z) * v[0] + (1f - 2f * (x * x + z * z)) * v[1] + 2f * (y * z - w * x) * v[2],
            2f * (x * z - w * y) * v[0] + 2f * (y * z + w * x) * v[1] + (1f - 2f * (x * x + y * y)) * v[2]
        )
    }

    /**
     * Neutral head frame axes (x, y, z) in world coordinates, or null if the ear axis is nearly
     * vertical at the neutral pose (caller falls back to the legacy frame).
     * @param earAxisSensor unit ear axis (head +x, to the right) in the sensor frame
     */
    fun basis(ref: FloatArray, earAxisSensor: FloatArray): Array<FloatArray>? {
        val e = rotate(ref, earAxisSensor)
        val n = sqrt(e[0] * e[0] + e[1] * e[1])
        if (n < MIN_EAR_HORIZONTAL) return null
        val x = floatArrayOf(e[0] / n, e[1] / n, 0f)
        val z = floatArrayOf(0f, 0f, 1f)
        val y = floatArrayOf(-x[1], x[0], 0f) // z cross x
        return arrayOf(x, y, z)
    }

    /** Rotation vector (rad, world frame) of q * conj(ref), shortest path. */
    fun relRotVecWorld(ref: FloatArray, q: FloatArray): FloatArray {
        // q * conj(ref)
        var w = q[0] * ref[0] + q[1] * ref[1] + q[2] * ref[2] + q[3] * ref[3]
        var x = -q[0] * ref[1] + q[1] * ref[0] - q[2] * ref[3] + q[3] * ref[2]
        var y = -q[0] * ref[2] + q[1] * ref[3] + q[2] * ref[0] - q[3] * ref[1]
        var z = -q[0] * ref[3] - q[1] * ref[2] + q[2] * ref[1] + q[3] * ref[0]
        if (w < 0f) { w = -w; x = -x; y = -y; z = -z }
        val s = sqrt(x * x + y * y + z * z)
        val k = if (s > 1e-6f) 2f * atan2(s, w) / s else 2f
        return floatArrayOf(x * k, y * k, z * k)
    }

    /** Components of world vector [v] along the head [basis]. */
    fun project(v: FloatArray, basis: Array<FloatArray>): FloatArray = FloatArray(3) {
        v[0] * basis[it][0] + v[1] * basis[it][1] + v[2] * basis[it][2]
    }

    /** Head rotation vector (rad) for attitude [q] against neutral [ref], or null if degenerate. */
    fun headVector(ref: FloatArray, q: FloatArray, earAxisSensor: FloatArray): FloatArray? {
        val b = basis(ref, earAxisSensor) ?: return null
        return project(relRotVecWorld(ref, q), b)
    }

    /** Sensor-frame angular rate [gyroSensor] in the head frame, via the current attitude [q]. */
    fun headRate(q: FloatArray, gyroSensor: FloatArray, basis: Array<FloatArray>): FloatArray =
        project(rotate(q, gyroSensor), basis)
}
