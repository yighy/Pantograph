package com.yighy.paintcursor.drawing

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yighy.paintcursor.ui.theme.MotionTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Which expandable panel is currently open under the toolbar. Panels are mutually exclusive. */
enum class ToolbarPanel { None, Brush, Color, Settings }

@Composable
fun DrawingToolbar(
    viewModel: DrawingViewModel,
    onOpenBrushStudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activePanel by remember { mutableStateOf(ToolbarPanel.None) }

    val drawingMode by remember(viewModel) { viewModel.uiState.map { it.drawingMode }.distinctUntilChanged() }.collectAsState(DrawingMode.Freehand)
    val canUndo by remember(viewModel) { viewModel.uiState.map { it.canUndo }.distinctUntilChanged() }.collectAsState(false)
    val canRedo by remember(viewModel) { viewModel.uiState.map { it.canRedo }.distinctUntilChanged() }.collectAsState(false)
    val selectedColor by remember(viewModel) { viewModel.uiState.map { it.selectedColor }.distinctUntilChanged() }.collectAsState(Color.Black)
    val isLazyModeActive by remember(viewModel) { viewModel.uiState.map { it.isLazyModeActive }.distinctUntilChanged() }.collectAsState(false)

    val isSelectionMode = drawingMode.isSelectionTool()
    val isSelectionClosed by remember(viewModel) { viewModel.uiState.map { it.isSelectionClosed }.distinctUntilChanged() }.collectAsState(false)

    // Auto-show panels when specialized tools are selected (UX improvement)
    LaunchedEffect(drawingMode) {
        if (drawingMode is DrawingMode.BucketFill) activePanel = ToolbarPanel.Settings
        // Entering selection mode (lasso/rect/wand/color/import) closes other panels so
        // the selection controls are immediately visible
        if (drawingMode.isSelectionTool()) {
            activePanel = ToolbarPanel.None
        }
    }

    LaunchedEffect(isLazyModeActive) {
        if (isLazyModeActive) activePanel = ToolbarPanel.Settings
    }

    // Height and opacity both ride springs from the same family, so the fade lands with the
    // collapse instead of finishing early and leaving an empty box to close on its own.
    val visibilityAnimSpecEnter = remember {
        expandVertically(
            animationSpec = MotionTokens.panelTransition
        ) + fadeIn(animationSpec = MotionTokens.expressiveEnter)
    }
    val visibilityAnimSpecExit = remember {
        shrinkVertically(
            animationSpec = MotionTokens.panelTransition
        ) + fadeOut(animationSpec = MotionTokens.expressiveExit)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .wrapContentHeight()
            // No animateContentSize here: the panels' own expandVertically/shrinkVertically
            // already animate this height. Running both made the Surface re-spring whatever
            // height the children hadn't finished animating, which showed up as a jolt at
            // the end of a collapse.
            // Swipe down anywhere on the toolbar to collapse the open panel.
            // Children (sliders, scrollable lists) consume their own gestures first,
            // so this only sees swipes on non-interactive areas.
            .pointerInput(Unit) {
                var totalDrag = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        totalDrag += dragAmount
                        if (totalDrag > 0f) change.consume()
                    },
                    onDragEnd = {
                        if (totalDrag > 80f) activePanel = ToolbarPanel.None
                    }
                )
            },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
            // Deliberately no spacedBy: a collapsing panel keeps its slot in this Column
            // until its exit transition ends, so the gap reserved for it stayed at full
            // size the whole way down and then vanished in a single frame. Each panel
            // brings its own leading divider padding instead.
        ) {
            // Row 1: Tools & Navigation
            // The extra-tools gate (Fill/Gradient/Lazy/Lasso/Rect/Wand/Color) moved to the
            // top-right action group (see ToolsMenuButton in DrawingScreen.kt), which also
            // absorbed the Fit-to-Screen action. Everything left here sits in one centered
            // row, grouped by separators: Brush/Color, then Settings, then Undo/Redo. They
            // used to be a centered group plus an end-pinned pair, but at the 48dp minimum
            // touch target those two alignments overlap on a ~360dp-wide screen.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Brush & Color Picker
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolToggleButton(
                        selected = activePanel == ToolbarPanel.Brush,
                        onClick = { activePanel = if (activePanel == ToolbarPanel.Brush) ToolbarPanel.None else ToolbarPanel.Brush },
                        icon = Icons.Rounded.Brush,
                        contentDescription = "Brush presets"
                    )

                    // Specialized Color Picker Button
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .semantics { this.selected = activePanel == ToolbarPanel.Color }
                            .clickable(role = Role.Button) { activePanel = if (activePanel == ToolbarPanel.Color) ToolbarPanel.None else ToolbarPanel.Color },
                        contentAlignment = Alignment.Center
                    ) {
                        // The swatch is the affordance, so nothing sits on top of it: the
                        // ColorLens glyph used to cover the middle and leave only a thin
                        // ring of the actual colour showing. Open state reads as a ring
                        // around the swatch rather than a fill behind it, which the larger
                        // swatch would otherwise hide.
                        val isOpen = activePanel == ToolbarPanel.Color
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(selectedColor)
                                .border(
                                    width = if (isOpen) 2.dp else 1.dp,
                                    color = if (isOpen) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                )
                                .semantics { contentDescription = "Colour picker" }
                        )
                    }
                }

                ToolbarSeparator()

                // Settings
                ToolToggleButton(
                    selected = activePanel == ToolbarPanel.Settings,
                    onClick = { activePanel = if (activePanel == ToolbarPanel.Settings) ToolbarPanel.None else ToolbarPanel.Settings },
                    icon = Icons.Rounded.Tune,
                    contentDescription = "Tool settings"
                )

                ToolbarSeparator()

                // History
                IconButton(onClick = { viewModel.undo() }, enabled = canUndo) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { viewModel.redo() }, enabled = canRedo) {
                    Icon(Icons.AutoMirrored.Rounded.Redo, "Redo", modifier = Modifier.size(20.dp))
                }
            }

            // Quick Brush Panel - Smooth Slide
            AnimatedVisibility(
                visible = activePanel == ToolbarPanel.Brush,
                enter = visibilityAnimSpecEnter,
                exit = visibilityAnimSpecExit
            ) {
                QuickBrushPanel(viewModel, onOpenStudio = {
                    activePanel = ToolbarPanel.None
                    onOpenBrushStudio()
                })
            }

            // Color Picker Panel - Smooth Slide
            AnimatedVisibility(
                visible = activePanel == ToolbarPanel.Color,
                enter = visibilityAnimSpecEnter,
                exit = visibilityAnimSpecExit
            ) {
                ColorPickerContent(viewModel) { activePanel = ToolbarPanel.None }
            }

            // Global Settings Panel - Smooth Slide
            AnimatedVisibility(
                visible = activePanel == ToolbarPanel.Settings,
                enter = visibilityAnimSpecEnter,
                exit = visibilityAnimSpecExit
            ) {
                GlobalSettingsPanel(viewModel)
            }

            // Selection Panel - shown while a selection tool is active OR a selection is
            // still alive (it clips drawing tools), as long as no other panel is open
            AnimatedVisibility(
                visible = (isSelectionMode || isSelectionClosed) && activePanel == ToolbarPanel.None,
                enter = visibilityAnimSpecEnter,
                exit = visibilityAnimSpecExit
            ) {
                SelectionPanel(viewModel)
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

    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
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

@Composable
fun ExtraToolItem(
    label: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The Text below is the accessible name, so the icon stays decorative.
        ToolToggleButton(selected = selected, onClick = onClick, icon = icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

/** Hairline divider grouping the toolbar row into brush/color, settings and history. */
@Composable
private fun ToolbarSeparator() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    )
}

@Composable
fun ToolToggleButton(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    // Null only where a visible text label already names the button (see ExtraToolItem);
    // otherwise this is the button's only name for screen readers.
    contentDescription: String?,
    selectedColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedContainerColor: Color = MaterialTheme.colorScheme.primaryContainer
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(MaterialTheme.shapes.medium)
            // Exposed as a selectable button so TalkBack announces the open/closed state
            // of the panel this toggle controls, not just its name.
            .semantics { this.selected = selected }
            .clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(selectedContainerColor)
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun ColorPickerContent(viewModel: DrawingViewModel, onDismiss: () -> Unit) {
    val selectedColor by remember(viewModel) { viewModel.uiState.map { it.selectedColor }.distinctUntilChanged() }.collectAsState(Color.Black)
    val colorHistory by remember(viewModel) { viewModel.uiState.map { it.colorHistory }.distinctUntilChanged() }.collectAsState(emptyList())
    val isSliderMode by remember(viewModel) { viewModel.uiState.map { it.isColorPickerSliderMode }.distinctUntilChanged() }.collectAsState(false)
    val isEyeDropperActive by remember(viewModel) { viewModel.uiState.map { it.isEyeDropperMode }.distinctUntilChanged() }.collectAsState(false)

    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(bottom = 4.dp))
        
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
fun QuickBrushPanel(
    viewModel: DrawingViewModel,
    onOpenStudio: () -> Unit
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Size/Softness/Opacity/Flow sliders live on the FAB's right satellite gate
            // (see HoverDrawButton). Opening this panel goes straight to the saved presets
            // rather than to an intermediate menu; the studio is one tap from the header.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Presets",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
                TextButton(
                    onClick = onOpenStudio,
                    modifier = Modifier.height(48.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Rounded.Tune, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Brush studio", style = MaterialTheme.typography.labelMedium)
                }
            }

            PresetsListContent(viewModel)
        }
    }
}

