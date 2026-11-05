/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.presentation.screens.apple

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Returned by a pose source when it has nothing to show. */
const val NO_HEAD_POSE = Long.MIN_VALUE

/**
 * Perspective wireframe head rotated by a head-frame rotation vector (x right, y forward, z up, radians,
 * axis-angle relative to the neutral pose). [pose] fills `out[0..2]` and returns the timeline position in
 * ms (used for the nose trail), or [NO_HEAD_POSE]. It is polled once per display frame on the main thread.
 */
@Composable
fun HeadOrientationView(
    replaying: Boolean,
    placeholder: String?,
    pose: (FloatArray) -> Long,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { HeadRenderer() }
    val currentPose by rememberUpdatedState(pose)
    var frame by remember { mutableLongStateOf(0L) }
    var yaw by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    var roll by remember { mutableFloatStateOf(0f) }
    var hasPose by remember { mutableStateOf(false) }

    LaunchedEffect(renderer) {
        var lastNanos = 0L
        var lastText = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 0f else (now - lastNanos) / 1e9f
                lastNanos = now
                val t = currentPose(renderer.target)
                if (t != NO_HEAD_POSE) renderer.update(t, dt)
                if (now - lastText >= 100_000_000L) {
                    lastText = now
                    hasPose = t != NO_HEAD_POSE
                    yaw = Math.toDegrees(renderer.yaw.toDouble()).toFloat()
                    pitch = Math.toDegrees(renderer.pitch.toDouble()).toFloat()
                    roll = Math.toDegrees(renderer.roll.toDouble()).toFloat()
                }
                frame = now
            }
        }
    }

    val colors = MaterialTheme.colorScheme
    val palette = HeadPalette(
        line = colors.onSurface,
        nose = colors.primary,
        ear = colors.tertiary,
        trail = colors.secondary,
        x = Color(0xFFE53935),
        y = Color(0xFF43A047),
        z = Color(0xFF1E88E5),
    )
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val axisLabels = remember(measurer, labelStyle) {
        arrayOf(measurer.measure("x", labelStyle), measurer.measure("y", labelStyle), measurer.measure("z", labelStyle))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceContainerHigh)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Reading the tick here invalidates only the draw phase each frame, not composition.
            if (frame == Long.MIN_VALUE) return@Canvas
            renderer.draw(this, palette, axisLabels)
        }

        Text(
            text = stringResource(
                if (replaying) R.string.head_tracking_visualizer_replay else R.string.head_tracking_visualizer_live
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (replaying) colors.onPrimary else colors.onError,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (replaying) colors.primary else colors.error)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            val numberStyle = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.head_tracking_visualizer_yaw, yaw), style = numberStyle, color = palette.z)
            Text(stringResource(R.string.head_tracking_visualizer_pitch, pitch), style = numberStyle, color = palette.x)
            Text(stringResource(R.string.head_tracking_visualizer_roll, roll), style = numberStyle, color = palette.y)
        }

        Text(
            text = stringResource(R.string.head_tracking_visualizer_convention),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
        )

        if (placeholder != null || !hasPose) {
            Text(
                text = placeholder ?: stringResource(R.string.head_tracking_visualizer_waiting),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
        }
    }
}

private class HeadPalette(
    val line: Color,
    val nose: Color,
    val ear: Color,
    val trail: Color,
    val x: Color,
    val y: Color,
    val z: Color,
)

/** Holds every buffer the view needs so a frame allocates nothing. */
private class HeadRenderer {
    val target = FloatArray(3)

    private val q = floatArrayOf(1f, 0f, 0f, 0f)
    private val tq = FloatArray(4)
    private val r = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    var yaw = 0f; private set
    var pitch = 0f; private set
    var roll = 0f; private set
    private var initialized = false

    private val trailX = FloatArray(TRAIL_CAPACITY)
    private val trailY = FloatArray(TRAIL_CAPACITY)
    private val trailZ = FloatArray(TRAIL_CAPACITY)
    private val trailT = LongArray(TRAIL_CAPACITY)
    private var trailStart = 0
    private var trailCount = 0
    private var lastTimeline = NO_HEAD_POSE

    private val modelCount = MESH.size / 3
    private val sx = FloatArray(modelCount)
    private val sy = FloatArray(modelCount)
    private val segFront = BooleanArray(SEGMENTS.size / 2)
    private val path = Path()
    private val tmp = FloatArray(3)

