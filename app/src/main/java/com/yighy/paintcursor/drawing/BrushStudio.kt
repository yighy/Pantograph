package com.yighy.paintcursor.drawing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.yighy.paintcursor.data.LayerEntity
import com.yighy.paintcursor.data.PreferenceManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun AdvancedBrushStudioWrapper(viewModel: DrawingViewModel, onDismiss: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    AdvancedBrushStudio(uiState = uiState, viewModel = viewModel, onDismiss = onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AdvancedBrushStudio(uiState: DrawingState, viewModel: DrawingViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val tipPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.setBrushTip(context, it.toString()) }
    }
    val texturePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.setBrushTexture(context, it.toString()) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(bottom = 60.dp)
        ) {
            // Pinned header: title + live preview stay visible while any slider below is adjusted
            stickyHeader {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Brush Studio",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        // Current color at a glance (the preview stroke uses it)
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(uiState.selectedColor)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            // Checkerboard Background (transparency indicator, tinted from the
                            // theme's neutral on-surface-variant tone so it stays visible and
                            // correctly contrasted in both light and dark theme)
                            val checkerColor = MaterialTheme.colorScheme.onSurfaceVariant
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val squareSize = 12.dp.toPx()
                                val cols = (size.width / squareSize).toInt() + 1
                                val rows = (size.height / squareSize).toInt() + 1
                                for (i in 0 until cols) {
                                    for (j in 0 until rows) {
                                        if ((i + j) % 2 == 0) {
                                            drawRect(
                                                color = checkerColor.copy(alpha = 0.08f),
                                                topLeft = Offset(i * squareSize, j * squareSize),
                                                size = androidx.compose.ui.geometry.Size(squareSize, squareSize)
                                            )
                                        }
                                    }
                                }
                            }

                            // The preview is rendered by the actual stamp engine (single code
                            // path with real strokes) - see DrawingViewModel.renderBrushPreview.
                            BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
                                val wPx = constraints.maxWidth
                                val hPx = constraints.maxHeight
                                val previewKey = listOf(
                                    uiState.selectedWidth, uiState.brushSoftness, uiState.brushOpacity,
                                    uiState.brushFlow, uiState.brushSpacing, uiState.selectedColor,
                                    uiState.sizeJitter, uiState.brushRotation, uiState.brushRotationJitter,
                                    uiState.brushRotationDynamics, uiState.brushTipBitmap,
                                    uiState.brushTextureMask, uiState.velocityEnabled,
                                    uiState.velocitySizeAmount, uiState.velocityFlowAmount,
                                    uiState.velocityScatterAmount, wPx, hPx
                                )
                                val previewBitmap = remember(previewKey) {
                                    if (wPx > 0 && hPx > 0) viewModel.renderBrushPreview(wPx, hPx) else null
                                }
                                previewBitmap?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Core Properties
            item {
                StudioSection(title = "Core Properties", icon = Icons.Rounded.Brush) {
                    DrawingSettingRow("Size", "${uiState.selectedWidth.toInt()}px", uiState.selectedWidth, { viewModel.selectWidth(it) }, 1f..300f)
                    DrawingSettingRow("Softness", "${(uiState.brushSoftness * 100).toInt()}%", uiState.brushSoftness, { viewModel.setBrushSoftness(it) }, 0f..1f)
                    DrawingSettingRow("Opacity", "${(uiState.brushOpacity * 100).toInt()}%", uiState.brushOpacity, { viewModel.setBrushOpacity(it) }, 0f..1f)
                    DrawingSettingRow("Flow", "${(uiState.brushFlow * 100).toInt()}%", uiState.brushFlow, { viewModel.setBrushFlow(it) }, 0f..1f)
                    DrawingSettingRow("Smoothing", "${(uiState.brushSmoothing * 100).toInt()}%", uiState.brushSmoothing, { viewModel.setBrushSmoothing(it) }, 0f..1f)
                }
            }

            // Dynamics & Jitter
            item {
                StudioSection(title = "Dynamics", icon = Icons.Rounded.Tune) {
                    DrawingSettingRow("Spacing", "${(uiState.brushSpacing * 100).toInt()}%", uiState.brushSpacing, { viewModel.setBrushSpacing(it) }, 0.01f..2f)
                    DrawingSettingRow("Rotation", "${uiState.brushRotation.toInt()}\u00B0", uiState.brushRotation, { viewModel.setBrushRotation(it) }, 0f..360f)

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Follow Direction", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Rotate brush along path", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = uiState.brushRotationDynamics, onCheckedChange = { viewModel.setRotationDynamics(it) })
                    }

                    DrawingSettingRow("Size Jitter", "${(uiState.sizeJitter * 100).toInt()}%", uiState.sizeJitter, { viewModel.setSizeJitter(it) }, 0f..1f)
                    DrawingSettingRow("Rotation Jitter", "${uiState.brushRotationJitter.toInt()}\u00B0", uiState.brushRotationJitter, { viewModel.setRotationJitter(it) }, 0f..180f)

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Velocity Dynamics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("React to stroke speed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = uiState.velocityEnabled, onCheckedChange = { viewModel.setVelocityEnabled(it) })
                    }

                    if (uiState.velocityEnabled) {
                        DrawingSettingRow("Velocity Size", "${if (uiState.velocitySizeAmount > 0) "+" else ""}${(uiState.velocitySizeAmount * 100).toInt()}%", uiState.velocitySizeAmount, { viewModel.setVelocitySize(it) }, -1f..1f)
                        DrawingSettingRow("Velocity Flow", "${if (uiState.velocityFlowAmount > 0) "+" else ""}${(uiState.velocityFlowAmount * 100).toInt()}%", uiState.velocityFlowAmount, { viewModel.setVelocityFlow(it) }, -1f..1f)
                        DrawingSettingRow("Velocity Scatter", "${if (uiState.velocityScatterAmount > 0) "+" else ""}${(uiState.velocityScatterAmount * 100).toInt()}%", uiState.velocityScatterAmount, { viewModel.setVelocityScatter(it) }, -1f..1f)
                    }
                }
            }

            // Custom Assets
            item {
                StudioSection(title = "Custom Assets", icon = Icons.Rounded.Texture) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AssetPickerCard(
                            label = "Brush Tip",
                            icon = Icons.Rounded.FilterTiltShift,
                            bitmap = uiState.brushTipBitmap,
                            onPick = { tipPicker.launch("image/*") },
                            onClear = { viewModel.setBrushTip(context, null) },
                            modifier = Modifier.weight(1f)
                        )
                        AssetPickerCard(
                            label = "Texture",
                            icon = Icons.Rounded.Texture,
                            bitmap = uiState.brushTextureBitmap,
                            onPick = { texturePicker.launch("image/*") },
                            onClear = { viewModel.setBrushTexture(context, null) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "Tap to pick an image \u2022 Texture: white keeps paint, dark cuts it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Presets Section
            val selectedBrush = uiState.customBrushes.find { it.id == uiState.selectedCustomBrushId }
            item {
                StudioSection(
                    title = if (selectedBrush != null) "Editing: ${selectedBrush.name}" else "Presets", 
                    icon = Icons.Rounded.AutoAwesome
                ) {
                    var newBrushName by remember { mutableStateOf("") }
                    val nameExists = uiState.customBrushes.any { it.name.equals(newBrushName, ignoreCase = true) }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(), 
                        verticalAlignment = Alignment.CenterVertically, 
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newBrushName,
                            onValueChange = { newBrushName = it },
                            label = { Text("Brush Name") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            isError = nameExists
                        )
                        Button(
                            enabled = newBrushName.isNotBlank() && !nameExists, 
                            onClick = { viewModel.saveCurrentAsCustomBrush(newBrushName); newBrushName = "" },
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Rounded.Add, null)
                        }
                    }

                    if (selectedBrush != null) {
                        Button(
                            onClick = { viewModel.updateSelectedBrush() }, 
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Save Changes to Preset")
                        }
                    }
                }
            }
            
            // Inlined Presets items to avoid nested scrolling conflicts
            items(uiState.customBrushes) { brush ->
                val isActive = brush.id == uiState.selectedCustomBrushId
                BrushPresetCard(
                    brush = brush,
                    isActive = isActive,
                    onSelect = { viewModel.selectCustomBrush(brush) },
                    onDelete = { viewModel.deleteCustomBrush(brush) },
                    onRename = { viewModel.renameCustomBrush(brush, it) }
                )
            }
        }
    }
}

