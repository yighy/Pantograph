package com.yighy.paintcursor.drawing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
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
import com.yighy.paintcursor.ui.theme.MotionTokens
import coil.compose.AsyncImage
import com.yighy.paintcursor.data.LayerEntity
import com.yighy.paintcursor.data.PreferenceManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

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
    val isPenDown by remember(viewModel) { viewModel.uiState.map { it.isPenDown }.distinctUntilChanged() }.collectAsState(false)
    val drawingMode by remember(viewModel) { viewModel.uiState.map { it.drawingMode }.distinctUntilChanged() }.collectAsState(DrawingMode.Freehand)

    // Live values for all four brush-gate params, so the bubble row can show every level
    // at a glance, not just the one currently being dragged (see BrushGatePanel)
    val gateSize by remember(viewModel) { viewModel.uiState.map { it.selectedWidth }.distinctUntilChanged() }.collectAsState(20f)
    val gateSoftness by remember(viewModel) { viewModel.uiState.map { it.brushSoftness }.distinctUntilChanged() }.collectAsState(0f)
    val gateOpacity by remember(viewModel) { viewModel.uiState.map { it.brushOpacity }.distinctUntilChanged() }.collectAsState(1f)
    val gateFlow by remember(viewModel) { viewModel.uiState.map { it.brushFlow }.distinctUntilChanged() }.collectAsState(1f)

    val scale by animateFloatAsState(
        targetValue = if (isPenDown) 1.15f else 1f,
        animationSpec = MotionTokens.pulse
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        val fabSizePx = with(density) { fabSizeSetting.dp.toPx() }

        var localX by remember { mutableFloatStateOf(initialOffsetX) }
        var localY by remember { mutableFloatStateOf(initialOffsetY) }

        LaunchedEffect(initialOffsetX, initialOffsetY) {
            localX = initialOffsetX
            localY = initialOffsetY
        }

        LaunchedEffect(screenWidth, screenHeight, fabSizePx) {
            val maxX = (screenWidth - fabSizePx).coerceAtLeast(0f)
            val maxY = (screenHeight - fabSizePx).coerceAtLeast(0f)
            val coercedX = localX.coerceIn(0f, maxX)
            val coercedY = localY.coerceIn(0f, maxY)
            if (coercedX != localX || coercedY != localY) {
                localX = coercedX
                localY = coercedY
                viewModel.saveFabPosition(coercedX, coercedY)
                onPositionChanged(coercedX, coercedY)
            }
        }

        Box(
            modifier = Modifier
                .offset { 
                    IntOffset(
                        localX.coerceIn(0f, (screenWidth - fabSizePx).coerceAtLeast(0f)).roundToInt(), 
                        localY.coerceIn(0f, (screenHeight - fabSizePx).coerceAtLeast(0f)).roundToInt()
                    ) 
                }
                .scale(scale)
                .pointerInput(fabDragThreshold) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        viewModel.setPenDown(true)
                        
                        var gestureMode = 0 
                        
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.find { it.id == down.id } ?: break
                            if (!change.pressed) break 
                            
                            val fingerDrag = change.position - down.position
                            val cursorDist = viewModel.getCurrentStrokeDistance()
                            
                            if (gestureMode == 0) {
                                if (fingerDrag.getDistance() > fabDragThreshold) {
                                    if (cursorDist < 10.dp.toPx()) {
                                        gestureMode = 1 
                                        viewModel.setPenDown(false)
                                        // Only abort stroke if we were actually drawing (not picking color or selecting)
                                        val isSelectionMode = drawingMode.isSelectionTool()
                                        if (!isEyeDropperMode && !isSelectionMode) {
                                            viewModel.abortCurrentStroke()
                                        }
                                    } else {
                                        gestureMode = 2 
                                    }
                                } else if (cursorDist > 5.dp.toPx()) {
                                    gestureMode = 2 
                                }
                            }
                            
                            if (gestureMode == 1) {
                                change.consume()
                                localX = (localX + (change.position.x - change.previousPosition.x)).coerceIn(0f, screenWidth - fabSizePx)
                                localY = (localY + (change.position.y - change.previousPosition.y)).coerceIn(0f, screenHeight - fabSizePx)
                                onPositionChanged(localX, localY)
                            }
                        }
                        
                        if (gestureMode == 1) viewModel.saveFabPosition(localX, localY)
                        viewModel.setPenDown(false)
                    }
                }
                .size(fabSizeSetting.dp)
        ) {
            FloatingActionButton(
                onClick = { },
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(28), // Percentage based shape for expressive look at all sizes
                containerColor = when {
                    isEyeDropperMode -> MaterialTheme.colorScheme.secondaryContainer
                    isPenDown -> MaterialTheme.colorScheme.errorContainer 
                    else -> MaterialTheme.colorScheme.tertiaryContainer
                },
                contentColor = when {
                    isEyeDropperMode -> MaterialTheme.colorScheme.onSecondaryContainer
                    isPenDown -> MaterialTheme.colorScheme.onErrorContainer 
                    else -> MaterialTheme.colorScheme.onTertiaryContainer
                }
            ) {
                val iconSize = (fabSizeSetting * 0.45f).dp
                AnimatedContent(
                    targetState = when {
                        isEyeDropperMode -> Icons.Rounded.Colorize
                        drawingMode is DrawingMode.BucketFill -> Icons.Rounded.FormatColorFill
                        isPenDown -> Icons.Default.Edit 
                        else -> Icons.Default.TouchApp
                    },
                    transitionSpec = {
                        scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut()
                    }
                ) { icon ->
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
        }

        // ==================== Satellite gear buttons ====================
        // Two slim rectangular buttons hugging the FAB, driven hold-and-drag like a gear
        // stick: right bar (FAB height) picks a brush param horizontally and its level
        // vertically; bottom bar (FAB width) is a 2x2 gate - top row freehand/line,
        // bottom row eraser off/on.
        val miniThicknessDp = (fabSizeSetting * 0.42f).coerceIn(26f, 40f)
        val miniThicknessPx = with(density) { miniThicknessDp.dp.toPx() }
        val gapPx = with(density) { 6.dp.toPx() }
        val satShape = RoundedCornerShape(30)

        val fabX = localX.coerceIn(0f, (screenWidth - fabSizePx).coerceAtLeast(0f))
        val fabY = localY.coerceIn(0f, (screenHeight - fabSizePx).coerceAtLeast(0f))

        // Satellites flip to the opposite side when the FAB touches a screen edge
        val rightSatX = if (fabX + fabSizePx + gapPx + miniThicknessPx <= screenWidth) fabX + fabSizePx + gapPx else fabX - gapPx - miniThicknessPx
        val rightSatY = fabY
        val bottomSatX = fabX
        val bottomSatY = if (fabY + fabSizePx + gapPx + miniThicknessPx <= screenHeight) fabY + fabSizePx + gapPx else fabY - gapPx - miniThicknessPx

        // ---- Right satellite: brush settings gate ----
        var brushGateActive by remember { mutableStateOf(false) }
        var brushGateParam by remember { mutableIntStateOf(0) }
        var brushGateValue by remember { mutableFloatStateOf(0f) }

        // Springy, MD3-Expressive feedback on activation: the satellite pulses and its
        // colors ease across instead of snapping, matching the FAB's own pen-down spring
        val brushGateScale by animateFloatAsState(
            targetValue = if (brushGateActive) 1.08f else 1f,
            animationSpec = MotionTokens.pulse
        )
        val brushGateBg by animateColorAsState(
            targetValue = if (brushGateActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
            animationSpec = MotionTokens.colorTransition
        )
        val brushGateIconTint by animateColorAsState(
            targetValue = if (brushGateActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            animationSpec = MotionTokens.colorTransition
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(rightSatX.roundToInt(), rightSatY.roundToInt()) }
                .scale(brushGateScale)
                .size(miniThicknessDp.dp, fabSizeSetting.dp)
                .shadow(4.dp, satShape)
                .clip(satShape)
                .background(brushGateBg)
                .pointerInput(satelliteGateSensitivity) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val params = BrushGateParam.values()
                        val colStepPx = 56.dp.toPx()
                        val colHysteresisPx = 10.dp.toPx()
                        // Soft dead zone: no value change until the finger clears this radius
                        // from the anchor, and the value ramps up from zero past it (no jump).
                        // Sensitivity < 1 widens the dead zone and lengthens the travel needed
                        // for a full sweep (safer, coarser); > 1 does the opposite (twitchier).
                        val sensitivity = satelliteGateSensitivity.coerceIn(0.25f, 4f)
                        val deadZonePx = 12.dp.toPx() / sensitivity
                        val travelPx = 300.dp.toPx() / sensitivity
                        val startIndex = brushGateParam
                        var index = startIndex
                        // Relative column, continuous: lets the switch threshold sit at
                        // +-0.5 step + hysteresis on either side of the committed column,
                        // instead of re-triggering right at the boundary on the tiniest jitter
                        var committedRel = 0
                        var anchorY = down.position.y
                        var anchorValue = params[index].read(viewModel.uiState.value)
                        brushGateValue = anchorValue
                        brushGateActive = true
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.find { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val drag = change.position - down.position
                            val relPos = drag.x / colStepPx
                            val upperBound = committedRel + 0.5f + colHysteresisPx / colStepPx
                            val lowerBound = committedRel - 0.5f - colHysteresisPx / colStepPx
                            val newRel = when {
                                relPos > upperBound -> committedRel + 1
                                relPos < lowerBound -> committedRel - 1
                                else -> committedRel
                            }
                            val newIndex = (startIndex + newRel).coerceIn(0, params.size - 1)
                            if (newIndex != index) {
                                // Gear change: re-anchor the vertical axis on the new param's
                                // value so switching columns never jumps its level
                                index = newIndex
                                committedRel = newRel.coerceIn(-startIndex, params.size - 1 - startIndex)
                                brushGateParam = newIndex
                                anchorY = change.position.y
                                anchorValue = params[index].read(viewModel.uiState.value)
                            }
                            val p = params[index]
                            val rawDelta = anchorY - change.position.y
                            val effectiveDelta = when {
                                rawDelta > deadZonePx -> rawDelta - deadZonePx
                                rawDelta < -deadZonePx -> rawDelta + deadZonePx
                                else -> 0f
                            }
                            // Progressive response: the finger's own travel distance IS the
                            // live sensitivity control. Right after the dead zone (small
                            // movements, fine adjustments) the value barely moves; pulling
                            // further away ramps the response up to full speed. This is what
                            // made e.g. Size "climb too fast" before - a flat linear mapping
                            // makes the very first millimeters of drag move it just as fast as
                            // the rest of the sweep. Exponent >1 = slow start, fast finish;
                            // full range is still reached exactly at the same travel distance.
                            val t = (effectiveDelta / travelPx).coerceIn(-1f, 1f)
                            val shaped = sign(t) * abs(t).pow(1.8f)
                            val value = (anchorValue + shaped * (p.max - p.min))
                                .coerceIn(p.min, p.max)
                            brushGateValue = value
                            p.apply(viewModel, value)
                        }
                        brushGateActive = false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Brush,
                contentDescription = "Brush settings",
                tint = brushGateIconTint,
                modifier = Modifier.size((miniThicknessDp * 0.62f).dp)
            )
        }

        // ---- Bottom satellite: drawing-mode gate ----
        val isLineMode = drawingMode is DrawingMode.StraightLine || drawingMode is DrawingMode.StraightLineEraser
        val isEraserMode = drawingMode is DrawingMode.Eraser || drawingMode is DrawingMode.StraightLineEraser
        var modeGateActive by remember { mutableStateOf(false) }
        var modeGateCell by remember { mutableIntStateOf(-1) }

        val modeGateScale by animateFloatAsState(
            targetValue = if (modeGateActive) 1.08f else 1f,
            animationSpec = MotionTokens.pulse
        )
        val modeGateBg by animateColorAsState(
            targetValue = when {
                modeGateActive -> MaterialTheme.colorScheme.primary
                isEraserMode -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
            animationSpec = MotionTokens.colorTransition
        )
        val modeGateIconTint by animateColorAsState(
            targetValue = when {
                modeGateActive -> MaterialTheme.colorScheme.onPrimary
                isEraserMode -> MaterialTheme.colorScheme.onErrorContainer
                else -> MaterialTheme.colorScheme.onSecondaryContainer
            },
            animationSpec = MotionTokens.colorTransition
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(bottomSatX.roundToInt(), bottomSatY.roundToInt()) }
                .scale(modeGateScale)
                .size(fabSizeSetting.dp, miniThicknessDp.dp)
                .shadow(4.dp, satShape)
                .clip(satShape)
                .background(modeGateBg)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val deadZonePx = 18.dp.toPx()
                        modeGateCell = -1
                        modeGateActive = true
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.find { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val drag = change.position - down.position
                            modeGateCell = if (drag.getDistance() < deadZonePx) -1 else {
                                (if (drag.y < 0f) 0 else 2) + (if (drag.x < 0f) 0 else 1)
                            }
                        }
                        val cell = modeGateCell
                        modeGateActive = false
                        modeGateCell = -1
                        if (cell >= 0) {
                            val cur = viewModel.uiState.value.drawingMode
                            val line = cur is DrawingMode.StraightLine || cur is DrawingMode.StraightLineEraser
                            val eraser = cur is DrawingMode.Eraser || cur is DrawingMode.StraightLineEraser
                            viewModel.setDrawingMode(
                                when (cell) {
                                    0 -> if (eraser) DrawingMode.Eraser else DrawingMode.Freehand
                                    1 -> if (eraser) DrawingMode.StraightLineEraser else DrawingMode.StraightLine
                                    2 -> if (line) DrawingMode.StraightLine else DrawingMode.Freehand
                                    else -> if (line) DrawingMode.StraightLineEraser else DrawingMode.Eraser
                                }
                            )
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isEraserMode) EraserIcon else if (isLineMode) Icons.Rounded.HorizontalRule else Icons.Rounded.Gesture,
                contentDescription = "Drawing mode",
                tint = modeGateIconTint,
                modifier = Modifier.size((miniThicknessDp * 0.62f).dp)
            )
        }

        // ---- Overlays (drawn above everything, no pointer input: the satellite owns the gesture) ----
        // AnimatedVisibility must stay unconditionally in the tree (not behind an `if`) so
        // its internal transition state survives across toggles - that's what lets it play
        // a real enter animation instead of just appearing already-visible on first show.
        AnimatedVisibility(
            visible = brushGateActive,
            enter = scaleIn(MotionTokens.expressiveEnter, transformOrigin = TransformOrigin(0.5f, 0.5f)) +
                fadeIn(tween(120)),
            exit = scaleOut(MotionTokens.expressiveExit, transformOrigin = TransformOrigin(0.5f, 0.5f)) + fadeOut(tween(100))
        ) {
            BrushGatePanel(
                paramIndex = brushGateParam,
                value = brushGateValue,
                currentValues = remember(gateSize, gateSoftness, gateOpacity, gateFlow) {
                    listOf(gateSize, gateSoftness, gateOpacity, gateFlow)
                },
                anchorX = rightSatX + miniThicknessPx / 2f,
                anchorY = rightSatY,
                satHeightPx = fabSizePx,
                gapPx = gapPx,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
        }
        AnimatedVisibility(
            visible = modeGateActive,
            enter = scaleIn(MotionTokens.expressiveEnter, transformOrigin = TransformOrigin(0.5f, 0.5f)) +
                fadeIn(tween(120)),
            exit = scaleOut(MotionTokens.expressiveExit, transformOrigin = TransformOrigin(0.5f, 0.5f)) + fadeOut(tween(100))
        ) {
            ModeGatePanel(
                hoveredCell = modeGateCell,
                isLineMode = isLineMode,
                isEraserMode = isEraserMode,
                centerX = bottomSatX + fabSizePx / 2f,
                centerY = bottomSatY + miniThicknessPx / 2f,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
        }
    }
}

/** The four brush parameters reachable from the right satellite, with their slider ranges. */
private enum class BrushGateParam(val label: String, val min: Float, val max: Float) {
    Size("Size", 1f, 300f),
    Softness("Soft", 0f, 1f),
    Opacity("Opac", 0f, 1f),
    Flow("Flow", 0f, 1f);

    fun read(state: DrawingState): Float = when (this) {
        Size -> state.selectedWidth
        Softness -> state.brushSoftness
        Opacity -> state.brushOpacity
        Flow -> state.brushFlow
    }

    fun apply(viewModel: DrawingViewModel, value: Float) = when (this) {
        Size -> viewModel.selectWidth(value)
        Softness -> viewModel.setBrushSoftness(value)
        Opacity -> viewModel.setBrushOpacity(value)
        Flow -> viewModel.setBrushFlow(value)
    }

    fun format(value: Float): String =
        if (this == Size) "${value.toInt()}px" else "${(value * 100).toInt()}%"
}

/**
 * No enclosing card: each param is its own floating pill bubble, showing its label and
 * live level together (a two-line readout for all four at a glance, not just the one
 * being dragged). The outer box stays a fixed, invisible size purely so the on-screen
 * placement math below is exact regardless of how wide the bubbles render.
 */
@Composable
private fun BrushGatePanel(
    paramIndex: Int,
    value: Float,
    currentValues: List<Float>,
    anchorX: Float,
    anchorY: Float,
    satHeightPx: Float,
    gapPx: Float,
    screenWidth: Float,
    screenHeight: Float
) {
    val density = LocalDensity.current
    val panelW = 260.dp
    val panelH = 56.dp
    val panelWPx = with(density) { panelW.toPx() }
    val panelHPx = with(density) { panelH.toPx() }
    val px = (anchorX - panelWPx / 2f).coerceIn(0f, (screenWidth - panelWPx).coerceAtLeast(0f))
    val above = anchorY - panelHPx - gapPx * 2
    val py = (if (above >= 0f) above else anchorY + satHeightPx + gapPx * 2)
        .coerceIn(0f, (screenHeight - panelHPx).coerceAtLeast(0f))

    // Staggered pop-in: each bubble starts hidden and flips visible a beat after the
    // previous one, giving the row a small cascading entrance instead of popping in as
    // one flat block. Fires once per time the panel mounts (i.e. once per show).
    val bubbleVisible = remember { mutableStateListOf(false, false, false, false) }
    LaunchedEffect(Unit) {
        bubbleVisible.indices.forEach { i ->
            launch {
                delay(i * 30L)
                bubbleVisible[i] = true
            }
        }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(px.roundToInt(), py.roundToInt()) }
            .size(panelW, panelH)
            .zIndex(3f),
        contentAlignment = Alignment.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrushGateParam.values().forEachIndexed { i, p ->
                val selected = i == paramIndex
                // The dragged param uses the live in-gesture value (updates every frame);
                // the others read straight from state, always current
                val v = if (selected) value else currentValues[i]
                val bubbleColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    animationSpec = MotionTokens.colorTransition
                )
                AnimatedVisibility(
                    visible = bubbleVisible[i],
                    enter = fadeIn(tween(160)) + scaleIn(MotionTokens.expressiveEnter, initialScale = 0.55f)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = bubbleColor,
                        shadowElevation = 4.dp,
                        tonalElevation = 2.dp
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                p.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                            Text(
                                p.format(v),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * No enclosing card: each mode is its own floating pill bubble, same treatment as
 * [BrushGatePanel]. The outer box stays a fixed, invisible size purely so the on-screen
 * placement math below is exact regardless of how wide the bubbles render.
 */
@Composable
private fun ModeGatePanel(
    hoveredCell: Int,
    isLineMode: Boolean,
    isEraserMode: Boolean,
    centerX: Float,
    centerY: Float,
    screenWidth: Float,
    screenHeight: Float
) {
    val density = LocalDensity.current
    val panelW = 248.dp
    val panelH = 100.dp
    val panelWPx = with(density) { panelW.toPx() }
    val panelHPx = with(density) { panelH.toPx() }
    val px = (centerX - panelWPx / 2f).coerceIn(0f, (screenWidth - panelWPx).coerceAtLeast(0f))
    val py = (centerY - panelHPx / 2f).coerceIn(0f, (screenHeight - panelHPx).coerceAtLeast(0f))

    // Staggered pop-in, same treatment as BrushGatePanel's bubbles
    val bubbleVisible = remember { mutableStateListOf(false, false, false, false) }
    LaunchedEffect(Unit) {
        bubbleVisible.indices.forEach { i ->
            launch {
                delay(i * 30L)
                bubbleVisible[i] = true
            }
        }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(px.roundToInt(), py.roundToInt()) }
            .size(panelW, panelH)
            .zIndex(3f),
        contentAlignment = Alignment.Center
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeGateBubble("Free", Icons.Rounded.Gesture, hoveredCell == 0, active = !isLineMode, visible = bubbleVisible[0])
                ModeGateBubble("Line", Icons.Rounded.HorizontalRule, hoveredCell == 1, active = isLineMode, visible = bubbleVisible[1])
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeGateBubble("Eraser off", Icons.Rounded.Close, hoveredCell == 2, active = !isEraserMode, visible = bubbleVisible[2])
                ModeGateBubble("Eraser on", EraserIcon, hoveredCell == 3, active = isEraserMode, visible = bubbleVisible[3])
            }
        }
    }
}

@Composable
private fun ModeGateBubble(label: String, icon: ImageVector, hovered: Boolean, active: Boolean, visible: Boolean) {
    val bgColor by animateColorAsState(
        targetValue = when {
            hovered -> MaterialTheme.colorScheme.primary
            active -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = MotionTokens.colorTransition
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            hovered -> MaterialTheme.colorScheme.onPrimary
            active -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = MotionTokens.colorTransition
    )
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + scaleIn(MotionTokens.expressiveEnter, initialScale = 0.55f)
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = bgColor,
            shadowElevation = 4.dp,
            tonalElevation = 2.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (hovered) FontWeight.Bold else FontWeight.Medium,
                    color = contentColor
                )
            }
        }
    }
}