    private val eye = floatArrayOf(-2.6f, -3.6f, 2.0f)
    private val camF = FloatArray(3)
    private val camR = FloatArray(3)
    private val camU = FloatArray(3)

    init {
        val tx = 0f - eye[0]; val ty = 0f - eye[1]; val tz = -0.25f - eye[2]
        val fl = sqrt(tx * tx + ty * ty + tz * tz)
        camF[0] = tx / fl; camF[1] = ty / fl; camF[2] = tz / fl
        // right = forward x worldUp(0,0,1)
        var rx = camF[1]; var ry = -camF[0]
        val rl = sqrt(rx * rx + ry * ry)
        rx /= rl; ry /= rl
        camR[0] = rx; camR[1] = ry; camR[2] = 0f
        camU[0] = camR[1] * camF[2] - camR[2] * camF[1]
        camU[1] = camR[2] * camF[0] - camR[0] * camF[2]
        camU[2] = camR[0] * camF[1] - camR[1] * camF[0]
    }

    fun update(timelineMs: Long, dt: Float) {
        axisAngleToQuaternion(target[0], target[1], target[2], tq)
        if (!initialized) {
            tq.copyInto(q)
            initialized = true
        } else {
            var dot = q[0] * tq[0] + q[1] * tq[1] + q[2] * tq[2] + q[3] * tq[3]
            val sign = if (dot < 0f) -1f else 1f
            // ~40 ms time constant: hides the 25 Hz stepping without visibly lagging the motion.
            val a = 1f - exp(-dt / 0.04f)
            for (i in 0..3) q[i] += a * (sign * tq[i] - q[i])
            dot = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            for (i in 0..3) q[i] /= dot
        }
        quaternionToMatrix(q, r)
        // ZYX with z up: R = Rz(yaw) * Ry(roll) * Rx(pitch); y is the forward (roll) axis, x the ear (pitch) axis.
        yaw = atan2(r[3], r[0])
        roll = asin((-r[6]).coerceIn(-1f, 1f))
        pitch = atan2(r[7], r[8])

        if (lastTimeline != NO_HEAD_POSE && (timelineMs < lastTimeline || timelineMs - lastTimeline > 1000L)) {
            trailCount = 0
        }
        val newest = if (trailCount == 0) NO_HEAD_POSE else trailT[(trailStart + trailCount - 1) % TRAIL_CAPACITY]
        if (newest == NO_HEAD_POSE || timelineMs - newest >= TRAIL_STEP_MS) {
            rotate(NOSE_TIP[0], NOSE_TIP[1], NOSE_TIP[2], tmp)
            val idx: Int
            if (trailCount == TRAIL_CAPACITY) {
                idx = trailStart
                trailStart = (trailStart + 1) % TRAIL_CAPACITY
            } else {
                idx = (trailStart + trailCount) % TRAIL_CAPACITY
                trailCount++
            }
            trailX[idx] = tmp[0]; trailY[idx] = tmp[1]; trailZ[idx] = tmp[2]; trailT[idx] = timelineMs
        }
        while (trailCount > 0 && timelineMs - trailT[trailStart] > TRAIL_MS) {
            trailStart = (trailStart + 1) % TRAIL_CAPACITY
            trailCount--
        }
        lastTimeline = timelineMs
    }

    private fun rotate(x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = r[0] * x + r[1] * y + r[2] * z
        out[1] = r[3] * x + r[4] * y + r[5] * z
        out[2] = r[6] * x + r[7] * y + r[8] * z
    }

    private var focal = 1f
    private var cx = 0f
    private var cy = 0f

    /** Projects a world point; returns false if it is behind the camera. */
    private fun project(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        val dx = x - eye[0]; val dy = y - eye[1]; val dz = z - eye[2]
        val zc = dx * camF[0] + dy * camF[1] + dz * camF[2]
        if (zc <= 0.05f) return false
        val xc = dx * camR[0] + dy * camR[1] + dz * camR[2]
        val yc = dx * camU[0] + dy * camU[1] + dz * camU[2]
        out[0] = cx + focal * xc / zc
        out[1] = cy - focal * yc / zc
        out[2] = zc
        return true
    }

