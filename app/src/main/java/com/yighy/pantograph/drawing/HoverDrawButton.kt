package com.yighy.pantograph.drawing

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yighy.pantograph.ui.theme.MotionTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The floating button and the four satellites around it.
 *
 * This lays them out and hands each its share of the state; the button's own gesture is in
 * [DrawButton], and each satellite's - with what it shows while held - in its own file.
 */
@Composable
fun HoverDrawButton(
    viewModel: DrawingViewModel,
    fabSizeSetting: Float,
    initialOffsetX: Float,
    initialOffsetY: Float,
    onPositionChanged: (Float, Float) -> Unit
) {
    val fabDragThreshold by remember(viewModel) { viewModel.uiState.map { it.fabDragThreshold }.distinctUntilChanged() }.collectAsState(100f)
    val satelliteGateSensitivity by remember(viewModel) { viewModel.uiState.map { it.satelliteGateSensitivity }.distinctUntilChanged() }.collectAsState(1f)
    val isEyeDropperMode by remember(viewModel) { viewModel.uiState.map { it.isEyeDropperMode }.distinctUntilChanged() }.collectAsState(false)
    // Held, whether or not that means painting - see DrawingState.isPenEngaged.
    val isPenEngaged by remember(viewModel) { viewModel.uiState.map { it.isPenEngaged }.distinctUntilChanged() }.collectAsState(false)
    val drawingMode by remember(viewModel) { viewModel.uiState.map { it.drawingMode }.distinctUntilChanged() }.collectAsState(DrawingMode.Freehand)
    val gateColor by remember(viewModel) { viewModel.uiState.map { it.selectedColor }.distinctUntilChanged() }.collectAsState(Color.Black)
    val pinnedTools by remember(viewModel) { viewModel.uiState.map { it.pinnedTools }.distinctUntilChanged() }.collectAsState(emptyList())
    // Derived in the flow rather than read off uiState.value in composition, so the pill
    // actually recolours when the tool is turned on or off from anywhere else.
    val toolGateActiveFlags by remember(viewModel) {
        viewModel.uiState.map { state -> state.pinnedTools.map { it.isActive(state) } }.distinctUntilChanged()
    }.collectAsState(emptyList())

    // Scale() grows the FAB about its centre, so the pen-down pulse eats into the gap on
    // every side. The satellite spacing below is derived from this same constant rather
    // than guessed, so the two can't drift apart.
    val fabPressScale = 1.15f
    val scale by animateFloatAsState(
        targetValue = if (isPenEngaged) fabPressScale else 1f,
        animationSpec = MotionTokens.pulse
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val fabSizePx = with(density) { fabSizeSetting.dp.toPx() }

        val position = remember { FabPosition(initialOffsetX, initialOffsetY) }

        LaunchedEffect(initialOffsetX, initialOffsetY) {
            position.x = initialOffsetX
            position.y = initialOffsetY
        }

        LaunchedEffect(screenWidth, screenHeight, fabSizePx) {
            val maxX = (screenWidth - fabSizePx).coerceAtLeast(0f)
            val maxY = (screenHeight - fabSizePx).coerceAtLeast(0f)
            val coercedX = position.x.coerceIn(0f, maxX)
            val coercedY = position.y.coerceIn(0f, maxY)
            if (coercedX != position.x || coercedY != position.y) {
                position.x = coercedX
                position.y = coercedY
                viewModel.saveFabPosition(coercedX, coercedY)
                onPositionChanged(coercedX, coercedY)
            }
        }

        DrawButton(
            viewModel = viewModel,
            position = position,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            fabSizePx = fabSizePx,
            fabSizeSetting = fabSizeSetting,
            scale = scale,
            fabDragThreshold = fabDragThreshold,
            drawingMode = drawingMode,
            isEyeDropperMode = isEyeDropperMode,
            isPenEngaged = isPenEngaged,
            onPositionChanged = onPositionChanged
        )

        // ==================== Satellites ====================
        // Arcs hugging the FAB, each driven hold-and-drag like a gear stick.
        val miniThicknessDp = (fabSizeSetting * 0.42f).coerceIn(26f, 40f)
        val miniThicknessPx = with(density) { miniThicknessDp.dp.toPx() }
        // 6dp of breathing room measured from the FAB at its *pressed* size, not its resting
        // size: the pulse expands it by half the scale factor on each side, which at large
        // FAB settings was more than the whole resting gap.
        val gapPx = with(density) { 6.dp.toPx() } + fabSizePx * (fabPressScale - 1f) / 2f

        val fabX = position.x.coerceIn(0f, (screenWidth - fabSizePx).coerceAtLeast(0f))
        val fabY = position.y.coerceIn(0f, (screenHeight - fabSizePx).coerceAtLeast(0f))

        // Satellites flip away from screen edges; see SatelliteLayout for the rules and the
        // tests that pin down the corner cases.
        val placement = SatelliteLayout.place(
            fabX = fabX,
            fabY = fabY,
            fabSizePx = fabSizePx,
            miniPx = miniThicknessPx,
            gapPx = gapPx,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        // Arcs of rings around the round button. The layout decides which side each satellite
        // takes and how many rings out - an edge can move one across or queue it behind
        // another - and the arc is built for that slot.
        fun arcFor(slot: SatelliteLayout.Slot) = SatelliteArcs.arc(
            slot = slot,
            centerX = fabX + fabSizePx / 2f,
            centerY = fabY + fabSizePx / 2f,
            buttonRadius = fabSizePx / 2f,
            gapPx = gapPx,
            thicknessPx = miniThicknessPx
        )
        val levelsArc = arcFor(placement.levelsSlot)
        val modeArc = arcFor(placement.modeSlot)
        val colourArc = arcFor(placement.colourSlot)
        val toolArc = arcFor(placement.toolSlot)

        // Satellites clear out while the FAB is held: mid-stroke they are dead weight beside
        // the cursor, and a stray second finger landing on one would change the brush in the
        // middle of a line.
        //
        // Faded rather than removed from the tree. AnimatedVisibility would tear down the
        // gesture detectors on every single stroke and rebuild them on release - a coroutine
        // cancelled at the wrong moment strands the gate's "active" flag, and a detector that
        // reattaches while a finger is already down is asking for trouble. The nodes stay put
        // for the whole session; they simply refuse the gesture while hidden, so there is no
        // invisible target either.
        val satellitesVisible = !isPenEngaged
        val satelliteAlpha by animateFloatAsState(
            targetValue = if (satellitesVisible) 1f else 0f,
            animationSpec = if (satellitesVisible) MotionTokens.expressiveEnter else MotionTokens.expressiveExit,
            label = "satelliteAlpha"
        )
        val satelliteScale by animateFloatAsState(
            targetValue = if (satellitesVisible) 1f else 0.7f,
            animationSpec = if (satellitesVisible) MotionTokens.expressiveEnter else MotionTokens.expressiveExit,
            label = "satelliteScale"
        )

        val isLineMode = drawingMode is DrawingMode.StraightLine || drawingMode is DrawingMode.StraightLineEraser
        val isEraserMode = drawingMode is DrawingMode.Eraser || drawingMode is DrawingMode.StraightLineEraser

        val brushGate = remember { LevelGateState() }
        val modeGate = remember { CellGateState() }
        val colourGate = remember { ColourGateState() }
        val toolGate = remember { CellGateState() }

        BrushSatellite(
            viewModel = viewModel,
            arc = levelsArc,
            gate = brushGate,
            satelliteGateSensitivity = satelliteGateSensitivity,
            satelliteScale = satelliteScale,
            satelliteAlpha = satelliteAlpha,
            miniThicknessDp = miniThicknessDp
        )
        ModeSatellite(
            viewModel = viewModel,
            arc = modeArc,
            gate = modeGate,
            isLineMode = isLineMode,
            isEraserMode = isEraserMode,
            satelliteScale = satelliteScale,
            satelliteAlpha = satelliteAlpha,
            miniThicknessDp = miniThicknessDp
        )
        ColourSatellite(
            viewModel = viewModel,
            arc = colourArc,
            gate = colourGate,
            gateColor = gateColor,
            isEyeDropperActive = isEyeDropperMode,
            satelliteGateSensitivity = satelliteGateSensitivity,
            satelliteScale = satelliteScale,
            satelliteAlpha = satelliteAlpha,
            miniThicknessDp = miniThicknessDp
        )
        ToolSatellite(
            viewModel = viewModel,
            arc = toolArc,
            gate = toolGate,
            pinnedTools = pinnedTools,
            toolGateActiveFlags = toolGateActiveFlags,
            satelliteScale = satelliteScale,
            satelliteAlpha = satelliteAlpha,
            miniThicknessDp = miniThicknessDp
        )

        // What each satellite shows while held, after all of them so it is drawn above them.
        BrushGateOverlay(viewModel, levelsArc, brushGate, screenWidth, screenHeight)
        ModeGateOverlay(viewModel, modeArc, modeGate, isLineMode, isEraserMode, screenWidth, screenHeight)
        ColourGateOverlay(colourArc, colourGate, gateColor, isEyeDropperMode, screenWidth, screenHeight)
        ToolGateOverlay(toolArc, toolGate, pinnedTools, toolGateActiveFlags, screenWidth, screenHeight)
    }
}
