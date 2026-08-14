package com.yighy.paintcursor.drawing

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
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
    // Hoisted: which panel is open is decided by whatever the user just tapped, including
    // the tool menu that lives in DrawingScreen. Deriving it from state changes here meant a
    // tool that was re-selected without changing state simply never reopened its panel.
    activePanel: ToolbarPanel,
    onActivePanelChange: (ToolbarPanel) -> Unit,
    modifier: Modifier = Modifier
) {
    val drawingMode by remember(viewModel) { viewModel.uiState.map { it.drawingMode }.distinctUntilChanged() }.collectAsState(DrawingMode.Freehand)
    val canUndo by remember(viewModel) { viewModel.uiState.map { it.canUndo }.distinctUntilChanged() }.collectAsState(false)
    val canRedo by remember(viewModel) { viewModel.uiState.map { it.canRedo }.distinctUntilChanged() }.collectAsState(false)
    val selectedColor by remember(viewModel) { viewModel.uiState.map { it.selectedColor }.distinctUntilChanged() }.collectAsState(Color.Black)

    val isSelectionMode = drawingMode.isSelectionTool()
    val isSelectionClosed by remember(viewModel) { viewModel.uiState.map { it.isSelectionClosed }.distinctUntilChanged() }.collectAsState(false)

    // Entering a selection tool still closes whatever was open, so the selection controls are
    // immediately visible. This one stays state-driven because it only ever *closes* panels -
    // a repeated selection with nothing to change is correctly a no-op.
    LaunchedEffect(drawingMode) {
        if (drawingMode.isSelectionTool()) onActivePanelChange(ToolbarPanel.None)
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
            // Collapsed, this is just five buttons - stretching it edge to edge left big
            // dead margins in portrait. It hugs its row instead, and only takes the full
            // width when a panel that actually needs it (sliders, swatches) is open.
            // Caps the open width so landscape doesn't stretch sliders across 700dp of
            // screen with the controls marooned at either end. Centred by the parent.
            // Must come before the fill below: fillMaxWidth pins min width to the incoming
            // max, and a widthIn placed after that has nothing left to constrain.
            .widthIn(max = 480.dp)
            .then(
                if (activePanel == ToolbarPanel.None && !isSelectionMode && !isSelectionClosed) {
                    Modifier.wrapContentWidth()
                } else {
                    Modifier.fillMaxWidth()
                }
            )
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
                        if (totalDrag > 80f) onActivePanelChange(ToolbarPanel.None)
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
                        onClick = { onActivePanelChange(if (activePanel == ToolbarPanel.Brush) ToolbarPanel.None else ToolbarPanel.Brush) },
                        icon = Icons.Rounded.Brush,
                        contentDescription = "Brush presets"
                    )

                    // Specialized Color Picker Button
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .semantics { this.selected = activePanel == ToolbarPanel.Color }
                            .clickable(role = Role.Button) { onActivePanelChange(if (activePanel == ToolbarPanel.Color) ToolbarPanel.None else ToolbarPanel.Color) },
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
                    onClick = { onActivePanelChange(if (activePanel == ToolbarPanel.Settings) ToolbarPanel.None else ToolbarPanel.Settings) },
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
                    onActivePanelChange(ToolbarPanel.None)
                    onOpenBrushStudio()
                })
            }

            // Color Picker Panel - Smooth Slide
            AnimatedVisibility(
                visible = activePanel == ToolbarPanel.Color,
                enter = visibilityAnimSpecEnter,
                exit = visibilityAnimSpecExit
            ) {
                ColorPickerContent(viewModel) { onActivePanelChange(ToolbarPanel.None) }
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

    Column(modifier = Modifier.animateContentSize(animationSpec = MotionTokens.panelTransition)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
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

@Composable
fun ExtraToolItem(
    label: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    // Long-press pins this tool to the quick-access satellite. Pinning lives here, where the
    // entries are labelled and have room, rather than on the satellite itself - a press-and-
    // hold there would collide with the drag idiom the other two satellites teach.
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    // Last so the trailing lambda at the call sites still binds to it.
    onClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            // The Text below is the accessible name, so the icon stays decorative.
            ToolToggleButton(
                selected = selected,
                onClick = onClick,
                icon = icon,
                contentDescription = null,
                onLongClick = onTogglePin?.let {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                }
            )
            if (isPinned) {
                Icon(
                    Icons.Rounded.PushPin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).size(12.dp)
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
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
@OptIn(ExperimentalFoundationApi::class)
fun ToolToggleButton(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    // Null only where a visible text label already names the button (see ExtraToolItem);
    // otherwise this is the button's only name for screen readers.
    contentDescription: String?,
    selectedColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    onLongClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(MaterialTheme.shapes.medium)
            // Exposed as a selectable button so TalkBack announces the open/closed state
            // of the panel this toggle controls, not just its name.
            .semantics { this.selected = selected }
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(onClick = onClick, role = Role.Button)
                } else {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                        role = Role.Button
                    )
                }
            ),
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
    val customBrushes by remember(viewModel) { viewModel.uiState.map { it.customBrushes }.distinctUntilChanged() }.collectAsState(emptyList())
    var showNewPresetDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    val folders by remember(viewModel) { viewModel.uiState.map { it.brushFolders }.distinctUntilChanged() }.collectAsState(emptyList())
    val selectedBrushId by remember(viewModel) { viewModel.uiState.map { it.selectedCustomBrushId }.distinctUntilChanged() }.collectAsState(null)
    val selectedBrush = customBrushes.find { it.id == selectedBrushId }

    // Shared by the header button and each row's long-press menu. Selecting first is what makes
    // the studio open *on* this preset rather than on whatever was in hand.
    val onEditBrush: (BrushConfig) -> Unit = { brush ->
        viewModel.selectCustomBrush(brush)
        onOpenStudio()
    }
    var brushToDelete by remember { mutableStateOf<BrushConfig?>(null) }

    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Size/Softness/Opacity/Flow sliders live on the FAB's right satellite gate
            // (see HoverDrawButton). There is no button into the studio here any more: the
            // studio is where you edit *a preset*, so it is reached from the preset you want
            // to work on rather than from a heading that names none of them.
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
                // Icon-only: the row is three short actions and the labels were costing more
                // width than they explained. Each keeps its spoken name for screen readers.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showNewFolderDialog = true }) {
                        Icon(
                            Icons.Rounded.CreateNewFolder,
                            "New folder",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    // Both act on the preset selected in the list below, so they are absent
                    // rather than greyed out when there is none: a disabled control asks the
                    // user to work out why, an absent one poses no question at all.
                    AnimatedVisibility(
                        visible = selectedBrush != null,
                        enter = fadeIn(MotionTokens.expressiveEnter) + expandHorizontally(MotionTokens.panelTransition),
                        exit = fadeOut(MotionTokens.expressiveExit) + shrinkHorizontally(MotionTokens.panelTransition)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selectedBrush?.let(onEditBrush) }) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    "Edit selected preset in studio",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { brushToDelete = selectedBrush }) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    "Delete selected preset",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    // Captures whatever the brush is set to right now, so a brush arrived at by
                    // feel with the satellite gate can be kept without a detour through the studio.
                    IconButton(onClick = { showNewPresetDialog = true }) {
                        Icon(
                            Icons.Rounded.Add,
                            "New preset",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            PresetsListContent(viewModel = viewModel)
        }
    }

    if (showNewPresetDialog) {
        NamePromptDialog(
            title = "New preset",
            initial = "",
            confirmLabel = "Save",
            takenNames = customBrushes.map { it.name },
            onDismiss = { showNewPresetDialog = false },
            onConfirm = { name ->
                viewModel.saveCurrentAsCustomBrush(name)
                showNewPresetDialog = false
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

    if (showNewFolderDialog) {
        NamePromptDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            takenNames = folders.map { it.name },
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                viewModel.createBrushFolder(name)
                showNewFolderDialog = false
            }
        )
    }
}


@Composable
fun PresetsListContent(viewModel: DrawingViewModel) {
    val customBrushes by remember(viewModel) { viewModel.uiState.map { it.customBrushes }.distinctUntilChanged() }.collectAsState(emptyList())
    val folders by remember(viewModel) { viewModel.uiState.map { it.brushFolders }.distinctUntilChanged() }.collectAsState(emptyList())
    val selectedCustomBrushId by remember(viewModel) { viewModel.uiState.map { it.selectedCustomBrushId }.distinctUntilChanged() }.collectAsState(null)

    var folderToRename by remember { mutableStateOf<BrushFolder?>(null) }
    var folderToDelete by remember { mutableStateOf<BrushFolder?>(null) }
    // Collapsed by id, so a folder that disappears takes its entry with it and a new folder
    // starts open rather than inheriting a stale collapsed flag from a recycled position.
    val collapsed = remember { mutableStateListOf<Long>() }

    val loose = customBrushes.filter { it.folderId == null }

    Card(
        // Rows are taller now that each one carries a full-width stroke, so the cap is raised
        // to keep three and a bit of them in view - the part-row being the cue that it scrolls.
        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        if (customBrushes.isEmpty() && folders.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("No saved brushes", style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                folders.forEach { folder ->
                    val contents = customBrushes.filter { it.folderId == folder.id }
                    val isCollapsed = collapsed.contains(folder.id)
                    FolderHeaderRow(
                        folder = folder,
                        count = contents.size,
                        collapsed = isCollapsed,
                        onToggle = {
                            if (isCollapsed) collapsed.remove(folder.id) else collapsed.add(folder.id)
                        },
                        onRename = { folderToRename = folder },
                        onDelete = { folderToDelete = folder }
                    )
                    // animateContentSize, not AnimatedVisibility: this Column has no spacing to
                    // leave behind, and animating the wrapper keeps the rows below sliding
                    // rather than jumping when a folder opens.
                    Column(Modifier.animateContentSize(animationSpec = MotionTokens.panelTransition)) {
                        if (!isCollapsed) {
                            if (contents.isEmpty()) {
                                Text(
                                    "Empty",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 28.dp, top = 4.dp, bottom = 8.dp)
                                )
                            }
                            contents.forEach { brush ->
                                PresetRow(
                                    viewModel = viewModel,
                                    brush = brush,
                                    isActive = brush.id == selectedCustomBrushId,
                                    indented = true,
                                    onSelect = { viewModel.selectCustomBrush(brush) }
                                )
                            }
                        }
                    }
                }

                // Presets outside any folder come last: folders are the structure, and these
                // are what has not been filed yet.
                if (loose.isNotEmpty() && folders.isNotEmpty()) {
                    Text(
                        "Unfiled",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp)
                    )
                }
                loose.forEach { brush ->
                    PresetRow(
                        viewModel = viewModel,
                        brush = brush,
                        isActive = brush.id == selectedCustomBrushId,
                        indented = false,
                        onSelect = { viewModel.selectCustomBrush(brush) }
                    )
                }
            }
        }
    }

    folderToRename?.let { folder ->
        NamePromptDialog(
            title = "Rename folder",
            initial = folder.name,
            confirmLabel = "Rename",
            takenNames = folders.filter { it.id != folder.id }.map { it.name },
            onDismiss = { folderToRename = null },
            onConfirm = {
                viewModel.renameBrushFolder(folder, it)
                folderToRename = null
            }
        )
    }

    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text("Delete folder") },
            text = { Text("'${folder.name}' will be removed. The presets inside it are kept and move back out of any folder.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBrushFolder(folder)
                        folderToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text("Cancel") } }
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
        // Rows appear and disappear here as tools change while the panel is already open.
        // AnimatedVisibility only animates the panel's own show/hide, so without this the
        // Lazy Radius and Tolerance rows would pop in with no transition.
        Column(
            modifier = Modifier.animateContentSize(animationSpec = MotionTokens.panelTransition),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                Text(valueLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            // No height constraint: Slider's own 48dp box is the thumb's touch target,
            // and clamping it to 24dp made the thumb hard to grab vertically.
            Slider(value = value, onValueChange = onValueChange, valueRange = range)
        }
    }
}