/** Asset slot showing the loaded bitmap (with a clear badge) or a placeholder to pick one. */
@Composable
fun AssetPickerCard(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bitmap: android.graphics.Bitmap?,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onPick,
        modifier = modifier.height(92.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (bitmap != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Box {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaterialTheme.shapes.small)
                            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(icon, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (bitmap != null) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (bitmap != null) {
                Surface(
                    onClick = onClear,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear $label",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StudioSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                content()
            }
        }
    }
}

@Composable
fun BrushPresetCard(
    brush: BrushConfig,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = if (isActive) 4.dp else 0.dp,
        border = if (isActive) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
                                Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = brush.name, 
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Size: ${brush.size.toInt()}px \u2022 Opacity: ${(brush.opacity*100).toInt()}% \u2022 Flow: ${(brush.flow*100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { showRenameDialog = true }) {
                    Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Preset") },
            text = { Text("Are you sure you want to delete '${brush.name}'?") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showRenameDialog) {
        var renameText by remember { mutableStateOf(brush.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Preset") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("New Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(renameText); showRenameDialog = false }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun DrawingSettingRow(label: String, valueLabel: String, value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.labelSmall); Text(valueLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Slider(value = value, onValueChange = onValueChange, valueRange = range, modifier = Modifier.height(24.dp))
        }
    }
}

