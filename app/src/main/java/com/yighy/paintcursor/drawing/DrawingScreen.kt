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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.yighy.paintcursor.ui.theme.MotionTokens
import com.yighy.paintcursor.data.LayerEntity
import com.yighy.paintcursor.data.PreferenceManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawingScreen(
    viewModel: DrawingViewModel,
    onBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    preferenceManager: PreferenceManager
) {
    val fabPos by preferenceManager.fabPosition.collectAsState(initial = 40f to 300f)
    val fabSizeSetting by preferenceManager.fabSize.collectAsState(initial = 56f)
    val context = LocalContext.current
    
    var showBrushStudio by remember { mutableStateOf(false) }
    var showLayersPanel by remember { mutableStateOf(false) }
    var editingLayerId by remember { mutableStateOf<Long?>(null) }
    // Lives here rather than inside the toolbar because the tool menu below also needs to
    // open panels, and a tap is the only reliable trigger for that.
    var activePanel by remember { mutableStateOf(ToolbarPanel.None) }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.setReferenceImage(context, it.toString()) }
    }

    val layerImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importImageAsLayer(context, it.toString()) }
    }

    // Layer saves are write-behind (batched); flush when the app goes to background so a
    // process kill can't lose the last strokes
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.flushPendingSaves()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var offsetX by remember { mutableFloatStateOf(fabPos.first) }
    var offsetY by remember { mutableFloatStateOf(fabPos.second) }

    LaunchedEffect(fabPos) {
        offsetX = fabPos.first
        offsetY = fabPos.second
    }

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .onSizeChanged { viewportSize = it }
        ) {
            // Drawing area fills the whole screen
            DrawingCanvas(viewModel = viewModel, modifier = Modifier.fillMaxSize())

            // Reference Image Layer
            ReferenceImageOverlay(viewModel, viewportSize)

            // Bottom Toolbar - Animated appearance
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 32.dp)
                    .padding(bottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding())
                    .fillMaxWidth(0.95f),
                // The toolbar sizes itself now (it hugs its row when collapsed), so it has
                // to be centred here rather than left to sit at the start edge.
                contentAlignment = Alignment.BottomCenter
            ) {
                DrawingToolbar(
                    viewModel = viewModel,
                    onOpenBrushStudio = { showBrushStudio = true },
                    activePanel = activePanel,
                    onActivePanelChange = { activePanel = it }
                )
            }
            
            // Back Button - Styled EXACTLY the same as top right actions
            Surface(
                modifier = Modifier
                    .padding(16.dp)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                    .align(Alignment.TopStart),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            }

            // Top Right Actions & Layers Panel
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                    .align(Alignment.TopEnd)
            ) {
                LayersAndActionsSection(
                    viewModel = viewModel,
                    showLayersPanel = showLayersPanel,
                    onToggleLayers = { showLayersPanel = !showLayersPanel },
                    onSelectLayer = { editingLayerId = null },
                    onEditLayer = { editingLayerId = it },
                    onNavigateToSettings = onNavigateToSettings,
                    onRequestSettingsPanel = { activePanel = ToolbarPanel.Settings },
                    imagePickerLauncher = imagePickerLauncher,
                    layerImportLauncher = layerImportLauncher
                )
            }

            // Layer Edit Overlay - Compact Side Panel
            // Keep the last id so the exit animation can still render the panel
            var lastEditingLayerId by remember { mutableStateOf<Long?>(null) }
            if (editingLayerId != null) lastEditingLayerId = editingLayerId

            // Slide only (no fade): keeps the elevation shadow alive during the animation
            AnimatedVisibility(
                visible = editingLayerId != null,
                enter = slideInHorizontally(animationSpec = MotionTokens.slideEnter, initialOffsetX = { it }),
                exit = slideOutHorizontally(animationSpec = MotionTokens.slideExit, targetOffsetX = { it })
            ) {
                // Invisible scrim: a tap anywhere outside the panel dismisses it
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { editingLayerId = null } }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 90.dp) // Offset to stay clear of the layers panel
                            .padding(top = 100.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        lastEditingLayerId?.let { id ->
                            LayerOptionsPanel(
                                viewModel = viewModel,
                                editingLayerId = id,
                                onDismiss = { editingLayerId = null }
                            )
                        }
                    }

                    // While animating out, swallow all taps so the panel buttons can't be hit
                    if (editingLayerId == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) { detectTapGestures { } }
                        )
                    }
                }
            }
            
            // Hover/Draw Button
            HoverDrawButton(
                viewModel = viewModel,
                fabSizeSetting = fabSizeSetting,
                initialOffsetX = offsetX,
                initialOffsetY = offsetY,
                onPositionChanged = { x, y -> 
                    offsetX = x
                    offsetY = y
                }
            )
        }

        if (showBrushStudio) {
            AdvancedBrushStudioWrapper(viewModel, onDismiss = { showBrushStudio = false })
        }
    }
}

