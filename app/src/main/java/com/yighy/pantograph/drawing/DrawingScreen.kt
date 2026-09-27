package com.yighy.pantograph.drawing

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yighy.pantograph.ui.theme.MotionTokens
import com.yighy.pantograph.data.PreferenceManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class)
/**
 * How far in from the top the screen is safe to put a control.
 *
 * The status bar inset alone is not that distance. Hiding the bars for fullscreen takes it to
 * zero while the notch stays exactly where it always was, so anything measuring from the status
 * bar rides up underneath the cutout the moment fullscreen starts - which is what happened to
 * the state chips. The cutout inset does not move when the bars do.
 */
private val topSafeInsets: WindowInsets
    @Composable get() = WindowInsets.statusBars.union(WindowInsets.displayCutout)

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

    // OpenDocument so the reference survives a restart - it is stored with the project. The
    // layer import below stays on GetContent: it reads the pixels there and then and never
    // needs the uri again.
    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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

    val isFullscreen by remember(viewModel) {
        viewModel.uiState.map { it.isFullscreen }.distinctUntilChanged()
    }.collectAsState(false)

    // The chip is a small target in the corner furthest from the thumb, and in fullscreen it is
    // the only control left. Back is the universal way out of anything, so it leaves fullscreen
    // before it leaves the project - and only while there is a fullscreen to leave, so the
    // ordinary back behaviour is untouched.
    BackHandler(enabled = isFullscreen) { viewModel.toggleFullscreen() }

    val hideStatusBar by preferenceManager.hideStatusBar.collectAsState(initial = true)
    val window = (context as? android.app.Activity)?.window
    LaunchedEffect(isFullscreen, hideStatusBar, window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            ?: return@LaunchedEffect
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            // Back to whatever the setting asked for, rather than to "everything visible".
            controller.show(WindowInsetsCompat.Type.navigationBars())
            if (hideStatusBar) controller.hide(WindowInsetsCompat.Type.statusBars())
            else controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
    DisposableEffect(window) {
        onDispose {
            // Leaving the screen while stripped down would follow you to the project list.
            window?.let { WindowCompat.getInsetsController(it, it.decorView) }
                ?.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

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

            // Bottom bar: a group of buttons in each corner, the panel they open floating above.
            // Present in fullscreen as well, where it carries only what an armed tool needs and
            // disappears again with it. The same 16dp from the edges as the buttons along the
            // top, so the four corners line up.
            DrawingToolbar(
                viewModel = viewModel,
                onOpenBrushStudio = { showBrushStudio = true },
                activePanel = activePanel,
                onActivePanelChange = { activePanel = it },
                armedToolsOnly = isFullscreen,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(
                        WindowInsets.systemBars.union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                    )
                    .padding(16.dp)
            )
            
            // Back: a group of one, so it is round all the way, like the ends of the others.
            if (!isFullscreen) GroupedButton(
                GroupPosition.Only,
                onClick = onBack,
                modifier = Modifier
                    .windowInsetsPadding(topSafeInsets)
                    .padding(16.dp)
                    .align(Alignment.TopStart)
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }

            // Top Right Actions & Layers Panel
            Box(
                modifier = Modifier
                    .windowInsetsPadding(topSafeInsets)
                    .padding(16.dp)
                    .align(Alignment.TopEnd)
            ) {
                LayersAndActionsSection(
                    viewModel = viewModel,
                    isFullscreen = isFullscreen,
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
