package com.yighy.pantograph.drawing

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The project's timelapse: whether it is recording, how much has been recorded and what it takes
 * up, and the ways out - export it as a video, or throw it away.
 */
@Composable
internal fun TimelapseDialog(viewModel: DrawingViewModel, onDismiss: () -> Unit) {
    val info by viewModel.timelapseInfo.collectAsState()
    val export by viewModel.timelapseExport.collectAsState()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var chooseLength by remember { mutableStateOf(false) }
    val exporting = export is TimelapseExport.Running

    val close = {
        viewModel.clearTimelapseExportResult()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = close,
        icon = { Icon(Icons.Rounded.Timelapse, contentDescription = null) },
        title = { Text("Timelapse") },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = info.recording,
                            role = Role.Switch,
                            onValueChange = { viewModel.setTimelapseRecording(it) }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Record this drawing", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = info.recording, onCheckedChange = null)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                StatRow("Strokes", "${info.strokes}")
                StatRow("Length", if (info.frames == 0) "-" else formatSeconds(info.seconds))
                StatRow("Storage", Formatter.formatShortFileSize(context, info.bytes))

                Spacer(Modifier.height(16.dp))
                when (val e = export) {
                    is TimelapseExport.Running -> {
                        Text("Exporting...", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { e.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        FilledTonalButton(
                            onClick = { chooseLength = true },
                            enabled = info.frames > 0,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Export video") }
                        when (e) {
                            TimelapseExport.Saved -> ExportNote("Saved to Movies/Pantograph")
                            TimelapseExport.Failed -> ExportNote("The video couldn't be made on this device", error = true)
                            else -> {}
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = close) { Text("Done") }
        },
        dismissButton = {
            TextButton(
                onClick = { confirmDelete = true },
                enabled = info.frames > 0 && !exporting,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete") }
        }
    )

    if (chooseLength) {
        LengthDialog(
            frames = info.frames,
            onDismiss = { chooseLength = false },
            onExport = { seconds ->
                chooseLength = false
                viewModel.exportTimelapse(seconds)
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete the timelapse?") },
            text = { Text("Its frames are removed and recording stops. The drawing itself is not touched.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTimelapse()
                        confirmDelete = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

/**
 * How long the video should be: 15 or 30 seconds, or the frames' own pace. Each with the size the
 * video will come to, which is what decides between them when it is meant for sharing.
 */
@Composable
private fun LengthDialog(frames: Int, onDismiss: () -> Unit, onExport: (Int?) -> Unit) {
    val context = LocalContext.current
    val options = listOf<Int?>(15, 30, null)
    var picked by remember { mutableStateOf<Int?>(15) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Video length") },
        text = {
            Column {
                options.forEach { seconds ->
                    val durationUs = TimelapseTiming.durationUs(TimelapseTiming.plan(frames, seconds))
                    val size = durationUs / 1_000_000.0 * TimelapseExporter.BITRATE / 8
                    val label = if (seconds == null) "Original, ${formatSeconds(durationUs / 1_000_000f)}" else "$seconds seconds"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = picked == seconds, role = Role.RadioButton) { picked = seconds }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = picked == seconds, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            "~" + Formatter.formatShortFileSize(context, size.toLong()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onExport(picked) }) { Text("Export") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ExportNote(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A length in whole seconds: "8 s", "2 min 5 s". */
private fun formatSeconds(seconds: Float): String {
    val whole = kotlin.math.ceil(seconds).toInt()
    return if (whole < 60) "$whole s" else "${whole / 60} min ${whole % 60} s"
}