/**
 * One saved preset: the stroke itself, with its name underneath.
 *
 * Tapping only ever selects. Editing and deleting moved to the panel header, where they appear
 * once something is selected - a long-press menu here meant the two most useful actions on a
 * preset were invisible until you happened to try holding one.
 */
@Composable
private fun PresetRow(
    viewModel: DrawingViewModel,
    brush: BrushConfig,
    isActive: Boolean,
    indented: Boolean,
    onSelect: () -> Unit
) {
    val density = LocalDensity.current
    val strokeHeight = 48.dp
    val strokeColor = MaterialTheme.colorScheme.onSurface

    // Re-renders once a custom tip or texture finishes decoding, not on every recomposition -
    // this walks the real stamp engine and is not a cheap draw.
    val assetsVersion by remember(viewModel) {
        viewModel.uiState.map { it.brushAssetsVersion }.distinctUntilChanged()
    }.collectAsState(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .background(if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .padding(start = if (indented) 24.dp else 10.dp, end = 10.dp)
            .padding(vertical = 6.dp)
            .semantics { selected = isActive }
    ) {
        // The stroke is rendered at the width it will actually occupy, so it reads as a real
        // mark rather than a scaled-down sample.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val widthPx = constraints.maxWidth
            val heightPx = with(density) { strokeHeight.roundToPx() }
            val thumbnail = remember(brush, strokeColor, assetsVersion, widthPx) {
                viewModel.renderPresetPreview(brush, strokeColor, widthPx, heightPx)
            }
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(strokeHeight)
                    .clip(MaterialTheme.shapes.extraSmall)
            )
        }
        Text(
            brush.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        modifier = Modifier.padding(horizontal = 8.dp),
        color = if (isActive) Color.Transparent else DividerDefaults.color
    )
}

/** Folder title bar: tap to fold, overflow to rename or remove. */
@Composable
private fun FolderHeaderRow(
    folder: BrushFolder,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val chevronTurn by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        animationSpec = MotionTokens.pulse,
        label = "folderChevron"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.KeyboardArrowDown,
            contentDescription = if (collapsed) "Expand folder" else "Collapse folder",
            modifier = Modifier.size(18.dp).rotate(chevronTurn),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            folder.name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Rounded.MoreVert, "Folder actions", modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = { showMenu = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { showMenu = false; onDelete() }
                )
            }
        }
    }
}

/** Shared name prompt: rejects blanks and names already in use, case-insensitively. */
@Composable
private fun NamePromptDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    takenNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    val trimmed = name.trim()
    val clashes = takenNames.any { it.equals(trimmed, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = clashes,
                    shape = MaterialTheme.shapes.medium
                )
                if (clashes) {
                    Text(
                        "That name is already in use",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty() && !clashes
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
