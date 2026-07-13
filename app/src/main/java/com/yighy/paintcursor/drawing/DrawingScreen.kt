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

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.setReferenceImage(context, it.toString()) }
    }

    val layerImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importImageAsLayer(context, it.toString()) }
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
                    .fillMaxWidth(0.95f)
            ) {
                DrawingToolbar(viewModel = viewModel, onOpenBrushStudio = { showBrushStudio = true })
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
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it })
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
                IconButton(onClick = { viewModel.requestFitToScreen() }) {
                    Icon(Icons.Rounded.Fullscreen, contentDescription = "Fit to Screen")
                }

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
                            text = { Text("Save Project", fontWeight = FontWeight.Medium) }, 
                            onClick = { viewModel.manualSave(); showMenu = false }, 
                            leadingIcon = { Icon(Icons.Rounded.Save, null, tint = MaterialTheme.colorScheme.primary) }
                        )
                        DropdownMenuItem(
                            text = { Text("Import Image", fontWeight = FontWeight.Medium) },
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
                            text = { Text("Export as PNG", fontWeight = FontWeight.Medium) }, 
                            onClick = { viewModel.exportProject(context, "png"); showMenu = false }, 
                            leadingIcon = { Icon(Icons.Rounded.IosShare, null, tint = MaterialTheme.colorScheme.primary) }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = { Text("Settings", fontWeight = FontWeight.Medium) },
                            onClick = { onNavigateToSettings(); showMenu = false },
                            leadingIcon = { Icon(Icons.Rounded.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        )
                        DropdownMenuItem(
                            text = { Text("About", fontWeight = FontWeight.Medium) },
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
            enter = slideInHorizontally(initialOffsetX = { it * 2 }),
            exit = slideOutHorizontally(targetOffsetX = { it * 2 })
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
        title = { Text("Paint Cursor", fontWeight = FontWeight.Bold) },
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
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

