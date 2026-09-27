package com.yighy.pantograph.drawing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun ColorPickerContent(viewModel: DrawingViewModel, onDismiss: () -> Unit) {
    val selectedColor by remember(viewModel) { viewModel.uiState.map { it.selectedColor }.distinctUntilChanged() }.collectAsState(Color.Black)
    val colorHistory by remember(viewModel) { viewModel.uiState.map { it.colorHistory }.distinctUntilChanged() }.collectAsState(emptyList())
    val isSliderMode by remember(viewModel) { viewModel.uiState.map { it.isColorPickerSliderMode }.distinctUntilChanged() }.collectAsState(false)
    val isEyeDropperActive by remember(viewModel) { viewModel.uiState.map { it.isEyeDropperMode }.distinctUntilChanged() }.collectAsState(false)

    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (colorHistory.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                colorHistory.forEach { color ->
                    // The swatch stays 32dp visually; the tappable box around it is 48dp so
                    // the row still meets the minimum touch target without fat circles.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { viewModel.selectColor(color) },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (color == selectedColor) 2.dp else 0.5.dp,
                                    color = if (color == selectedColor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
        }

        HSBPickerView(
            initialColor = selectedColor,
            isSliderMode = isSliderMode,
            isEyeDropperActive = isEyeDropperActive,
            onModeToggle = { viewModel.setColorPickerSliderMode(it) },
            onEyeDropperClick = { 
                viewModel.toggleEyeDropper()
                onDismiss()
            },
            onColorChanged = { viewModel.selectColor(it) }
        )
    }
}

@Composable
fun GlobalSettingsPanel(viewModel: DrawingViewModel) {
    // Smoothing lives in the Brush Studio only now (see BrushStudio.kt); removed here to
    // keep this quick panel focused on things that aren't brush-specific
    val cursorSensitivity by remember(viewModel) { viewModel.uiState.map { it.cursorSensitivity }.distinctUntilChanged() }.collectAsState(0.6f)
    val isFineCursor by remember(viewModel) { viewModel.uiState.map { it.isFineCursor }.distinctUntilChanged() }.collectAsState(false)

    // Tools' own values are not here any more: each lives on its tool's chip, which is showing
    // exactly while the value matters, and is marked as draggable. What is left is the one
    // setting that belongs to no tool.
    Column {
        // The label follows the tool rather than staying put: with Fine armed this is no longer
        // a setting about drawing, it is what the cursor does at all times, and this row is the
        // only place that says so.
        SettingRow(
            if (isFineCursor) "Cursor Sensitivity" else "Draw Sensitivity",
            "${"%.1f".format(cursorSensitivity)}x",
            cursorSensitivity,
            { viewModel.setCursorSensitivity(it) },
            0.1f..1.0f
        )
    }
}

@Composable
fun SettingRow(label: String, valueLabel: String, value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(valueLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            // No height constraint: Slider's own 48dp box is the thumb's touch target,
            // and clamping it to 24dp made the thumb hard to grab vertically.
            Slider(value = value, onValueChange = onValueChange, valueRange = range)
        }
    }
}