    /** True if a surface at world point p with world normal n faces the camera. */
    private fun faces(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float): Boolean =
        nx * (eye[0] - px) + ny * (eye[1] - py) + nz * (eye[2] - pz) > 0f

    private fun projectRotated(x: Float, y: Float, z: Float): Offset {
        rotate(x, y, z, tmp)
        project(tmp[0], tmp[1], tmp[2], tmp)
        return Offset(tmp[0], tmp[1])
    }

    fun draw(scope: DrawScope, p: HeadPalette, labels: Array<TextLayoutResult>) = with(scope) {
        val minSide = minOf(size.width, size.height)
        focal = minSide * 1.15f
        cx = size.width / 2f
        cy = size.height / 2f + minSide * 0.02f
        val thin = 1.dp.toPx()
        val medium = 2.dp.toPx()

        drawGround(this, p, thin)

        for (i in 0 until modelCount) {
            rotate(MESH[i * 3], MESH[i * 3 + 1], MESH[i * 3 + 2], tmp)
            project(tmp[0], tmp[1], tmp[2], tmp)
            sx[i] = tmp[0]; sy[i] = tmp[1]
        }
        for (s in segFront.indices) {
            val a = SEGMENTS[s * 2]; val b = SEGMENTS[s * 2 + 1]
            val mx = (MESH[a * 3] + MESH[b * 3]) * 0.5f
            val my = (MESH[a * 3 + 1] + MESH[b * 3 + 1]) * 0.5f
            val mz = (MESH[a * 3 + 2] + MESH[b * 3 + 2]) * 0.5f
            rotate(mx / (RX * RX), my / (RY * RY), mz / (RZ * RZ), tmp)
            val nx = tmp[0]; val ny = tmp[1]; val nz = tmp[2]
            rotate(mx, my, mz, tmp)
            segFront[s] = faces(tmp[0], tmp[1], tmp[2], nx, ny, nz)
        }

        drawMesh(this, p.line.copy(alpha = 0.14f), thin, front = false)
        drawEars(this, p, thin, front = false)
        drawAxes(this, p, medium, labels)
        drawMesh(this, p.line.copy(alpha = 0.5f), thin, front = true)
        drawEars(this, p, thin, front = true)
        drawFace(this, p)
        drawTrail(this, p, medium)
    }

    private fun drawGround(scope: DrawScope, p: HeadPalette, width: Float) = with(scope) {
        val color = p.line.copy(alpha = 0.18f)
        var px = 0f; var py = 0f
        for (i in 0..GROUND_STEPS) {
            val a = (i * 2.0 * Math.PI / GROUND_STEPS).toFloat()
            project(GROUND_R * cos(a), GROUND_R * sin(a), GROUND_Z, tmp)
            if (i > 0) drawLine(color, Offset(px, py), Offset(tmp[0], tmp[1]), width)
            px = tmp[0]; py = tmp[1]
        }
        project(0f, GROUND_R * 0.82f, GROUND_Z, tmp)
        val ax = tmp[0]; val ay = tmp[1]
        project(0f, GROUND_R * 1.12f, GROUND_Z, tmp)
        drawLine(p.y.copy(alpha = 0.45f), Offset(ax, ay), Offset(tmp[0], tmp[1]), width * 2f, StrokeCap.Round)
    }

    private fun drawMesh(scope: DrawScope, color: Color, width: Float, front: Boolean) = with(scope) {
        for (s in segFront.indices) {
            if (segFront[s] != front) continue
            val a = SEGMENTS[s * 2]; val b = SEGMENTS[s * 2 + 1]
            drawLine(color, Offset(sx[a], sy[a]), Offset(sx[b], sy[b]), width)
        }
    }

    private fun drawAxes(scope: DrawScope, p: HeadPalette, width: Float, labels: Array<TextLayoutResult>) = with(scope) {
        val origin = projectRotated(0f, 0f, 0f)
        for (axis in 0..2) {
            val color = when (axis) { 0 -> p.x; 1 -> p.y; else -> p.z }
            val end = projectRotated(
                if (axis == 0) AXIS_LEN else 0f,
                if (axis == 1) AXIS_LEN else 0f,
                if (axis == 2) AXIS_LEN else 0f
            )
            drawLine(color, origin, end, width, StrokeCap.Round)
            val label = labels[axis]
            drawText(
                label,
                color = color,
                topLeft = Offset(end.x - label.size.width / 2f, end.y - label.size.height / 2f) +
                    (end - origin).let { d ->
                        val l = d.getDistance().coerceAtLeast(1f)
                        Offset(d.x / l * label.size.height * 0.7f, d.y / l * label.size.height * 0.7f)
                    }
            )
        }
    }