@Composable
fun PresetsListContent(viewModel: DrawingViewModel) {
    val customBrushes by remember(viewModel) { viewModel.uiState.map { it.customBrushes }.distinctUntilChanged() }.collectAsState(emptyList())
    val selectedCustomBrushId by remember(viewModel) { viewModel.uiState.map { it.selectedCustomBrushId }.distinctUntilChanged() }.collectAsState(null)
    
    var brushToRename by remember { mutableStateOf<BrushConfig?>(null) }
    var brushToDelete by remember { mutableStateOf<BrushConfig?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        if (customBrushes.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("No saved brushes", style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                customBrushes.forEach { brush ->
                    val isActive = brush.id == selectedCustomBrushId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                viewModel.selectCustomBrush(brush)
                            }
                            .background(if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
                            // Vertical padding is small because the 48dp action buttons now
                            // set the row height; this keeps rows the same size as before.
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(brush.name, style = MaterialTheme.typography.bodySmall, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal, color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                            Text("${brush.size.toInt()}px - Flow ${(brush.flow*100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row {
                            IconButton(onClick = { brushToRename = brush }) {
                                Icon(Icons.Rounded.Edit, "Rename brush", tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { brushToDelete = brush }) {
                                Icon(Icons.Rounded.Delete, "Delete brush", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 8.dp), color = if (isActive) Color.Transparent else DividerDefaults.color)
                }
            }
        }
    }

    if (brushToRename != null) {
        var renameText by remember { mutableStateOf(brushToRename!!.name) }
        val renameExists = customBrushes.any { it.name.equals(renameText, ignoreCase = true) && it.id != brushToRename!!.id }

        AlertDialog(
            onDismissRequest = { brushToRename = null },
            title = { Text("Rename Brush", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("New Name") },
                    singleLine = true,
                    isError = renameExists,
                    supportingText = if (renameExists) { { Text("Name already exists") } } else null
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank() && !renameExists,
                    onClick = {
                        viewModel.renameCustomBrush(brushToRename!!, renameText)
                        brushToRename = null
                    }
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { brushToRename = null }) { Text("Cancel") }
            }
        )
    }

    if (brushToDelete != null) {
        AlertDialog(
            onDismissRequest = { brushToDelete = null },
            title = { Text("Delete Brush") },
            text = { Text("Are you sure you want to delete '${brushToDelete!!.name}'?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCustomBrush(brushToDelete!!)
                    brushToDelete = null
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { brushToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun GlobalSettingsPanel(viewModel: DrawingViewModel) {
    // Smoothing lives in the Brush Studio only now (see BrushStudio.kt); removed here to
    // keep this quick panel focused on things that aren't brush-specific
    val cursorSensitivity by remember(viewModel) { viewModel.uiState.map { it.cursorSensitivity }.distinctUntilChanged() }.collectAsState(0.6f)
    val drawingMode by remember(viewModel) { viewModel.uiState.map { it.drawingMode }.distinctUntilChanged() }.collectAsState(DrawingMode.Freehand)
    val fillTolerance by remember(viewModel) { viewModel.uiState.map { it.fillTolerance }.distinctUntilChanged() }.collectAsState(10f)
    val isLazyModeActive by remember(viewModel) { viewModel.uiState.map { it.isLazyModeActive }.distinctUntilChanged() }.collectAsState(false)
    val lazyRadius by remember(viewModel) { viewModel.uiState.map { it.lazyRadius }.distinctUntilChanged() }.collectAsState(50f)

    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingRow("Draw Sensitivity", "${"%.1f".format(cursorSensitivity)}x", cursorSensitivity, { viewModel.setCursorSensitivity(it) }, 0.1f..1.0f)
            
            if (isLazyModeActive) {
                SettingRow("Lazy Radius", "${lazyRadius.toInt()}px", lazyRadius, { viewModel.setLazyRadius(it) }, 10f..500f)
            }

            if (drawingMode is DrawingMode.BucketFill || drawingMode is DrawingMode.SelectWand || drawingMode is DrawingMode.SelectColor) {
                SettingRow("Tolerance", "${fillTolerance.toInt()}", fillTolerance, { viewModel.setFillTolerance(it) }, 0f..200f)
            }
        }
    }
}

@Composable
fun SettingRow(label: String, valueLabel: String, value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(valueLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            // No height constraint: Slider's own 48dp box is the thumb's touch target,
            // and clamping it to 24dp made the thumb hard to grab vertically.
            Slider(value = value, onValueChange = onValueChange, valueRange = range)
        }
    }
}
