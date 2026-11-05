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
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.privileged.HeadPoseSample
import me.kavishdevar.librepods.core.privileged.HeadPoseTelemetry
import me.kavishdevar.librepods.core.privileged.ShizukuHeadTracker
import me.kavishdevar.librepods.presentation.components.primitives.MaterialButtonStyle
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import me.kavishdevar.librepods.services.SpatialHeadTrackingController
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Composable
fun HeadTrackingDiagnosticsScreen(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?
) {
    val context = LocalContext.current
    val sample by HeadPoseTelemetry.latest.collectAsState()
    val logFile by HeadTrackingCsvLogger.file.collectAsState()
    val logging by HeadTrackingCsvLogger.logging.collectAsState()

    DisposableEffect(Unit) {
        viewModel.startHeadTracking()
        onDispose {
            HeadTrackingCsvLogger.stop()
            // While the spatial toggle is on, the stream belongs to SpatialHeadTrackingController.
            if (!SpatialHeadTrackingController.isEnabled(context)) viewModel.stopHeadTracking()
        }
    }

    var rateHz by remember { mutableFloatStateOf(0f) }
    var bridgeRunning by remember { mutableStateOf(ShizukuHeadTracker.enabled) }
    LaunchedEffect(Unit) {
        var lastSeq = HeadPoseTelemetry.latest.value?.seq ?: 0L
        var lastTime = SystemClock.elapsedRealtime()
        while (true) {
            delay(1.seconds)
            val seq = HeadPoseTelemetry.latest.value?.seq ?: lastSeq
            val now = SystemClock.elapsedRealtime()
            rateHz = (seq - lastSeq) * 1000f / (now - lastTime).coerceAtLeast(1)
            lastSeq = seq
            lastTime = now
            bridgeRunning = ShizukuHeadTracker.enabled
        }
    }

    var driftStart by remember { mutableStateOf<HeadPoseSample?>(null) }
    var driftEnd by remember { mutableStateOf<HeadPoseSample?>(null) }
    var driftRunning by remember { mutableStateOf(false) }

    StyledScaffold(
        title = stringResource(R.string.head_tracking_diagnostics),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            HeadVisualizerSection(logFile = logFile, logging = logging)

            val s = sample
            val notAvailable = "-"
            StyledList {
                styledListItem(
                    contentText = stringResource(R.string.head_tracking_diagnostics_bridge),
                    supportingText = stringResource(
                        if (bridgeRunning) R.string.head_tracking_diagnostics_running
                        else R.string.head_tracking_diagnostics_not_running
                    ),
                    orientation = StyledListItemOrientation.Vertical
                )
                styledListItem(
                    contentText = stringResource(R.string.head_tracking_diagnostics_stream_rate),
                    supportingText = stringResource(R.string.head_tracking_diagnostics_hz, rateHz),
                    orientation = StyledListItemOrientation.Vertical
                )
                styledListItem(
                    contentText = stringResource(R.string.head_tracking_diagnostics_head),
                    supportingText = s?.let {
                        stringResource(
                            R.string.head_tracking_diagnostics_degrees,
                            Math.toDegrees(it.headZ.toDouble()),
                            Math.toDegrees(it.headX.toDouble()),
                            Math.toDegrees(it.headY.toDouble())
                        )
                    } ?: notAvailable,
                    orientation = StyledListItemOrientation.Vertical
                )
                styledListItem(
                    contentText = stringResource(R.string.head_tracking_diagnostics_quaternion),
                    supportingText = s?.let {
                        String.format(Locale.US, "%.4f, %.4f, %.4f, %.4f", it.qw, it.qx, it.qy, it.qz)
                    } ?: notAvailable,
                    orientation = StyledListItemOrientation.Vertical
                )
                styledListItem(
                    contentText = stringResource(R.string.head_tracking_diagnostics_accelerometer),
                    supportingText = s?.let { "${it.ax}, ${it.ay}, ${it.az}" } ?: notAvailable,
                    orientation = StyledListItemOrientation.Vertical
                )
            }

            if (s == null) {
                Text(
                    stringResource(R.string.head_tracking_diagnostics_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            StyledButton(
                onClick = viewModel::recenterHeadPose,
                modifier = Modifier.fillMaxWidth(),
                maxScale = 0.05f
            ) {
                Text(
                    stringResource(R.string.head_tracking_recenter_now),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Text(
                "AirPods stream interval (applies immediately)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(40, 20, 10).forEach { ms ->
                    StyledButton(
                        onClick = { viewModel.setHeadTrackingInterval(ms.milliseconds) },
                        modifier = Modifier.weight(1f),
                        maxScale = 0.05f,
                        materialButtonStyle = MaterialButtonStyle.Outlined
                    ) {
                        Text("$ms ms", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            val logFailed = stringResource(R.string.head_tracking_log_failed)
            StyledButton(
                onClick = {
                    if (logging) {
                        HeadTrackingCsvLogger.stop()
                    } else if (HeadTrackingCsvLogger.start(context) == null) {
                        Toast.makeText(context, logFailed, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                maxScale = 0.05f
            ) {
                Text(
                    stringResource(
                        if (logging) R.string.head_tracking_stop_logging else R.string.head_tracking_start_logging
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            logFile?.let { file ->
                Text(
                    stringResource(
                        if (logging) R.string.head_tracking_logging_to else R.string.head_tracking_last_log,
                        file.name
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                StyledButton(
                    onClick = { shareLog(context, file) },
                    modifier = Modifier.fillMaxWidth(),
                    maxScale = 0.05f,
                    enabled = !logging,
                    materialButtonStyle = MaterialButtonStyle.Outlined
                ) {
                    Text(
                        stringResource(R.string.head_tracking_share_log),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            StyledList(title = stringResource(R.string.head_tracking_drift_test)) {
                val start = driftStart
                val end = if (driftRunning) s else driftEnd
                val elapsedNanos = if (start != null && end != null) end.elapsedNanos - start.elapsedNanos else -1L
                val elapsedS = (elapsedNanos / 1_000_000_000L).toInt()
                styledListItem(
                    contentText = when {
                        start == null || end == null -> stringResource(R.string.head_tracking_drift_test_description)
                        elapsedS >= 60 -> stringResource(
                            R.string.head_tracking_drift_result,
                            Math.toDegrees((end.headZ - start.headZ).toDouble()) / (elapsedNanos / 60e9),
                            elapsedS
                        )
                        else -> stringResource(R.string.head_tracking_drift_elapsed, elapsedS)
                    }
                )
            }

            StyledButton(
                onClick = {
                    if (driftRunning) {
                        driftEnd = sample
                        driftRunning = false
                    } else {
                        driftEnd = null
                        driftStart = sample
                        driftRunning = driftStart != null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                maxScale = 0.05f,
                enabled = driftRunning || sample != null,
                materialButtonStyle = MaterialButtonStyle.Outlined
            ) {
                Text(
                    stringResource(
                        if (driftRunning) R.string.head_tracking_drift_stop else R.string.head_tracking_drift_start
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}

private fun shareLog(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, file.name))
}

/**
 * Writes every head pose frame to a CSV under getExternalFilesDir(null)/headtracking_logs/,
 * flushing every [FLUSH_INTERVAL_MS] and stopping on its own at [MAX_BYTES].
 */
object HeadTrackingCsvLogger {
    private const val TAG = "HeadTrackingCsvLogger"
    private const val MAX_BYTES = 20L * 1024 * 1024
    private const val FLUSH_INTERVAL_MS = 2000L
    private const val HEADER =
        "elapsed_ms,payload_hex,qw,qx,qy,qz,ax,ay,az,head_x_rad,head_y_rad,head_z_rad,sent_rx,sent_ry,sent_rz\n"
    private val HEX = "0123456789abcdef".toCharArray()

    private var writer: BufferedWriter? = null
    private var startNanos = 0L
    private var bytesWritten = 0L
    private var lastFlush = 0L
    private val line = StringBuilder(256)

    private val _file = MutableStateFlow<File?>(null)
    val file: StateFlow<File?> = _file.asStateFlow()

    private val _logging = MutableStateFlow(false)
    val logging: StateFlow<Boolean> = _logging.asStateFlow()

    @Synchronized
    fun start(context: Context): File? {
        stop()
        val dir = headTrackingLogDir(context)
        val f = File(dir, SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv")
        return try {
            dir.mkdirs()
            writer = f.bufferedWriter().apply { write(HEADER) }
            bytesWritten = HEADER.length.toLong()
            startNanos = SystemClock.elapsedRealtimeNanos()
            lastFlush = SystemClock.elapsedRealtime()
            HeadPoseTelemetry.sink = ::write
            _file.value = f
            _logging.value = true
            f
        } catch (e: Exception) {
            Log.e(TAG, "could not open $f", e)
            null
        }
    }

    @Synchronized
    fun stop() {
        HeadPoseTelemetry.sink = null
        try {
            writer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close failed", e)
        }
        writer = null
        _logging.value = false
    }

    @Synchronized
    private fun write(s: HeadPoseSample, payload: ByteArray) {
        val w = writer ?: return
        line.setLength(0)
        line.append((s.elapsedNanos - startNanos) / 1_000_000L).append(',')
        for (b in payload) {
            val v = b.toInt() and 0xFF
            line.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        line.append(',').append(s.qw).append(',').append(s.qx).append(',').append(s.qy).append(',').append(s.qz)
        line.append(',').append(s.ax).append(',').append(s.ay).append(',').append(s.az)
        line.append(',').append(s.headX).append(',').append(s.headY).append(',').append(s.headZ)
        line.append(',').append(s.sentRx).append(',').append(s.sentRy).append(',').append(s.sentRz).append('\n')
        try {
            w.append(line)
            bytesWritten += line.length
            val now = SystemClock.elapsedRealtime()
            if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                w.flush()
                lastFlush = now
            }
        } catch (e: Exception) {
            Log.w(TAG, "write failed, stopping", e)
            stop()
            return
        }
        if (bytesWritten >= MAX_BYTES) {
            Log.i(TAG, "log reached $bytesWritten bytes, stopping")
            stop()
        }
    }
}