    private fun drawEars(scope: DrawScope, p: HeadPalette, width: Float, front: Boolean) = with(scope) {
        for (side in 0..1) {
            val sgn = if (side == 0) 1f else -1f
            rotate(sgn * EAR_X, 0f, 0f, tmp)
            val wx = tmp[0]; val wy = tmp[1]; val wz = tmp[2]
            rotate(sgn, 0f, 0f, tmp)
            if (faces(wx, wy, wz, tmp[0], tmp[1], tmp[2]) != front) continue
            project(wx, wy, wz, tmp)
            val radius = focal * EAR_R / tmp[2]
            val c = Offset(tmp[0], tmp[1])
            if (front) {
                drawCircle(p.ear, radius, c)
                if (side == 0) drawCircle(p.x, radius, c, style = Stroke(width * 2f))
            } else {
                drawCircle(p.ear.copy(alpha = 0.3f), radius, c, style = Stroke(width))
            }
        }
    }

    private fun drawFace(scope: DrawScope, p: HeadPalette) = with(scope) {
        for (side in 0..1) {
            val ex = if (side == 0) EYE[0] else -EYE[0]
            rotate(ex / (RX * RX), EYE[1] / (RY * RY), EYE[2] / (RZ * RZ), tmp)
            val nx = tmp[0]; val ny = tmp[1]; val nz = tmp[2]
            rotate(ex, EYE[1], EYE[2], tmp)
            if (!faces(tmp[0], tmp[1], tmp[2], nx, ny, nz)) continue
            project(tmp[0], tmp[1], tmp[2], tmp)
            drawCircle(p.line.copy(alpha = 0.8f), focal * 0.055f / tmp[2], Offset(tmp[0], tmp[1]))
        }

        rotate(NOSE_TIP[0], NOSE_TIP[1], NOSE_TIP[2], tmp)
        val tx = tmp[0]; val ty = tmp[1]; val tz = tmp[2]
        for (f in 0..3) {
            val a = f * 3; val b = ((f + 1) % 4) * 3
            rotate(NOSE_BASE[a], NOSE_BASE[a + 1], NOSE_BASE[a + 2], tmp)
            val ax = tmp[0]; val ay = tmp[1]; val az = tmp[2]
            rotate(NOSE_BASE[b], NOSE_BASE[b + 1], NOSE_BASE[b + 2], tmp)
            val bx = tmp[0]; val by = tmp[1]; val bz = tmp[2]
            // Outward normal (a - tip) x (b - tip), given the base corner order in NOSE_BASE.
            val ux = ax - tx; val uy = ay - ty; val uz = az - tz
            val vx = bx - tx; val vy = by - ty; val vz = bz - tz
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            if (!faces(tx, ty, tz, nx, ny, nz)) continue
            val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            val light = 0.55f + 0.45f * ((-nx * camF[0] - ny * camF[1] - nz * camF[2]) / nl).coerceIn(0f, 1f)
            path.reset()
            project(tx, ty, tz, tmp); path.moveTo(tmp[0], tmp[1])
            project(ax, ay, az, tmp); path.lineTo(tmp[0], tmp[1])
            project(bx, by, bz, tmp); path.lineTo(tmp[0], tmp[1])
            path.close()
            drawPath(path, p.nose.copy(alpha = light))
        }
    }

    private fun drawTrail(scope: DrawScope, p: HeadPalette, width: Float) = with(scope) {
        if (trailCount < 2) return@with
        val newest = trailT[(trailStart + trailCount - 1) % TRAIL_CAPACITY]
        var px = 0f; var py = 0f
        for (k in 0 until trailCount) {
            val i = (trailStart + k) % TRAIL_CAPACITY
            project(trailX[i], trailY[i], trailZ[i], tmp)
            if (k > 0) {
                val age = (newest - trailT[i]).toFloat() / TRAIL_MS
                drawLine(
                    p.trail.copy(alpha = (1f - age).coerceIn(0f, 1f) * 0.9f),
                    Offset(px, py), Offset(tmp[0], tmp[1]), width, StrokeCap.Round
                )
            }
            px = tmp[0]; py = tmp[1]
        }
    }

