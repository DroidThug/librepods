/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.presentation.screens.apple

import android.content.Context
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.privileged.HeadPoseTelemetry
import me.kavishdevar.librepods.presentation.components.primitives.MaterialButtonStyle
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

private const val TAG = "HeadPoseReplay"
private val REPLAY_SPEEDS = floatArrayOf(0.25f, 0.5f, 1f, 2f)
private val REPLAY_SPEED_LABELS = arrayOf("0.25", "0.5", "1", "2")

/** Head-frame rotation vectors from a CSV log; only the first [size] entries of each array are valid. */
class HeadPoseRecording(
    private val timeMs: LongArray,
    private val x: FloatArray,
    private val y: FloatArray,
    private val z: FloatArray,
    val size: Int,
    val skippedLines: Int,
) {
    val durationMs: Long = if (size == 0) 0L else timeMs[size - 1] - timeMs[0]

    /** Fills [out] with the pose at [positionMs] from the start, interpolated between neighbouring samples. */
    fun sampleAt(positionMs: Float, out: FloatArray) {
        val t = timeMs[0] + positionMs.toDouble()
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (timeMs[mid] <= t) lo = mid else hi = mid - 1
        }
        val next = (lo + 1).coerceAtMost(size - 1)
        val span = (timeMs[next] - timeMs[lo]).toDouble()
        var f = if (span <= 0.0) 0f else ((t - timeMs[lo]) / span).toFloat().coerceIn(0f, 1f)
        val dx = x[next] - x[lo]; val dy = y[next] - y[lo]; val dz = z[next] - z[lo]
        // Rotation vectors flip near |v| = pi, where a straight lerp would swing through an unrelated pose.
        if (dx * dx + dy * dy + dz * dz > 1f) f = if (f < 0.5f) 0f else 1f
        out[0] = x[lo] + f * dx
        out[1] = y[lo] + f * dy
        out[2] = z[lo] + f * dz
    }
}

fun headTrackingLogDir(context: Context): File = File(context.getExternalFilesDir(null), "headtracking_logs")

fun listHeadTrackingLogs(context: Context): List<File> =
    headTrackingLogDir(context)
        .listFiles { f -> f.isFile && f.name.endsWith(".csv") }
        ?.sortedByDescending { it.name }
        ?: emptyList()

/**
 * Streams a [HeadTrackingCsvLogger] file, keeping only elapsed_ms and head_x/y/z_rad. Lines with the wrong field
 * count, unparseable or non-finite values, or time going backwards are skipped, so a file that is still being
 * written (truncated last line) loads fine.
 */
fun loadHeadPoseCsv(file: File): HeadPoseRecording {
    var capacity = (file.length() / 150L).toInt().coerceIn(1024, 1 shl 22)
    var t = LongArray(capacity)
    var x = FloatArray(capacity)
    var y = FloatArray(capacity)
    var z = FloatArray(capacity)
    var n = 0
    var skipped = 0

    var colT = 0
    var colX = 9
    var colY = 10
    var colZ = 11
    var expected = 15
    var commas = IntArray(expected)

    file.bufferedReader(bufferSize = 1 shl 16).use { reader ->
        var first = true
        while (true) {
            val line = reader.readLine() ?: break
            if (first) {
                first = false
                if (line.startsWith("elapsed_ms")) {
                    val names = line.trim().split(',')
                    colT = names.indexOf("elapsed_ms")
                    colX = names.indexOf("head_x_rad")
                    colY = names.indexOf("head_y_rad")
                    colZ = names.indexOf("head_z_rad")
                    if (colX < 0 || colY < 0 || colZ < 0) throw IOException("missing head_*_rad columns")
                    expected = names.size
                    commas = IntArray(expected)
                    continue
                }
            }
            if (line.isBlank()) continue

            var k = 0
            for (i in line.indices) {
                if (line[i] == ',') {
                    if (k < commas.size) commas[k] = i
                    k++
                }
            }
            if (k + 1 != expected) {
                skipped++
                continue
            }
            val tv = field(line, commas, k, colT).toLongOrNull()
            val xv = field(line, commas, k, colX).toFloatOrNull()
            val yv = field(line, commas, k, colY).toFloatOrNull()
            val zv = field(line, commas, k, colZ).toFloatOrNull()
            if (tv == null || xv == null || yv == null || zv == null ||
                !xv.isFinite() || !yv.isFinite() || !zv.isFinite() ||
                (n > 0 && tv < t[n - 1])
            ) {
                skipped++
                continue
            }
            if (n == capacity) {
                capacity *= 2
                t = t.copyOf(capacity)
                x = x.copyOf(capacity)
                y = y.copyOf(capacity)
                z = z.copyOf(capacity)
            }
            t[n] = tv; x[n] = xv; y[n] = yv; z[n] = zv
            n++
        }
    }
    return HeadPoseRecording(t, x, y, z, n, skipped)
}

