package com.yighy.pantograph.drawing

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
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
 * up, and a way to throw it away.
 */
@Composable
internal fun TimelapseDialog(viewModel: DrawingViewModel, onDismiss: () -> Unit) {
    val info by viewModel.timelapseInfo.collectAsState()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {
            TextButton(
                onClick = { confirmDelete = true },
                enabled = info.frames > 0,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete") }
        }
    )

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

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The length at one frame per stroke: "8 s", "2 min 5 s". */
private fun formatSeconds(seconds: Float): String {
    val whole = kotlin.math.ceil(seconds).toInt()
    return if (whole < 60) "$whole s" else "${whole / 60} min ${whole % 60} s"
}