    companion object {
        const val RX = 0.72f
        const val RY = 0.88f
        const val RZ = 1.0f
        const val EAR_X = 0.76f
        const val EAR_R = 0.13f
        const val AXIS_LEN = 1.55f
        const val GROUND_R = 1.7f
        const val GROUND_Z = -1.3f
        const val GROUND_STEPS = 64
        const val TRAIL_MS = 3000L
        const val TRAIL_STEP_MS = 20L
        const val TRAIL_CAPACITY = 192

        val NOSE_TIP = floatArrayOf(0f, 1.28f, -0.05f)
        val NOSE_BASE = floatArrayOf(
            -0.12f, 0.80f, 0.08f,
            0.12f, 0.80f, 0.08f,
            0.12f, 0.80f, -0.2f,
            -0.12f, 0.80f, -0.2f,
        )
        val EYE = floatArrayOf(0.27f, 0.82f, 0.24f)

        private const val LATITUDES = 9
        private const val MERIDIANS = 12
        private const val RING_STEPS = 32
        private const val MERIDIAN_STEPS = 16

        val MESH: FloatArray
        val SEGMENTS: IntArray

        init {
            val pts = ArrayList<Float>()
            val segs = ArrayList<Int>()
            fun add(x: Float, y: Float, z: Float): Int {
                pts += x; pts += y; pts += z
                return pts.size / 3 - 1
            }
            for (l in 1..LATITUDES) {
                val phi = Math.PI * l / (LATITUDES + 1) - Math.PI / 2
                val first = pts.size / 3
                for (k in 0 until RING_STEPS) {
                    val th = 2 * Math.PI * k / RING_STEPS
                    add(
                        (RX * cos(phi) * cos(th)).toFloat(),
                        (RY * cos(phi) * sin(th)).toFloat(),
                        (RZ * sin(phi)).toFloat()
                    )
                    segs += first + k; segs += first + (k + 1) % RING_STEPS
                }
            }
            for (m in 0 until MERIDIANS) {
                val th = 2 * Math.PI * m / MERIDIANS
                var prev = -1
                for (k in 0..MERIDIAN_STEPS) {
                    val phi = Math.PI * k / MERIDIAN_STEPS - Math.PI / 2
                    val idx = add(
                        (RX * cos(phi) * cos(th)).toFloat(),
                        (RY * cos(phi) * sin(th)).toFloat(),
                        (RZ * sin(phi)).toFloat()
                    )
                    if (prev >= 0) { segs += prev; segs += idx }
                    prev = idx
                }
            }
            MESH = pts.toFloatArray()
            SEGMENTS = segs.toIntArray()
        }
    }
}

/** Unit quaternion (w, x, y, z) for a rotation vector: angle = |v|, axis = v / |v|. */
private fun axisAngleToQuaternion(x: Float, y: Float, z: Float, out: FloatArray) {
    val angle = sqrt(x * x + y * y + z * z)
    if (angle < 1e-6f) {
        out[0] = 1f; out[1] = x * 0.5f; out[2] = y * 0.5f; out[3] = z * 0.5f
        val n = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3])
        for (i in 0..3) out[i] /= n
        return
    }
    val s = sin(angle / 2f) / angle
    out[0] = cos(angle / 2f); out[1] = x * s; out[2] = y * s; out[3] = z * s
}

/** Rodrigues' rotation formula in Euler-Rodrigues (quaternion) form; row-major, maps head frame to reference. */
private fun quaternionToMatrix(q: FloatArray, m: FloatArray) {
    val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
    m[0] = 1 - 2 * (y * y + z * z); m[1] = 2 * (x * y - w * z); m[2] = 2 * (x * z + w * y)
    m[3] = 2 * (x * y + w * z); m[4] = 1 - 2 * (x * x + z * z); m[5] = 2 * (y * z - w * x)
    m[6] = 2 * (x * z - w * y); m[7] = 2 * (y * z + w * x); m[8] = 1 - 2 * (x * x + y * y)
}