private fun field(line: String, commas: IntArray, commaCount: Int, index: Int): String {
    val start = if (index == 0) 0 else commas[index - 1] + 1
    val end = if (index == commaCount) line.length else commas[index]
    return line.substring(start, end).trim()
}

@Stable
class HeadPoseReplayPlayer {
    var recording by mutableStateOf<HeadPoseRecording?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var speed by mutableFloatStateOf(1f)

    /** Position for UI controls; updated at most ~20 times a second while playing. */
    var displayPositionMs by mutableFloatStateOf(0f)
        private set

    private var positionMs = 0f

    fun load(rec: HeadPoseRecording) {
        recording = rec
        positionMs = 0f
        displayPositionMs = 0f
        playing = rec.size > 0
    }

    fun togglePlay() {
        val rec = recording ?: return
        if (!playing && positionMs >= rec.durationMs) seek(0f)
        playing = !playing
    }

    fun restart() {
        if (recording == null) return
        seek(0f)
        playing = true
    }

    fun seek(ms: Float) {
        val rec = recording ?: return
        positionMs = ms.coerceIn(0f, rec.durationMs.toFloat())
        displayPositionMs = positionMs
    }

    suspend fun run() {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val rec = recording
                if (playing && rec != null && last != 0L) {
                    positionMs += (now - last) / 1e6f * speed
                    val end = rec.durationMs.toFloat()
                    if (positionMs >= end) {
                        positionMs = end
                        playing = false
                    }
                    if (!playing || abs(positionMs - displayPositionMs) >= 50f) displayPositionMs = positionMs
                }
                last = now
            }
        }
    }

    fun sample(out: FloatArray): Long {
        val rec = recording ?: return NO_HEAD_POSE
        if (rec.size == 0) return NO_HEAD_POSE
        rec.sampleAt(positionMs, out)
        return positionMs.toLong()
    }
}

private fun livePose(out: FloatArray): Long {
    val s = HeadPoseTelemetry.latest.value ?: return NO_HEAD_POSE
    out[0] = s.headX
    out[1] = s.headY
    out[2] = s.headZ
    return s.elapsedNanos / 1_000_000L
}

private fun formatReplayTime(ms: Float): String {
    val totalTenths = (ms / 100f).toInt()
    return String.format(Locale.US, "%d:%02d.%d", totalTenths / 600, (totalTenths / 10) % 60, totalTenths % 10)
}

