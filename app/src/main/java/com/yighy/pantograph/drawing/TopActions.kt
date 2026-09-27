package com.yighy.pantograph.drawing

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.yighy.pantograph.ui.theme.MotionTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

@Composable
fun LayersAndActionsSection(
    viewModel: DrawingViewModel,
    /** Strips this section back to the chips, which carry the only way out of fullscreen. */
    isFullscreen: Boolean = false,
    showLayersPanel: Boolean,
    onToggleLayers: () -> Unit,
    onSelectLayer: (Long) -> Unit,
    onEditLayer: (Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    /** Opens the toolbar's Settings panel, for tools whose options live there. */
    // Array<String> because the reference picker is an OpenDocument contract: it takes a list
    // of mime types, unlike the single string GetContent expects.
    imagePickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    layerImportLauncher: androidx.activity.result.ActivityResultLauncher<String>
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showImportOptions by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showTimelapseDialog by remember { mutableStateOf(false) }
    val timelapse by viewModel.timelapseInfo.collectAsState()

    LaunchedEffect(showMenu) {
        if (!showMenu) showImportOptions = false
    }
    
    val layers by remember(viewModel) { viewModel.uiState.map { it.layers }.distinctUntilChanged() }.collectAsState(emptyList())
    val activeLayerId by remember(viewModel) { viewModel.uiState.map { it.activeLayerId }.distinctUntilChanged() }.collectAsState(-1L)
    val layerBitmaps by remember(viewModel) { viewModel.uiState.map { it.layerBitmaps }.distinctUntilChanged() }.collectAsState(emptyMap())
    val renderVersion by remember(viewModel) { viewModel.uiState.map { it.renderVersion }.distinctUntilChanged() }.collectAsState(0)
    val drawingMode by remember(viewModel) { viewModel.uiState.map { it.drawingMode }.distinctUntilChanged() }.collectAsState(DrawingMode.Freehand)
    val isLazyModeActive by remember(viewModel) { viewModel.uiState.map { it.isLazyModeActive }.distinctUntilChanged() }.collectAsState(false)
    // Derived in the flow rather than rebuilt from the fields above, so the row cannot drift
    // from what PinnableTool.isActive considers on. Only ever one or two entries: drawingMode
    // holds a single value, so every tool but Lazy excludes all the others.
    val activeTools by remember(viewModel) {
        viewModel.uiState.map { state -> PinnableTool.entries.filter { it.isActive(state) } }.distinctUntilChanged()
    }.collectAsState(emptyList())
    // A locked active layer belongs in this row for the same reason the tools do: it changes
    // what the pen does, silently, and the alternative is discovering it by drawing nothing.
    val activeLayerLocked by remember(viewModel) {
        viewModel.uiState.map { st -> st.layers.any { it.id == st.activeLayerId && it.isLocked } }
            .distinctUntilChanged()
    }.collectAsState(false)
    // The trace layer is only ever built by stamping something onto it, so its presence is the
    // same fact as there being traces to clear - no need to go reading pixels to find out.
    val hasTraces by remember(viewModel) {
        viewModel.uiState.map { st -> st.layers.any { it.isTrace } }.distinctUntilChanged()
    }.collectAsState(false)

    Column(
        horizontalAlignment = Alignment.End
        // No verticalArrangement: a collapsed AnimatedVisibility is still a slot, so spacedBy
        // would hold its gap open for a chip row that is not there. Each child below carries
        // its own top padding instead, which costs nothing while it is hidden.
    ) {
        if (!isFullscreen) ButtonGroup {
            // Extra-tools gate (moved from the bottom toolbar to free up its space) with
            // Fit-to-Screen folded in as a menu item, taking the slot the standalone
            // Fullscreen button used to occupy
            ToolsMenuButton(
                viewModel = viewModel,
                drawingMode = drawingMode,
                isLazyModeActive = isLazyModeActive,
                isFullscreen = isFullscreen
            )

            GroupedButton(
                GroupPosition.Middle,
                onClick = onToggleLayers,
                pressed = showLayersPanel,
                isToggle = true
            ) {
                Icon(Icons.Rounded.Layers, contentDescription = "Layers")
            }

            Box {
                GroupedButton(GroupPosition.Last, onClick = { showMenu = true }, pressed = showMenu) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    // Clear of the button: the buttons lost the padded container they used to
                    // sit in, and the menu would otherwise touch their bottom edge.
                    offset = DpOffset(0.dp, 8.dp),
                    shape = MaterialTheme.shapes.large,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    // Leading icons stay untinted, so they take the menu's own
                    // onSurfaceVariant. Colour here is reserved for saying "this one is
                    // destructive" (error) - accenting an ordinary action just makes the
                    // items next to it look disabled, and the accents this menu had
                    // marked no such thing: "Import Image" only opens the two entries
                    // below it, which were the grey ones.
                    DropdownMenuItem(
                        text = { Text("Save Project") },
                        onClick = { viewModel.manualSave(); showMenu = false },
                        leadingIcon = { Icon(Icons.Rounded.Save, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Import Image") },
                        onClick = { showImportOptions = !showImportOptions },
                        leadingIcon = { Icon(Icons.Rounded.AddPhotoAlternate, null) },
                        trailingIcon = {
                            // One chevron that turns over, rather than two that swap: the
                            // rotation is continuous with the rows unfolding underneath,
                            // where a swap would pop at whichever frame it happened on.
                            val chevron by animateFloatAsState(
                                targetValue = if (showImportOptions) 180f else 0f,
                                animationSpec = MotionTokens.expressiveEnter,
                                label = "importChevron"
                            )
                            Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.rotate(chevron))
                        }
                    )
                    // The two entries fold out of the row above instead of appearing
                    // whole, so it reads as one row opening rather than the menu
                    // reshuffling under the finger.
                    AnimatedVisibility(
                        visible = showImportOptions,
                        enter = fadeIn(MotionTokens.expressiveEnter) + expandVertically(MotionTokens.panelTransition),
                        exit = fadeOut(MotionTokens.expressiveExit) + shrinkVertically(MotionTokens.panelTransition)
                    ) {
                        Column {
                            DropdownMenuItem(
                                text = { Text("Reference") },
                                onClick = { imagePickerLauncher.launch(arrayOf("image/*")); showMenu = false },
                                leadingIcon = { Icon(Icons.Rounded.Image, null) },
                                modifier = Modifier.padding(start = 16.dp)
                            )
                            DropdownMenuItem(
                                text = { Text("As New Layer") },
                                onClick = { layerImportLauncher.launch("image/*"); showMenu = false },
                                leadingIcon = { Icon(Icons.Rounded.Layers, null) },
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("Export as PNG") },
                        onClick = { viewModel.exportProject(context, "png"); showMenu = false },
                        leadingIcon = { Icon(Icons.Rounded.IosShare, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Timelapse") },
                        onClick = { showTimelapseDialog = true; showMenu = false },
                        leadingIcon = { Icon(Icons.Rounded.Timelapse, null) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = { onNavigateToSettings(); showMenu = false },
                        leadingIcon = { Icon(Icons.Rounded.Settings, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("About") },
                        onClick = { showAboutDialog = true; showMenu = false },
                        leadingIcon = { Icon(Icons.Rounded.Info, null) }
                    )
                }
            }
        }

        // The tools button above only says that *something* is on; these say which, and take
        // it back off. Below the container rather than inside it, so the row is free to run
        // out into the empty canvas on its left instead of being penned into the width of
        // three icon buttons. The layers rail simply starts lower when both are showing.
        //
        // Nothing at all in plain freehand: the controls in this app live off the canvas, and
        // a strip that is empty most of the time would be chrome charging rent.
        //
        // lastTools is held over so the exit has something to animate: the content recomposes
        // while the transition is still running, so reading activeTools directly emptied the
        // row on the first frame of the close and left an empty box to collapse on its own -
        // same reason lastEditingLayerId exists further down.
        val anyChips = activeTools.isNotEmpty() || activeLayerLocked || hasTraces || timelapse.recording
        var lastTools by remember { mutableStateOf(activeTools) }
        var lastLocked by remember { mutableStateOf(false) }
        var lastTraces by remember { mutableStateOf(false) }
        var lastRecording by remember { mutableStateOf(false) }
        if (anyChips) {
            lastTools = activeTools
            lastLocked = activeLayerLocked
            lastTraces = hasTraces
            lastRecording = timelapse.recording
        }

        AnimatedVisibility(
            visible = anyChips,
            enter = fadeIn(MotionTokens.expressiveEnter) + expandVertically(MotionTokens.panelTransition),
            exit = fadeOut(MotionTokens.expressiveExit) + shrinkVertically(MotionTokens.panelTransition)
        ) {
            // FlowRow, not Row: the set of pinnable tools is meant to grow, and a plain row
            // has no answer to running out of width - it squeezes, then clips off the left.
            // Today at most two can be on at once (seven of the eight read the single
            // drawingMode, so they exclude each other; only Lazy is independent), but the
            // first tool added with a toggle of its own breaks that quietly.
            //
            // No width cap out here: the row may take the whole screen less its margins and
            // only wraps once it has actually used them. Each line packs to the right, so a
            // half-full one still hangs off the same edge as the buttons above.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                lastTools.forEach { tool ->
                    // Keyed: a chip can hold a gesture in progress, and this row reorders as
                    // tools come and go. Unkeyed, the gesture would stay with the slot and could
                    // end up driving a neighbour's value.
                    key(tool) { ToolStateChip(tool, viewModel) }
                }
                if (lastLocked) {
                    // Error colours, not the accent: the tools are things you turned on, this
                    // is something standing in your way.
                    ActiveStateChip(
                        icon = Icons.Rounded.Lock,
                        label = "Locked",
                        description = "The active layer is locked, tap to unlock it",
                        onDismiss = { viewModel.unlockActiveLayer() },
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                if (lastRecording) {
                    // Always in sight while it records: it fills storage stroke by stroke, and
                    // is otherwise easy to forget about. Red, the colour recording always is.
                    ActiveStateChip(
                        icon = Icons.Rounded.FiberManualRecord,
                        label = "REC",
                        description = "Recording a timelapse, tap to stop",
                        onDismiss = { viewModel.setTimelapseRecording(false) },
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                if (lastTraces) {
                    // Tertiary: neither a mode you armed nor something blocking you, just a
                    // surface that has filled up and can be emptied.
                    ActiveStateChip(
                        icon = Icons.Rounded.CleaningServices,
                        label = "Traces",
                        description = "Undone strokes are being kept, tap to clear them",
                        onDismiss = { viewModel.discardTraces() },
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        if (showAboutDialog) {
            AboutDialog(onDismiss = { showAboutDialog = false })
        }

        if (showTimelapseDialog) {
            TimelapseDialog(viewModel, onDismiss = { showTimelapseDialog = false })
        }

        // Slide only, no fade: the fade forces an alpha compositing layer which drops the
        // elevation shadow during the whole animation (it would pop in/out abruptly).
        // Offset is 2x the panel width: the panel sits 16dp from the screen edge (plus
        // shadow), so sliding by its own width alone leaves a sliver that then vanishes.
        AnimatedVisibility(
            visible = showLayersPanel && !isFullscreen,
            enter = slideInHorizontally(animationSpec = MotionTokens.slideEnter, initialOffsetX = { it * 2 }),
            exit = slideOutHorizontally(animationSpec = MotionTokens.slideExit, targetOffsetX = { it * 2 })
        ) {
            Box(modifier = Modifier.padding(top = 8.dp)) {
                FloatingLayersPanel(
                    layers = layers,
                    activeLayerId = activeLayerId,
                    layerBitmaps = layerBitmaps,
                    renderVersion = renderVersion,
                    onSelectLayer = {
                        viewModel.selectLayer(it)
                        onSelectLayer(it)
                    },
                    onEditLayer = { onEditLayer(it.id) },
                    onAddLayer = { viewModel.addLayer("New Layer") },
                    onReorder = { from, to -> viewModel.reorderLayers(from, to) }
                )
            }
        }
    }
}

/**
 * Extra-tools gate (Fill/Gradient/Lazy/Lasso/Rect/Wand/Color) plus Fit-to-Screen, in the
 * top-right action group. Was a separate standalone button in the bottom toolbar and a
 * separate Fullscreen button here; merged into one to free up toolbar space.
 */
@Composable
private fun ToolsMenuButton(
    viewModel: DrawingViewModel,
    drawingMode: DrawingMode,
    isLazyModeActive: Boolean,
    isFullscreen: Boolean
) {
    var showTools by remember { mutableStateOf(false) }
    val pinnedTools by remember(viewModel) { viewModel.uiState.map { it.pinnedTools }.distinctUntilChanged() }.collectAsState(emptyList())
    // Collected here rather than hoisted with its neighbours: this menu is the only thing that
    // reads it, and the chip row builds itself from PinnableTool.isActive.
    val isFineCursor by remember(viewModel) { viewModel.uiState.map { it.isFineCursor }.distinctUntilChanged() }.collectAsState(false)
    val isAnchorActive by remember(viewModel) { viewModel.uiState.map { it.isAnchorActive }.distinctUntilChanged() }.collectAsState(false)
    val isLoupeActive by remember(viewModel) { viewModel.uiState.map { it.isLoupeActive }.distinctUntilChanged() }.collectAsState(false)
    val isBucketFill = drawingMode is DrawingMode.BucketFill
    val isSelectionMode = drawingMode.isSelectionTool()
    val isActive = showTools || isBucketFill || isLazyModeActive || isAnchorActive || isFineCursor || isLoupeActive || isSelectionMode || drawingMode is DrawingMode.Gradient

    Box {
        GroupedButton(GroupPosition.First, onClick = { showTools = true }, pressed = isActive) {
            // A fixed glyph: this button used to morph into whichever extra tool was on, which
            // made the one permanent entry point to the tools menu look like a different
            // control depending on state. Its pressed look still says a tool is active.
            Icon(Icons.Rounded.Architecture, contentDescription = "Tools")
        }
        DropdownMenu(
            expanded = showTools,
            onDismissRequest = { showTools = false },
            offset = DpOffset(0.dp, 8.dp),
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            // Tools grouped by function: view first, then paint tools, then selection tools
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("View", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ExtraToolItem("Fit Screen", false, Icons.Rounded.FitScreen) {
                        viewModel.requestFitToScreen()
                        showTools = false
                    }
                    // The way back out is the chip this leaves behind, so it is pinnable like
                    // any other toggle rather than a one-way door.
                    ExtraToolItem(
                        "Fullscreen", isFullscreen, Icons.Rounded.Fullscreen,
                        isPinned = pinnedTools.contains(PinnableTool.Fullscreen),
                        onTogglePin = { viewModel.togglePinnedTool(PinnableTool.Fullscreen) }
                    ) {
                        viewModel.toggleFullscreen()
                        showTools = false
                    }
                }

                HorizontalDivider(thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

                Text("Paint", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ExtraToolItem(
                        "Fill", isBucketFill, Icons.Rounded.FormatColorFill,
                        isPinned = pinnedTools.contains(PinnableTool.Fill),
                        onTogglePin = { viewModel.togglePinnedTool(PinnableTool.Fill) }
                    ) {
                        viewModel.setBucketFillMode()
                        showTools = false
                    }
                    ExtraToolItem(
                        "Gradient", drawingMode is DrawingMode.Gradient, Icons.Rounded.Gradient,
                        isPinned = pinnedTools.contains(PinnableTool.Gradient),
                        onTogglePin = { viewModel.togglePinnedTool(PinnableTool.Gradient) }
                    ) {
                        viewModel.setDrawingMode(if (drawingMode is DrawingMode.Gradient) DrawingMode.Freehand else DrawingMode.Gradient)
                        showTools = false
                    }
                    ExtraToolItem(
                        "Path", drawingMode is DrawingMode.Path, Icons.Rounded.Timeline,
                        isPinned = pinnedTools.contains(PinnableTool.Path),
                        onTogglePin = { viewModel.togglePinnedTool(PinnableTool.Path) }
                    ) {
                        viewModel.setDrawingMode(if (drawingMode is DrawingMode.Path) DrawingMode.Freehand else DrawingMode.Path)
                        showTools = false
                    }
                }

                HorizontalDivider(thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

                // Lazy isn't a tool that paints - it steadies the cursor whichever brush is
                // in hand - so it sits apart from the paint tools rather than among them.
                Text("Guide", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ExtraToolItem(
                        "Lazy", isLazyModeActive, Icons.Rounded.Cable,
                        isPinned = pinnedTools.contains(PinnableTool.Lazy),
                        onTogglePin = { viewModel.togglePinnedTool(PinnableTool.Lazy) }
                    ) {
                        viewModel.toggleLazyMode()
                        showTools = false
                    }
                    // Beside Lazy rather than among the paint tools: neither of these paints,
                    // both steady the hand that does.
                    ExtraToolItem(
                        "Anchor", isAnchorActive, Icons.Rounded.Anchor,
                        isPinned = pinnedTools.contains(PinnableTool.Anchor),
                        onTogglePin = { viewModel.togglePinnedTool(PinnableTool.Anchor) }
                    ) {
                        viewModel.toggleAnchor()
                        showTools = false
                    }
                    ExtraToolItem(
                        "Fine", isFineCursor, Icons.Rounded.CenterFocusStrong,
                        isPinned = pinnedTools.contains(PinnableTool.Fine),
                        onTogglePin = { viewModel.togglePinnedTool(PinnableTool.Fine) }
                    ) {
                        viewModel.toggleFineCursor()
                        showTools = false
                    }
                    ExtraToolItem(
                        "Loupe", isLoupeActive, Icons.Rounded.ZoomIn,
                        isPinned = pinnedTools.contains(PinnableTool.Loupe),
                        onTogglePin = { viewModel.togglePinnedTool(PinnableTool.Loupe) }
                    ) {
                        viewModel.toggleLoupe()
                        showTools = false
                    }
                }

                HorizontalDivider(thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

                Text("Select", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ExtraToolItem(
                        "Lasso", drawingMode is DrawingMode.SelectLasso, Icons.Rounded.Polyline,
                        isPinned = pinnedTools.contains(PinnableTool.Lasso),
                        onTogglePin = { viewModel.togglePinnedTool(PinnableTool.Lasso) }
                    ) {
                        viewModel.setDrawingMode(if (drawingMode is DrawingMode.SelectLasso) DrawingMode.Freehand else DrawingMode.SelectLasso)
                        showTools = false
                    }
                    ExtraToolItem(
                        "Rect", drawingMode is DrawingMode.SelectRect, Icons.Rounded.HighlightAlt,
                        isPinned = pinnedTools.contains(PinnableTool.Rect),
                        onTogglePin = { viewModel.togglePinnedTool(PinnableTool.Rect) }
                    ) {
                        viewModel.setDrawingMode(if (drawingMode is DrawingMode.SelectRect) DrawingMode.Freehand else DrawingMode.SelectRect)
                        showTools = false
                    }
                    ExtraToolItem(
                        "Wand", drawingMode is DrawingMode.SelectWand, Icons.Rounded.AutoFixHigh,
                        isPinned = pinnedTools.contains(PinnableTool.Wand),
                        onTogglePin = { viewModel.togglePinnedTool(PinnableTool.Wand) }
                    ) {
                        viewModel.setDrawingMode(if (drawingMode is DrawingMode.SelectWand) DrawingMode.Freehand else DrawingMode.SelectWand)
                        showTools = false
                    }
                    ExtraToolItem(
                        "Color", drawingMode is DrawingMode.SelectColor, Icons.Rounded.Palette,
                        isPinned = pinnedTools.contains(PinnableTool.ColorSelect),
                        onTogglePin = { viewModel.togglePinnedTool(PinnableTool.ColorSelect) }
                    ) {
                        viewModel.setDrawingMode(if (drawingMode is DrawingMode.SelectColor) DrawingMode.Freehand else DrawingMode.SelectColor)
                        showTools = false
                    }
                }
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
