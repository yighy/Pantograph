package com.yighy.pantograph.drawing

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.yighy.pantograph.ui.theme.MotionTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The path tool's own controls, modelled on [SelectionPanel]: an editing session gets the
 * toolbar for as long as it lasts.
 */
@Composable
fun PathPanel(viewModel: DrawingViewModel) {
    val points by remember(viewModel) { viewModel.uiState.map { it.pathPoints.size }.distinctUntilChanged() }.collectAsState(0)
    val closed by remember(viewModel) { viewModel.uiState.map { it.pathClosed }.distinctUntilChanged() }.collectAsState(false)
    val pointIsCorner by remember(viewModel) { viewModel.uiState.map { it.pathPointUnderCursorIsCorner }.distinctUntilChanged() }.collectAsState(null)

    Column(modifier = Modifier.animateContentSize(animationSpec = MotionTokens.panelTransition)) {
        if (points < 2) {
            // The gesture is new to this app, so it is spelled out rather than left to be
            // discovered by pressing the one button and seeing what happens.
            Text(
                "Hold the button to drop a point, steer, then release. Hold an existing point to move it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.commitPath() },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Draw", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = { viewModel.cancelPath() },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Rounded.Close, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Discard", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Enabled rather than hidden: a control that comes and goes as the cursor
                    // drifts over a point would be harder to aim at than one that greys out.
                    OutlinedButton(
                        onClick = { viewModel.togglePathPointCorner() },
                        enabled = pointIsCorner != null,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            if (pointIsCorner == true) "Round point" else "Sharp point",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.togglePathClosed() },
                        enabled = points >= 3,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(if (closed) "Open" else "Close", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun SelectionPanel(viewModel: DrawingViewModel) {
    val isSelectionClosed by remember(viewModel) { viewModel.uiState.map { it.isSelectionClosed }.distinctUntilChanged() }.collectAsState(false)
    val hasFloating by remember(viewModel) { viewModel.uiState.map { it.floatingBitmap != null }.distinctUntilChanged() }.collectAsState(false)
    val floatingScale by remember(viewModel) { viewModel.uiState.map { it.floatingScale }.distinctUntilChanged() }.collectAsState(1f)
    val floatingRotation by remember(viewModel) { viewModel.uiState.map { it.floatingRotation }.distinctUntilChanged() }.collectAsState(0f)
    val drawingMode by remember(viewModel) { viewModel.uiState.map { it.drawingMode }.distinctUntilChanged() }.collectAsState(DrawingMode.Freehand)
    val isSelectionToolActive = drawingMode.isSelectionTool()

    Column(modifier = Modifier.animateContentSize(animationSpec = MotionTokens.panelTransition)) {
        // This panel swaps between three quite differently sized layouts while staying open.
        when {
            hasFloating -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingRow("Scale", "${(floatingScale * 100).toInt()}%", floatingScale, { viewModel.setSelectionScale(it) }, 0.1f..3f)
                    SettingRow("Rotation", "${floatingRotation.toInt()}\u00B0", floatingRotation, { viewModel.setSelectionRotation(it) }, -180f..180f)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.commitSelection() },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Apply", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = { viewModel.cancelSelection() },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Rounded.Close, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(
                        "Drag the cursor to move the selection",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            isSelectionClosed -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isSelectionToolActive) {
                            Button(
                                onClick = { viewModel.liftSelection(cut = true) },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Text("Move", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Button(
                            onClick = { viewModel.duplicateSelection() },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                        ) {
                            Text("Duplicate", style = MaterialTheme.typography.labelSmall)
                        }
                        Button(
                            onClick = { viewModel.deleteSelection() },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                        ) {
                            Text("Delete", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.invertSelection() },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("Invert", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = { viewModel.clearSelection() },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("Deselect", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (!isSelectionToolActive) {
                        Text(
                            "Selection active - strokes only affect the selected area",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            else -> {
                Text(
                    "Hold the pen and move the cursor to outline an area, then release",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}