@Composable
fun LayersAndActionsSection(
    viewModel: DrawingViewModel,
    showLayersPanel: Boolean,
    onToggleLayers: () -> Unit,
    onSelectLayer: (Long) -> Unit,
    onEditLayer: (Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    /** Opens the toolbar's Settings panel, for tools whose options live there. */
    onRequestSettingsPanel: () -> Unit,
    imagePickerLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    layerImportLauncher: androidx.activity.result.ActivityResultLauncher<String>
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showImportOptions by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(showMenu) {
        if (!showMenu) showImportOptions = false
    }
    
    val layers by remember(viewModel) { viewModel.uiState.map { it.layers }.distinctUntilChanged() }.collectAsState(emptyList())
    val activeLayerId by remember(viewModel) { viewModel.uiState.map { it.activeLayerId }.distinctUntilChanged() }.collectAsState(-1L)
    val layerBitmaps by remember(viewModel) { viewModel.uiState.map { it.layerBitmaps }.distinctUntilChanged() }.collectAsState(emptyMap())
    val renderVersion by remember(viewModel) { viewModel.uiState.map { it.renderVersion }.distinctUntilChanged() }.collectAsState(0)
    val drawingMode by remember(viewModel) { viewModel.uiState.map { it.drawingMode }.distinctUntilChanged() }.collectAsState(DrawingMode.Freehand)
    val isLazyModeActive by remember(viewModel) { viewModel.uiState.map { it.isLazyModeActive }.distinctUntilChanged() }.collectAsState(false)

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Expressive Grouped Container
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Extra-tools gate (moved from the bottom toolbar to free up its space) with
                // Fit-to-Screen folded in as a menu item, taking the slot the standalone
                // Fullscreen button used to occupy
                ToolsMenuButton(
                    viewModel = viewModel,
                    drawingMode = drawingMode,
                    isLazyModeActive = isLazyModeActive,
                    onRequestSettingsPanel = onRequestSettingsPanel
                )

                // Vertical Separator
                Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))

                IconButton(
                    onClick = onToggleLayers,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (showLayersPanel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        contentColor = if (showLayersPanel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(Icons.Rounded.Layers, contentDescription = "Layers")
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = showMenu, 
                        onDismissRequest = { showMenu = false },
                        shape = MaterialTheme.shapes.large,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        DropdownMenuItem(
                            text = { Text("Save Project") }, 
                            onClick = { viewModel.manualSave(); showMenu = false }, 
                            leadingIcon = { Icon(Icons.Rounded.Save, null, tint = MaterialTheme.colorScheme.primary) }
                        )
                        DropdownMenuItem(
                            text = { Text("Import Image") },
                            onClick = { showImportOptions = !showImportOptions },
                            leadingIcon = { Icon(Icons.Rounded.AddPhotoAlternate, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingIcon = { Icon(if (showImportOptions) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) }
                        )
                        if (showImportOptions) {
                            DropdownMenuItem(
                                text = { Text("Reference Image") },
                                onClick = { imagePickerLauncher.launch("image/*"); showMenu = false },
                                leadingIcon = { Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                modifier = Modifier.padding(start = 16.dp)
                            )
                            DropdownMenuItem(
                                text = { Text("As New Layer") },
                                onClick = { layerImportLauncher.launch("image/*"); showMenu = false },
                                leadingIcon = { Icon(Icons.Rounded.Layers, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Export as PNG") }, 
                            onClick = { viewModel.exportProject(context, "png"); showMenu = false }, 
                            leadingIcon = { Icon(Icons.Rounded.IosShare, null, tint = MaterialTheme.colorScheme.primary) }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { onNavigateToSettings(); showMenu = false },
                            leadingIcon = { Icon(Icons.Rounded.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        )
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = { showAboutDialog = true; showMenu = false },
                            leadingIcon = { Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        )
                    }
                }
            }
        }

        if (showAboutDialog) {
            AboutDialog(onDismiss = { showAboutDialog = false })
        }

        // Slide only, no fade: the fade forces an alpha compositing layer which drops the
        // elevation shadow during the whole animation (it would pop in/out abruptly).
        // Offset is 2x the panel width: the panel sits 16dp from the screen edge (plus
        // shadow), so sliding by its own width alone leaves a sliver that then vanishes.
        AnimatedVisibility(
            visible = showLayersPanel,
            enter = slideInHorizontally(animationSpec = MotionTokens.slideEnter, initialOffsetX = { it * 2 }),
            exit = slideOutHorizontally(animationSpec = MotionTokens.slideExit, targetOffsetX = { it * 2 })
        ) {
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
    onRequestSettingsPanel: () -> Unit
) {
    var showTools by remember { mutableStateOf(false) }
    val pinnedTools by remember(viewModel) { viewModel.uiState.map { it.pinnedTools }.distinctUntilChanged() }.collectAsState(emptyList())
    val isBucketFill = drawingMode is DrawingMode.BucketFill
    val isSelectionMode = drawingMode.isSelectionTool()
    val isActive = showTools || isBucketFill || isLazyModeActive || isSelectionMode || drawingMode is DrawingMode.Gradient

    Box {
        IconButton(
            onClick = { showTools = true },
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
        ) {
            // A fixed glyph: this button used to morph into whichever extra tool was on, which
            // made the one permanent entry point to the tools menu look like a different
            // control depending on state. The tinted container still says a tool is active.
            Icon(Icons.Rounded.Architecture, contentDescription = "Tools")
        }
        DropdownMenu(
            expanded = showTools,
            onDismissRequest = { showTools = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            // Tools grouped by function: view first, then paint tools, then selection tools
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("View", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ExtraToolItem("Fit Screen", false, Icons.Rounded.Fullscreen) {
                        viewModel.requestFitToScreen()
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
                        // Both of these toggle, so read the outcome before calling: turning a
                        // tool off must not pop open the panel holding its options.
                        val turningOn = !isBucketFill
                        viewModel.setBucketFillMode()
                        if (turningOn) onRequestSettingsPanel()
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
                        "Lazy", isLazyModeActive, Icons.Rounded.Stream,
                        isPinned = pinnedTools.contains(PinnableTool.Lazy),
                        onTogglePin = { viewModel.togglePinnedTool(PinnableTool.Lazy) }
                    ) {
                        val turningOn = !isLazyModeActive
                        viewModel.toggleLazyMode()
                        if (turningOn) onRequestSettingsPanel()
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

private const val GITHUB_URL = "https://github.com/yighy/paintcursor"

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Paint Cursor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                versionName?.let {
                    Text("Version $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(GITHUB_URL))
                            context.startActivity(intent)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "View on GitHub",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