/** 3D head view plus live/replay switching and replay controls, for the top of the diagnostics screen. */
@Composable
fun HeadVisualizerSection(logFile: File?, logging: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { HeadPoseReplayPlayer() }
    var replayMode by rememberSaveable { mutableStateOf(false) }
    var loadedFile by remember { mutableStateOf<File?>(null) }
    var loadingFile by remember { mutableStateOf<File?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(player) { player.run() }

    val logs by produceState(emptyList<File>(), replayMode, logFile, logging) {
        value = if (replayMode) withContext(Dispatchers.IO) { listHeadTrackingLogs(context) } else emptyList()
    }

    val loadFailed = stringResource(R.string.head_tracking_replay_load_failed)
    fun load(file: File) {
        loadJob?.cancel()
        loadingFile = file
        loadJob = scope.launch {
            val rec = try {
                withContext(Dispatchers.IO) { loadHeadPoseCsv(file) }
            } catch (e: IOException) {
                Log.w(TAG, "could not read $file", e)
                null
            }
            loadingFile = null
            if (rec == null || rec.size == 0) {
                Toast.makeText(context, String.format(loadFailed, file.name), Toast.LENGTH_SHORT).show()
            } else {
                loadedFile = file
                player.load(rec)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val rec = player.recording
        HeadOrientationView(
            replaying = replayMode,
            placeholder = when {
                !replayMode -> null
                loadingFile != null -> stringResource(R.string.head_tracking_replay_loading)
                rec == null -> stringResource(R.string.head_tracking_replay_pick_log)
                else -> null
            },
            pose = if (replayMode) player::sample else ::livePose,
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(false, true).forEach { replay ->
                StyledButton(
                    onClick = {
                        replayMode = replay
                        if (!replay && player.playing) player.togglePlay()
                    },
                    modifier = Modifier.weight(1f),
                    maxScale = 0.05f,
                    materialButtonStyle = if (replayMode == replay) MaterialButtonStyle.Tonal else MaterialButtonStyle.Outlined
                ) {
                    Text(
                        stringResource(if (replay) R.string.head_tracking_mode_replay else R.string.head_tracking_mode_live),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        if (replayMode) {
            if (rec != null) {
                loadedFile?.let { f ->
                    Text(
                        stringResource(R.string.head_tracking_replay_loaded, f.name, rec.size, rec.skippedLines),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StyledButton(
                        onClick = player::togglePlay,
                        modifier = Modifier.weight(1f),
                        maxScale = 0.05f
                    ) {
                        Text(
                            stringResource(if (player.playing) R.string.head_tracking_replay_pause else R.string.head_tracking_replay_play),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    StyledButton(
                        onClick = player::restart,
                        modifier = Modifier.weight(1f),
                        maxScale = 0.05f,
                        materialButtonStyle = MaterialButtonStyle.Outlined
                    ) {
                        Text(
                            stringResource(R.string.head_tracking_replay_restart),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    REPLAY_SPEEDS.forEachIndexed { i, speed ->
                        StyledButton(
                            onClick = { player.speed = speed },
                            modifier = Modifier.weight(1f),
                            maxScale = 0.05f,
                            materialButtonStyle =
                                if (player.speed == speed) MaterialButtonStyle.Tonal else MaterialButtonStyle.Outlined
                        ) {
                            Text(
                                stringResource(R.string.head_tracking_replay_speed, REPLAY_SPEED_LABELS[i]),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                StyledSlider(
                    label = stringResource(R.string.head_tracking_replay_position),
                    value = player.displayPositionMs,
                    onValueChange = player::seek,
                    valueRange = 0f..rec.durationMs.coerceAtLeast(1L).toFloat(),
                    description = stringResource(
                        R.string.head_tracking_replay_time,
                        formatReplayTime(player.displayPositionMs),
                        formatReplayTime(rec.durationMs.toFloat())
                    ),
                    independent = true
                )
            }

            StyledList(title = stringResource(R.string.head_tracking_replay_logs)) {
                if (logs.isEmpty()) {
                    styledListItem(contentText = stringResource(R.string.head_tracking_replay_no_logs))
                }
                logs.forEach { f ->
                    val recordingNow = logging && f == logFile
                    styledListItem(
                        onClick = { load(f) },
                        contentText = if (recordingNow) stringResource(R.string.head_tracking_replay_recording, f.name) else f.name,
                        supportingText = Formatter.formatShortFileSize(context, f.length()),
                        enabled = loadingFile == null,
                        orientation = StyledListItemOrientation.Vertical,
                        selected = f == loadedFile
                    )
                }
            }
        }
    }
}
