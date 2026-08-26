package com.yighy.pantograph.drawing

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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.yighy.pantograph.ui.theme.MotionTokens
import coil.compose.AsyncImage
import com.yighy.pantograph.data.LayerEntity
import com.yighy.pantograph.data.PreferenceManager
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
    onPositionChanged: (Float, Float) -> Unit,
    /** Opens the toolbar's Settings panel, for pinned tools whose own controls live there. */
    onRequestSettingsPanel: () -> Unit = {}
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
    val gateColor by remember(viewModel) { viewModel.uiState.map { it.selectedColor }.distinctUntilChanged() }.collectAsState(Color.Black)
    val isEyeDropperActive by remember(viewModel) { viewModel.uiState.map { it.isEyeDropperMode }.distinctUntilChanged() }.collectAsState(false)

    // Scale() grows the FAB about its centre, so the pen-down pulse eats into the gap on
    // every side. The satellite spacing below is derived from this same constant rather
    // than guessed, so the two can't drift apart.
    val fabPressScale = 1.15f
    val scale by animateFloatAsState(
        targetValue = if (isPenDown) fabPressScale else 1f,
        animationSpec = MotionTokens.pulse
    )

    // The satellite gates are worked blind - your own finger covers the bubbles - so every
    // threshold the gesture code already tracks (gate engaged, column changed, dead zone
    // cleared) gets a matching tick. LongPress marks committing to something, TextHandleMove
    // is the light per-step tick.
    val haptics = LocalHapticFeedback.current

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
                                        // This branch silently discards the stroke you may
                                        // have thought you were drawing, so say so.
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
            // One `when` for the glyph, its spoken name and both colours. They used to be
            // three separate whens over different case sets, which is how the selection tools
            // ended up with no representation at all and bucket fill with only half of one -
            // arm the wand and the button still looked exactly like plain drawing.
            val armed: Triple<ImageVector, String, Color>? = when {
                isEyeDropperMode ->
                    Triple(Icons.Rounded.Colorize, "Eyedropper active", MaterialTheme.colorScheme.secondaryContainer)
                drawingMode is DrawingMode.BucketFill ->
                    Triple(Icons.Rounded.FormatColorFill, "Bucket fill active", MaterialTheme.colorScheme.primaryContainer)
                drawingMode is DrawingMode.Gradient ->
                    Triple(Icons.Rounded.Gradient, "Gradient active", MaterialTheme.colorScheme.primaryContainer)
                drawingMode is DrawingMode.SelectLasso ->
                    Triple(Icons.Rounded.Polyline, "Lasso select active", MaterialTheme.colorScheme.primaryContainer)
                drawingMode is DrawingMode.SelectRect ->
                    Triple(Icons.Rounded.HighlightAlt, "Rectangle select active", MaterialTheme.colorScheme.primaryContainer)
                drawingMode is DrawingMode.SelectWand ->
                    Triple(Icons.Rounded.AutoFixHigh, "Magic wand active", MaterialTheme.colorScheme.primaryContainer)
                drawingMode is DrawingMode.SelectColor ->
                    Triple(Icons.Rounded.Palette, "Colour select active", MaterialTheme.colorScheme.primaryContainer)
                else -> null
            }
            // Pressed always reads the same whatever tool is armed, so the identity lives in
            // the glyph and the "a stroke is happening" signal stays in the colour.
            val container = when {
                isPenDown -> MaterialTheme.colorScheme.errorContainer
                armed != null -> armed.third
                else -> MaterialTheme.colorScheme.tertiaryContainer
            }
            val fabIcon = armed?.first
                ?: if (isPenDown) Icons.Default.Edit else Icons.Default.TouchApp
            val fabDescription = armed?.second
                ?: if (isPenDown) "Drawing" else "Hold to draw"

            FloatingActionButton(
                onClick = { },
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(28), // Percentage based shape for expressive look at all sizes
                containerColor = container,
                contentColor = contentColorFor(container)
            ) {
                val iconSize = (fabSizeSetting * 0.45f).dp
                // Icon and its spoken name travel together as one state, so the announced
                // label can't lag behind the glyph mid-transition.
                AnimatedContent(
                    targetState = fabIcon to fabDescription,
                    transitionSpec = {
                        scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut()
                    },
                    label = "fabIcon"
                ) { (icon, description) ->
                    Icon(
                        imageVector = icon,
                        contentDescription = description,
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
        // 6dp of breathing room measured from the FAB at its *pressed* size, not its resting
        // size: the pulse expands it by half the scale factor on each side, which at large
        // FAB settings was more than the whole resting gap.
        val gapPx = with(density) { 6.dp.toPx() } + fabSizePx * (fabPressScale - 1f) / 2f
        val satShape = RoundedCornerShape(30)

        val fabX = localX.coerceIn(0f, (screenWidth - fabSizePx).coerceAtLeast(0f))
        val fabY = localY.coerceIn(0f, (screenHeight - fabSizePx).coerceAtLeast(0f))

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
        val rightSatX = placement.levelsX
        val rightSatY = placement.levelsY
        val bottomSatX = placement.modeX
        val bottomSatY = placement.modeY
        val colourSatX = placement.colourX
        val colourSatY = placement.colourY
        val toolSatX = placement.toolX
        val toolSatY = placement.toolY
        // Turned on its side when it falls back to a flank rather than stacking.
        val toolSatWidthDp = if (placement.toolStacked) fabSizeSetting else miniThicknessDp
        val toolSatHeightDp = if (placement.toolStacked) miniThicknessDp else fabSizeSetting

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
        val satellitesVisible = !isPenDown
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

        // ---- Right satellite: brush settings gate ----
        var brushGateActive by remember { mutableStateOf(false) }
        var brushGateParam by remember { mutableIntStateOf(0) }
        var brushGateValue by remember { mutableFloatStateOf(0f) }
        // Live finger position, so the readout can ride above the hand instead of waiting
        // under it. Kept in the satellite's own coordinates and converted to screen space at
        // the call site: pointerInput's block doesn't restart when the FAB moves, so a
        // screen-space value captured in there would still be relative to the old position.
        var brushGateFingerLocalX by remember { mutableFloatStateOf(0f) }
        var brushGateFingerLocalY by remember { mutableFloatStateOf(0f) }

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

        // The drag gesture below is unreachable with a screen reader, so the same four
        // parameters are also exposed as discrete nudge actions. 10% of each parameter's own
        // range per invocation: enough to be worth the gesture, fine enough to land on a
        // usable value.
        val brushGateActions = BrushGateParam.values().flatMap { param ->
            listOf(true, false).map { increase ->
                CustomAccessibilityAction(
                    label = "${if (increase) "Increase" else "Decrease"} ${param.label.lowercase()}"
                ) {
                    val current = param.read(viewModel.uiState.value)
                    val stepSize = (param.max - param.min) / 10f
                    val next = (current + if (increase) stepSize else -stepSize)
                        .coerceIn(param.min, param.max)
                    param.apply(viewModel, next)
                    true
                }
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(rightSatX.roundToInt(), rightSatY.roundToInt()) }
                .scale(brushGateScale * satelliteScale)
                .alpha(satelliteAlpha)
                .size(miniThicknessDp.dp, fabSizeSetting.dp)
                .semantics {
                    contentDescription = "Brush levels"
                    customActions = brushGateActions
                }
                .shadow(4.dp, satShape)
                .clip(satShape)
                .background(brushGateBg)
                .pointerInput(satelliteGateSensitivity) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // Read live rather than through a captured value: this block is not
                        // rebuilt when the FAB is pressed. Hidden means inert - bail out
                        // without consuming, so the touch is nobody's business.
                        if (viewModel.uiState.value.isPenDown) return@awaitEachGesture
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
                        // Sideways anchor, clamped each event so travel past either end of the
                        // row isn't banked against the way back - the same reason the vertical
                        // axis re-anchors when its value pins against a limit.
                        var anchorX = down.position.x
                        var anchorY = down.position.y
                        var anchorValue = params[index].read(viewModel.uiState.value)
                        brushGateValue = anchorValue
                        brushGateFingerLocalX = down.position.x
                        brushGateFingerLocalY = down.position.y
                        brushGateActive = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // Tracks dead-zone state so the "value is live now" tick fires on the
                        // crossing, not on every frame beyond it.
                        var inDeadZone = true
                        // See the mode gate below: cancellation must not strand this flag.
                        try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.find { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            brushGateFingerLocalX = change.position.x
                            brushGateFingerLocalY = change.position.y
                            anchorX = GateMath.clampColumnAnchor(
                                anchorX = anchorX,
                                fingerX = change.position.x,
                                colStepPx = colStepPx,
                                minRel = -startIndex,
                                maxRel = params.size - 1 - startIndex
                            )
                            val newRel = GateMath.nextColumn(
                                committedRel, change.position.x - anchorX, colStepPx, colHysteresisPx
                            )
                            val newIndex = (startIndex + newRel).coerceIn(0, params.size - 1)
                            if (newIndex != index) {
                                // Gear change: re-anchor the vertical axis on the new param's
                                // value so switching columns never jumps its level
                                index = newIndex
                                committedRel = newRel.coerceIn(-startIndex, params.size - 1 - startIndex)
                                brushGateParam = newIndex
                                anchorY = change.position.y
                                anchorValue = params[index].read(viewModel.uiState.value)
                                // Makes the hysteresis perceptible: one detent per column.
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                inDeadZone = true
                            }
                            val p = params[index]
                            if (GateMath.effectiveDelta(anchorY - change.position.y, deadZonePx) == 0f) {
                                inDeadZone = true
                            } else if (inDeadZone) {
                                inDeadZone = false
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            val stepped = GateMath.step(
                                anchorValue = anchorValue,
                                anchorPos = anchorY,
                                currentPos = change.position.y,
                                min = p.min,
                                max = p.max,
                                deadZonePx = deadZonePx,
                                travelPx = travelPx
                            )
                            // Fed straight back in: these only move when the value is pinned
                            // against a limit, which is what keeps a reversal responsive.
                            anchorValue = stepped.anchorValue
                            anchorY = stepped.anchorPos
                            brushGateValue = stepped.value
                            p.apply(viewModel, stepped.value)
                        }
                        } finally {
                            brushGateActive = false
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Chevrons flanking the icon: a plain centred glyph reads as "tap me", which is
            // the one thing this control does not do. They sit on the pill's long axis, the
            // axis that carries the value, and dim out while the gate is engaged so they
            // don't compete with the bubbles.
            val brushHintAlpha by animateFloatAsState(
                targetValue = if (brushGateActive) 0f else 0.55f,
                animationSpec = MotionTokens.colorTransitionFloat,
                label = "brushGateHint"
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowUp,
                    contentDescription = null,
                    tint = brushGateIconTint.copy(alpha = brushHintAlpha),
                    modifier = Modifier.size((miniThicknessDp * 0.36f).dp)
                )
                // Equalizer, not Brush: the toolbar's Brush button already owns that glyph
                // for the preset list. This satellite is a live level control, and stacked
                // bars say "levels" while echoing the vertical drag that works it.
                Icon(
                    Icons.Rounded.Equalizer,
                    // Named by the enclosing Box's semantics, so the glyph stays decorative.
                    contentDescription = null,
                    tint = brushGateIconTint,
                    modifier = Modifier.size((miniThicknessDp * 0.62f).dp)
                )
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = brushGateIconTint.copy(alpha = brushHintAlpha),
                    modifier = Modifier.size((miniThicknessDp * 0.36f).dp)
                )
            }
        }

        // ---- Bottom satellite: drawing-mode gate ----
        // Name of the preset the swap cell would bring back, or null when there is none.
        // Resolved through the list rather than trusting the remembered id, so a preset deleted
        // since greys the cell out instead of leaving it offering a swap that would do nothing.
        val swapTargetName by remember(viewModel) {
            viewModel.uiState.map { state ->
                state.previousBrushId?.let { id -> state.customBrushes.find { it.id == id }?.name }
            }.distinctUntilChanged()
        }.collectAsState(null)

        val isLineMode = drawingMode is DrawingMode.StraightLine || drawingMode is DrawingMode.StraightLineEraser
        val isEraserMode = drawingMode is DrawingMode.Eraser || drawingMode is DrawingMode.StraightLineEraser
        var modeGateActive by remember { mutableStateOf(false) }
        var modeGateCell by remember { mutableIntStateOf(-1) }
        var modeGateFingerLocalX by remember { mutableFloatStateOf(0f) }
        var modeGateFingerLocalY by remember { mutableFloatStateOf(0f) }

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

        // Same story as the brush gate: the 2x2 drag has no screen-reader equivalent, so each
        // cell gets a named action. The two mode cells are toggles under the finger but are
        // offered here as outright settings, so the announced label always matches what
        // actually happens rather than depending on the state at the moment of the tap.
        val modeGateActions = listOf(
            CustomAccessibilityAction("Freehand") {
                viewModel.setDrawingMode(if (isEraserMode) DrawingMode.Eraser else DrawingMode.Freehand); true
            },
            CustomAccessibilityAction("Straight line") {
                viewModel.setDrawingMode(if (isEraserMode) DrawingMode.StraightLineEraser else DrawingMode.StraightLine); true
            },
            CustomAccessibilityAction("Eraser off") {
                viewModel.setDrawingMode(if (isLineMode) DrawingMode.StraightLine else DrawingMode.Freehand); true
            },
            CustomAccessibilityAction("Eraser on") {
                viewModel.setDrawingMode(if (isLineMode) DrawingMode.StraightLineEraser else DrawingMode.Eraser); true
            },
            CustomAccessibilityAction("Undo") { viewModel.undo(); true },
            CustomAccessibilityAction("Swap to previous brush") { viewModel.swapToPreviousBrush(); true }
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(bottomSatX.roundToInt(), bottomSatY.roundToInt()) }
                .scale(modeGateScale * satelliteScale)
                .alpha(satelliteAlpha)
                .size(fabSizeSetting.dp, miniThicknessDp.dp)
                .semantics {
                    contentDescription = when {
                        isEraserMode && isLineMode -> "Drawing mode: straight line eraser"
                        isEraserMode -> "Drawing mode: eraser"
                        isLineMode -> "Drawing mode: straight line"
                        else -> "Drawing mode: freehand"
                    }
                    customActions = modeGateActions
                }
                .shadow(4.dp, satShape)
                .clip(satShape)
                .background(modeGateBg)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (viewModel.uiState.value.isPenDown) return@awaitEachGesture
                        down.consume()
                        val deadZonePx = 18.dp.toPx()
                        val trailPx = GateMath.ANCHOR_TRAIL_RADIUS_DP.dp.toPx()
                        var anchor = GateMath.Anchor(down.position.x, down.position.y)
                        modeGateCell = -1
                        modeGateFingerLocalX = down.position.x
                        modeGateFingerLocalY = down.position.y
                        modeGateActive = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // This coroutine is cancellable: hiding the satellites disposes
                        // the node it runs in, and a cancel between here and the reset
                        // below would strand modeGateActive at true - leaving the panel
                        // on screen with nothing holding it.
                        try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.find { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            modeGateFingerLocalX = change.position.x
                            modeGateFingerLocalY = change.position.y
                            anchor = GateMath.trailAnchor(
                                anchor.x, anchor.y,
                                change.position.x, change.position.y, trailPx
                            )
                            val newCell = GateMath.quadrant(
                                change.position.x - anchor.x,
                                change.position.y - anchor.y,
                                deadZonePx
                            )
                            // Ticks on the way back to neutral too, so you can feel that
                            // releasing here would apply nothing.
                            if (newCell != modeGateCell) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            modeGateCell = newCell
                        }
                        } finally {
                            modeGateActive = false
                        }
                        val cell = modeGateCell
                        modeGateCell = -1
                        if (cell >= 0) {
                            // Unlike the brush gate, this one only lands on release - confirm it.
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val cur = viewModel.uiState.value.drawingMode
                            val line = cur is DrawingMode.StraightLine || cur is DrawingMode.StraightLineEraser
                            val eraser = cur is DrawingMode.Eraser || cur is DrawingMode.StraightLineEraser
                            // Each cell is one toggle or action rather than one value of a pair,
                            // which is what buys the two bottom cells: shape and eraser are
                            // independent of each other, so four half-cells were spending the
                            // quadrant on two booleans.
                            when (cell) {
                                // Shape, carrying the eraser state across unchanged.
                                0 -> viewModel.setDrawingMode(
                                    if (line) {
                                        if (eraser) DrawingMode.Eraser else DrawingMode.Freehand
                                    } else {
                                        if (eraser) DrawingMode.StraightLineEraser else DrawingMode.StraightLine
                                    }
                                )
                                // Eraser, carrying the shape across unchanged.
                                1 -> viewModel.setDrawingMode(
                                    if (eraser) {
                                        if (line) DrawingMode.StraightLine else DrawingMode.Freehand
                                    } else {
                                        if (line) DrawingMode.StraightLineEraser else DrawingMode.Eraser
                                    }
                                )
                                2 -> viewModel.undo()
                                else -> viewModel.swapToPreviousBrush()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Same affordance as the brush gate, turned along this pill's own axis.
            //
            // The chevrons are auto-mirrored ones, but only because the plain variants are
            // deprecated - they mean "this pill sweeps sideways", not "back" and "forward",
            // and the drag does not reverse under RTL. Mirroring a symmetric pair swaps two
            // glyphs that are each other's reflection, so the row draws identically either
            // way. Keep them as a pair; flipping one on its own would break that.
            val modeHintAlpha by animateFloatAsState(
                targetValue = if (modeGateActive) 0f else 0.55f,
                animationSpec = MotionTokens.colorTransitionFloat,
                label = "modeGateHint"
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = modeGateIconTint.copy(alpha = modeHintAlpha),
                    modifier = Modifier.size((miniThicknessDp * 0.36f).dp)
                )
                Icon(
                    imageVector = if (isEraserMode) EraserIcon else if (isLineMode) Icons.Rounded.HorizontalRule else Icons.Rounded.Gesture,
                    // Named by the enclosing Box's semantics, so the glyph stays decorative.
                    contentDescription = null,
                    tint = modeGateIconTint,
                    modifier = Modifier.size((miniThicknessDp * 0.62f).dp)
                )
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = modeGateIconTint.copy(alpha = modeHintAlpha),
                    modifier = Modifier.size((miniThicknessDp * 0.36f).dp)
                )
            }
        }

        // ---- Top satellite: colour gate ----
        // Same gear-stick interaction as the brush levels on the right: a column per component,
        // its level on the vertical axis. The fourth column is the eyedropper, which is an
        // action rather than a level - see ColourGateParam.
        var colourGateActive by remember { mutableStateOf(false) }
        var colourGateParam by remember { mutableIntStateOf(0) }
        var colourGateValue by remember { mutableFloatStateOf(0f) }
        var colourGateFingerLocalX by remember { mutableFloatStateOf(0f) }
        var colourGateFingerLocalY by remember { mutableFloatStateOf(0f) }
        // The gate's own hue/saturation/brightness, kept across gestures. A colour cannot
        // always say what its hue was - grey has none - so this is the authority and the brush
        // colour only tops it up with what it can still express. See ColourGate.readFrom.
        var workingHsv by remember { mutableStateOf(Hsv(0f, 1f, 1f)) }

        val colourGateScale by animateFloatAsState(
            targetValue = if (colourGateActive) 1.08f else 1f,
            animationSpec = MotionTokens.pulse
        )
        val colourGateBg by animateColorAsState(
            targetValue = when {
                colourGateActive -> MaterialTheme.colorScheme.primary
                isEyeDropperActive -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
            animationSpec = MotionTokens.colorTransition
        )
        val colourGateIconTint by animateColorAsState(
            targetValue = when {
                colourGateActive -> MaterialTheme.colorScheme.onPrimary
                isEyeDropperActive -> MaterialTheme.colorScheme.onTertiaryContainer
                else -> MaterialTheme.colorScheme.onSecondaryContainer
            },
            animationSpec = MotionTokens.colorTransition
        )

        // As with the other gates, the drag is unreachable with a screen reader, so each
        // component gets discrete nudges and the eyedropper a plain toggle.
        val colourGateActions = ColourGateParam.entries.filter { it != ColourGateParam.Pipette }
            .flatMap { param ->
                listOf(true, false).map { increase ->
                    CustomAccessibilityAction(
                        label = "${if (increase) "Increase" else "Decrease"} ${param.label.lowercase()}"
                    ) {
                        val live = viewModel.uiState.value.selectedColor
                        val hsv = ColourGate.readFrom(live.red, live.green, live.blue, workingHsv)
                        val stepSize = (param.max - param.min) / 10f
                        val next = (param.read(hsv) + if (increase) stepSize else -stepSize)
                            .coerceIn(param.min, param.max)
                        val updated = param.applyTo(hsv, next)
                        workingHsv = updated
                        val rgb = ColourGate.toRgb(updated)
                        viewModel.selectColor(Color(rgb[0], rgb[1], rgb[2]))
                        true
                    }
                }
            } + CustomAccessibilityAction("Toggle eyedropper") { viewModel.toggleEyeDropper(); true }

        Box(
            modifier = Modifier
                .offset { IntOffset(colourSatX.roundToInt(), colourSatY.roundToInt()) }
                .scale(colourGateScale * satelliteScale)
                .alpha(satelliteAlpha)
                .size(fabSizeSetting.dp, miniThicknessDp.dp)
                .semantics {
                    contentDescription = if (isEyeDropperActive) "Colour, eyedropper armed" else "Colour"
                    customActions = colourGateActions
                }
                .shadow(4.dp, satShape)
                .clip(satShape)
                .background(colourGateBg)
                .pointerInput(satelliteGateSensitivity) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (viewModel.uiState.value.isPenDown) return@awaitEachGesture
                        down.consume()
                        val params = ColourGateParam.entries
                        val colStepPx = 56.dp.toPx()
                        val colHysteresisPx = 10.dp.toPx()
                        val sensitivity = satelliteGateSensitivity.coerceIn(0.25f, 4f)
                        val deadZonePx = 12.dp.toPx() / sensitivity
                        val travelPx = 300.dp.toPx() / sensitivity
                        val startIndex = colourGateParam
                        var index = startIndex
                        var committedRel = 0
                        var anchorX = down.position.x
                        var anchorY = down.position.y

                        val live = viewModel.uiState.value.selectedColor
                        var hsv = ColourGate.readFrom(live.red, live.green, live.blue, workingHsv)
                        workingHsv = hsv
                        var anchorValue = params[index].read(hsv)
                        colourGateValue = anchorValue
                        colourGateFingerLocalX = down.position.x
                        colourGateFingerLocalY = down.position.y
                        colourGateActive = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        var inDeadZone = true
                        // Only a real lift arms the eyedropper. A cancelled gesture also leaves
                        // the loop, and toggling a mode because the system took the pointer away
                        // is not something the user asked for.
                        var released = false
                        try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.find { it.id == down.id } ?: break
                            if (!change.pressed) { released = true; break }
                            change.consume()
                            colourGateFingerLocalX = change.position.x
                            colourGateFingerLocalY = change.position.y
                            anchorX = GateMath.clampColumnAnchor(
                                anchorX = anchorX,
                                fingerX = change.position.x,
                                colStepPx = colStepPx,
                                minRel = -startIndex,
                                maxRel = params.size - 1 - startIndex
                            )
                            val newRel = GateMath.nextColumn(
                                committedRel, change.position.x - anchorX, colStepPx, colHysteresisPx
                            )
                            val newIndex = (startIndex + newRel).coerceIn(0, params.size - 1)
                            if (newIndex != index) {
                                index = newIndex
                                committedRel = newRel.coerceIn(-startIndex, params.size - 1 - startIndex)
                                colourGateParam = newIndex
                                anchorY = change.position.y
                                anchorValue = params[index].read(hsv)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                inDeadZone = true
                            }
                            val p = params[index]
                            // The eyedropper column has no vertical axis to read.
                            if (p == ColourGateParam.Pipette) continue

                            if (GateMath.effectiveDelta(anchorY - change.position.y, deadZonePx) == 0f) {
                                inDeadZone = true
                            } else if (inDeadZone) {
                                inDeadZone = false
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            val stepped = GateMath.step(
                                anchorValue = anchorValue,
                                anchorPos = anchorY,
                                currentPos = change.position.y,
                                min = p.min,
                                max = p.max,
                                deadZonePx = deadZonePx,
                                travelPx = travelPx
                            )
                            anchorValue = stepped.anchorValue
                            anchorY = stepped.anchorPos
                            colourGateValue = stepped.value
                            hsv = p.applyTo(hsv, stepped.value)
                            workingHsv = hsv
                            val rgb = ColourGate.toRgb(hsv)
                            viewModel.selectColor(Color(rgb[0], rgb[1], rgb[2]))
                        }
                        if (released && params[index] == ColourGateParam.Pipette) {
                            viewModel.toggleEyeDropper()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        } finally {
                            colourGateActive = false
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val colourHintAlpha by animateFloatAsState(
                targetValue = if (colourGateActive) 0f else 0.55f,
                animationSpec = MotionTokens.colorTransitionFloat,
                label = "colourGateHint"
            )
            // Chevrons on the long axis like the levels pill, but flanking a swatch of the
            // colour itself rather than a glyph: the one thing this control is always able to
            // tell you at a glance is what colour is loaded.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = colourGateIconTint.copy(alpha = colourHintAlpha),
                    modifier = Modifier.size((miniThicknessDp * 0.36f).dp)
                )
                Box(
                    modifier = Modifier
                        .size((miniThicknessDp * 0.52f).dp)
                        .clip(CircleShape)
                        .background(gateColor)
                        .border(1.dp, colourGateIconTint.copy(alpha = 0.5f), CircleShape)
                )
                if (isEyeDropperActive) {
                    Icon(
                        Icons.Rounded.Colorize,
                        contentDescription = null,
                        tint = colourGateIconTint,
                        modifier = Modifier.size((miniThicknessDp * 0.4f).dp)
                    )
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colourGateIconTint.copy(alpha = colourHintAlpha),
                    modifier = Modifier.size((miniThicknessDp * 0.36f).dp)
                )
            }
        }

        // ---- Tool satellite: one pinned tool, one tap ----
        // Up to four pinned tools, picked with the same sideways drag the brush gate uses for
        // its four parameters - same hysteresis, same one-column-per-event rule. Releasing
        // without having moved leaves the index at 0, so a plain tap still fires the first
        // pinned tool and the simple case costs no gesture at all.
        val pinnedTools by remember(viewModel) { viewModel.uiState.map { it.pinnedTools }.distinctUntilChanged() }.collectAsState(emptyList())
        // Derived in the flow rather than read off uiState.value in composition, so the pill
        // actually recolours when the tool is turned on or off from anywhere else.
        val toolGateActiveFlags by remember(viewModel) {
            viewModel.uiState.map { state -> state.pinnedTools.map { it.isActive(state) } }.distinctUntilChanged()
        }.collectAsState(emptyList())

        var toolGateActive by remember { mutableStateOf(false) }
        var toolGateCell by remember { mutableIntStateOf(-1) }
        var toolGateFingerLocalX by remember { mutableFloatStateOf(0f) }
        var toolGateFingerLocalY by remember { mutableFloatStateOf(0f) }

        // The pill itself only reports "is any pinned tool currently on", since its glyph no
        // longer names a particular one. Which tool is which is the panel's job.
        val anyPinnedToolActive = toolGateActiveFlags.any { it }

        val toolSatScale by animateFloatAsState(
            targetValue = if (toolGateActive) 1.08f else 1f,
            animationSpec = MotionTokens.pulse,
            label = "toolSatScale"
        )
        val toolSatBg by animateColorAsState(
            targetValue = when {
                toolGateActive || anyPinnedToolActive -> MaterialTheme.colorScheme.primary
                pinnedTools.isEmpty() -> MaterialTheme.colorScheme.surfaceContainerHigh
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
            animationSpec = MotionTokens.colorTransition,
            label = "toolSatBg"
        )
        val toolSatTint by animateColorAsState(
            targetValue = when {
                toolGateActive || anyPinnedToolActive -> MaterialTheme.colorScheme.onPrimary
                pinnedTools.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSecondaryContainer
            },
            animationSpec = MotionTokens.colorTransition,
            label = "toolSatTint"
        )

        val toolGateActions = pinnedTools.map { tool ->
            CustomAccessibilityAction(tool.label) { tool.toggle(viewModel, onRequestSettingsPanel); true }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(toolSatX.roundToInt(), toolSatY.roundToInt()) }
                .scale(satelliteScale * toolSatScale)
                .alpha(satelliteAlpha)
                .size(toolSatWidthDp.dp, toolSatHeightDp.dp)
                .semantics {
                    contentDescription = pinnedTools.firstOrNull()
                        ?.let { "Quick tools, ${pinnedTools.size} pinned, first is ${it.label}" }
                        ?: "Quick tool slot, empty"
                    customActions = toolGateActions
                }
                .shadow(4.dp, satShape)
                .clip(satShape)
                .background(toolSatBg)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val tools = viewModel.uiState.value.pinnedTools
                        if (viewModel.uiState.value.isPenDown || tools.isEmpty()) {
                            return@awaitEachGesture
                        }
                        down.consume()
                        // Quadrant off the touch-down point, exactly like the mode gate. A
                        // direction costs no travel, so unlike the column sweep this used to
                        // be, it cannot be squeezed out by a screen edge however the button
                        // is parked. Nothing is selected until the finger leaves the dead
                        // zone, so letting go without moving does nothing at all.
                        val deadZonePx = 18.dp.toPx()
                        val trailPx = GateMath.ANCHOR_TRAIL_RADIUS_DP.dp.toPx()
                        var anchor = GateMath.Anchor(down.position.x, down.position.y)
                        toolGateCell = -1
                        toolGateFingerLocalX = down.position.x
                        toolGateFingerLocalY = down.position.y
                        toolGateActive = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.find { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                toolGateFingerLocalX = change.position.x
                                toolGateFingerLocalY = change.position.y
                                anchor = GateMath.trailAnchor(
                                    anchor.x, anchor.y,
                                    change.position.x, change.position.y, trailPx
                                )
                                val cell = GateMath.quadrant(
                                    change.position.x - anchor.x,
                                    change.position.y - anchor.y,
                                    deadZonePx
                                )
                                // An empty quadrant reads as neutral rather than as a cell you
                                // could release on and get nothing from.
                                val newCell = if (cell >= 0 && cell < tools.size) cell else -1
                                if (newCell != toolGateCell) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                toolGateCell = newCell
                            }
                        } finally {
                            toolGateActive = false
                        }
                        val cell = toolGateCell
                        toolGateCell = -1
                        tools.getOrNull(cell)?.let {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            it.toggle(viewModel, onRequestSettingsPanel)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Always the pin, never the pinned tool's own glyph. A satellite that morphs into
            // whatever is pinned loses its identity - you can no longer tell at a glance which
            // pill is which. The tools themselves are named in the panel while you hold it.
            Icon(
                imageVector = Icons.Rounded.PushPin,
                contentDescription = null,
                tint = toolSatTint,
                modifier = Modifier.size((miniThicknessDp * 0.55f).dp)
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
                // Built by walking the enum rather than listing the values in order: the panel
                // indexes this by column, so a hand-written list silently mislabels every
                // bubble the moment the parameters are reordered - which is exactly what
                // happened when size/opacity/flow/softness were rearranged.
                currentValues = remember(gateSize, gateSoftness, gateOpacity, gateFlow) {
                    BrushGateParam.entries.map { param ->
                        when (param) {
                            BrushGateParam.Size -> gateSize
                            BrushGateParam.Softness -> gateSoftness
                            BrushGateParam.Opacity -> gateOpacity
                            BrushGateParam.Flow -> gateFlow
                        }
                    }
                },
                fingerX = rightSatX + brushGateFingerLocalX,
                fingerY = rightSatY + brushGateFingerLocalY,
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
                swapTarget = swapTargetName,
                fingerX = bottomSatX + modeGateFingerLocalX,
                fingerY = bottomSatY + modeGateFingerLocalY,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
        }
        AnimatedVisibility(
            visible = colourGateActive,
            enter = scaleIn(MotionTokens.expressiveEnter, transformOrigin = TransformOrigin(0.5f, 0.5f)) +
                fadeIn(tween(120)),
            exit = scaleOut(MotionTokens.expressiveExit, transformOrigin = TransformOrigin(0.5f, 0.5f)) + fadeOut(tween(100))
        ) {
            ColourGatePanel(
                paramIndex = colourGateParam,
                value = colourGateValue,
                hsv = workingHsv,
                swatch = gateColor,
                eyeDropperArmed = isEyeDropperActive,
                fingerX = colourSatX + colourGateFingerLocalX,
                fingerY = colourSatY + colourGateFingerLocalY,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
        }
        AnimatedVisibility(
            visible = toolGateActive,
            enter = scaleIn(MotionTokens.expressiveEnter, transformOrigin = TransformOrigin(0.5f, 0.5f)) +
                fadeIn(tween(120)),
            exit = scaleOut(MotionTokens.expressiveExit, transformOrigin = TransformOrigin(0.5f, 0.5f)) + fadeOut(tween(100))
        ) {
            ToolGatePanel(
                tools = pinnedTools,
                activeFlags = toolGateActiveFlags,
                hoveredCell = toolGateCell,
                fingerX = toolSatX + toolGateFingerLocalX,
                fingerY = toolSatY + toolGateFingerLocalY,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )
        }
    }
}

/**
 * The colour satellite's four columns.
 *
 * [Pipette] is the odd one out: it has no level, so its vertical axis is inert and it fires on
 * release instead. It is a column rather than a separate button because the eyedropper belongs
 * with the colour controls, and a fourth pill orbiting the button would have cost more room
 * than the whole gate.
 */
private enum class ColourGateParam(val label: String, val min: Float, val max: Float) {
    // Declaration order is the column order in the gate and in its readout panel.
    Hue("Hue", 0f, 360f),
    Saturation("Sat", 0f, 1f),
    Brightness("Bright", 0f, 1f),
    Pipette("Pick", 0f, 1f);

    fun read(hsv: Hsv): Float = when (this) {
        Hue -> hsv.hue
        Saturation -> hsv.saturation
        Brightness -> hsv.value
        Pipette -> 0f
    }

    fun applyTo(hsv: Hsv, value: Float): Hsv = when (this) {
        Hue -> hsv.withHue(value)
        Saturation -> hsv.withSaturation(value)
        Brightness -> hsv.withValue(value)
        Pipette -> hsv
    }

    /** Never called for [Pipette] - its bubble is the eyedropper glyph, with nothing to read out. */
    fun format(value: Float): String = when (this) {
        Hue -> "${value.toInt()}°"
        Pipette -> ""
        else -> "${(value * 100).toInt()}%"
    }
}

/** The four brush parameters reachable from the right satellite, with their slider ranges. */
private enum class BrushGateParam(val label: String, val min: Float, val max: Float) {
    // Declaration order is the column order in the gate and in its readout panel, so the two
    // cannot drift apart. Matches the Brush Studio's Core Properties list.
    Size("Size", 1f, 300f),
    Opacity("Opac", 0f, 1f),
    Flow("Flow", 0f, 1f),
    Softness("Soft", 0f, 1f);

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
    fingerX: Float,
    fingerY: Float,
    screenWidth: Float,
    screenHeight: Float
) {
    val density = LocalDensity.current
    val panelW = 260.dp
    val panelH = 56.dp
    val panelWPx = with(density) { panelW.toPx() }
    val panelHPx = with(density) { panelH.toPx() }
    // Clearance for the fingertip and the knuckle behind it.
    val fingerGapPx = with(density) { 72.dp.toPx() }
    // Rides the finger on both axes. Horizontal travel is clamped to the screen, and since
    // the panel is 260dp wide there is little room to move on a phone - expect it to track
    // through the middle of a sweep and sit against the edge at the outer columns.
    val px = (fingerX - panelWPx / 2f).coerceIn(0f, (screenWidth - panelWPx).coerceAtLeast(0f))
    val above = fingerY - panelHPx - fingerGapPx
    val py = (if (above >= 0f) above else fingerY + fingerGapPx)
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
 * The colour gate's readout: a swatch of the colour being mixed, then a bubble per component,
 * same treatment as [BrushGatePanel].
 *
 * The values come from the gate's own [hsv] rather than from the brush colour, so the hue
 * bubble keeps reading the hue being worked on even while saturation sits at zero and the
 * colour on screen is a grey that no longer carries one.
 */
@Composable
private fun ColourGatePanel(
    paramIndex: Int,
    value: Float,
    hsv: Hsv,
    swatch: Color,
    eyeDropperArmed: Boolean,
    fingerX: Float,
    fingerY: Float,
    screenWidth: Float,
    screenHeight: Float
) {
    val density = LocalDensity.current
    // The hue strip is only worth its height while hue is the column being worked, so the
    // panel grows for it and shrinks back. The placement below reads the height it actually
    // has, so it keeps clearing the finger either way.
    val hueSelected = ColourGateParam.entries[paramIndex] == ColourGateParam.Hue
    val panelW = 288.dp
    val panelH = if (hueSelected) 84.dp else 56.dp
    val panelWPx = with(density) { panelW.toPx() }
    val panelHPx = with(density) { panelH.toPx() }
    val fingerGapPx = with(density) { 72.dp.toPx() }
    val px = (fingerX - panelWPx / 2f).coerceIn(0f, (screenWidth - panelWPx).coerceAtLeast(0f))
    val above = fingerY - panelHPx - fingerGapPx
    val py = (if (above >= 0f) above else fingerY + fingerGapPx)
        .coerceIn(0f, (screenHeight - panelHPx).coerceAtLeast(0f))

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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
                ColourGateParam.entries.forEachIndexed { i, p ->
                    val selected = i == paramIndex
                    val bubbleColor by animateColorAsState(
                        targetValue = when {
                            selected -> MaterialTheme.colorScheme.primary
                            p == ColourGateParam.Pipette && eyeDropperArmed -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        animationSpec = MotionTokens.colorTransition
                    )
                    val contentColor = when {
                        selected -> MaterialTheme.colorScheme.onPrimary
                        p == ColourGateParam.Pipette && eyeDropperArmed -> MaterialTheme.colorScheme.onTertiaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    // The dragged component uses the live in-gesture value; the others read the
                    // gate's triple, which is current either way.
                    val v = if (selected) value else p.read(hsv)
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
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                                    // Matches the two-line bubbles beside it, so the row does
                                    // not go ragged where the icon replaces the readout.
                                    .height(30.dp)
                            ) {
                                if (p == ColourGateParam.Pipette) {
                                    // The glyph is the whole label here: "Pick / Lift" spent two
                                    // lines saying what the eyedropper icon says at a glance.
                                    Icon(
                                        Icons.Rounded.Colorize,
                                        contentDescription = null,
                                        tint = contentColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                } else {
                                    Text(
                                        p.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) contentColor.copy(alpha = 0.85f) else contentColor.copy(alpha = 0.75f)
                                    )
                                    Text(
                                        p.format(v),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                        color = contentColor
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (hueSelected) {
                // Built from the gate's own conversion rather than hand-picked stops, so the
                // strip cannot drift from the hues the drag actually produces.
                val hueStops = remember {
                    (0..12).map { step ->
                        val rgb = ColourGate.toRgb(Hsv(step * 30f, 1f, 1f))
                        Color(rgb[0], rgb[1], rgb[2])
                    }
                }
                Canvas(
                    modifier = Modifier
                        .width(248.dp)
                        .height(16.dp)
                        .clip(CircleShape)
                ) {
                    drawRect(brush = Brush.horizontalGradient(hueStops))
                    val x = (value / 360f).coerceIn(0f, 1f) * size.width
                    // Dark under light: one marker alone vanishes into either yellow or blue.
                    drawLine(
                        Color.Black.copy(alpha = 0.5f),
                        Offset(x, 0f), Offset(x, size.height),
                        strokeWidth = 6.dp.toPx()
                    )
                    drawLine(
                        Color.White,
                        Offset(x, 0f), Offset(x, size.height),
                        strokeWidth = 3.dp.toPx()
                    )
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
    swapTarget: String?,
    fingerX: Float,
    fingerY: Float,
    screenWidth: Float,
    screenHeight: Float
) {
    val density = LocalDensity.current
    val panelW = 248.dp
    val panelH = 100.dp
    val panelWPx = with(density) { panelW.toPx() }
    val panelHPx = with(density) { panelH.toPx() }
    val fingerGapPx = with(density) { 56.dp.toPx() }
    // Tracks the finger on both axes, sitting clear of it. The highlighted cell still comes
    // from the drag direction measured off the touch-down point, so what this grid shows is
    // which mode a release would pick - it is a readout that follows the hand, not a set of
    // fixed targets to steer onto.
    val px = (fingerX - panelWPx / 2f).coerceIn(0f, (screenWidth - panelWPx).coerceAtLeast(0f))
    val above = fingerY - panelHPx - fingerGapPx
    val py = (if (above >= 0f) above else fingerY + fingerGapPx)
        .coerceIn(0f, (screenHeight - panelHPx).coerceAtLeast(0f))

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
        // Each bubble names what releasing on it will *do*, not what is currently set - these
        // are toggles and actions now, and a cell reading "Line" while line mode is already on
        // would be describing the state rather than the outcome.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isLineMode) {
                    ModeGateBubble("Free", Icons.Rounded.Gesture, hoveredCell == 0, visible = bubbleVisible[0])
                } else {
                    ModeGateBubble("Line", Icons.Rounded.HorizontalRule, hoveredCell == 0, visible = bubbleVisible[0])
                }
                if (isEraserMode) {
                    ModeGateBubble("Erase off", Icons.Rounded.Close, hoveredCell == 1, visible = bubbleVisible[1])
                } else {
                    ModeGateBubble("Erase on", EraserIcon, hoveredCell == 1, visible = bubbleVisible[1])
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeGateBubble("Undo", Icons.AutoMirrored.Rounded.Undo, hoveredCell == 2, visible = bubbleVisible[2])
                // Named with the brush it would bring back, which is the thing worth knowing
                // before committing to it. Falls back to "Swap" and greys out when there is
                // nowhere to go, so the cell never disappears from the grid.
                ModeGateBubble(
                    swapTarget ?: "Swap",
                    Icons.Rounded.SwapHoriz,
                    hoveredCell == 3,
                    visible = bubbleVisible[3],
                    enabled = swapTarget != null
                )
            }
        }
    }
}

/**
 * Note there is no on/off state here, unlike the tool gate's bubbles. These cells name the
 * outcome rather than the setting - "Erase on" flips to "Erase off" instead of lighting up -
 * so the state is already carried by the label, and a highlight would only say it twice.
 */
@Composable
private fun ModeGateBubble(
    label: String,
    icon: ImageVector,
    hovered: Boolean,
    visible: Boolean,
    enabled: Boolean = true
) {
    val bgColor by animateColorAsState(
        targetValue = when {
            hovered && enabled -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = MotionTokens.colorTransition
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            // Still dims under the finger when disabled, so the cell reads as reached but
            // inert rather than as a miss.
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            hovered -> MaterialTheme.colorScheme.onPrimary
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
                    color = contentColor,
                    // A preset name goes in the swap bubble, and a long one would push the
                    // grid wider than the panel it is measured into.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}


/**
 * Readout for the tool gate: the four pins laid out as a 2x2, mirroring the mode gate so the
 * two satellites are worked the same way. Follows the finger and stays clear of it.
 */
@Composable
private fun ToolGatePanel(
    tools: List<PinnableTool>,
    activeFlags: List<Boolean>,
    hoveredCell: Int,
    fingerX: Float,
    fingerY: Float,
    screenWidth: Float,
    screenHeight: Float
) {
    if (tools.isEmpty()) return
    val density = LocalDensity.current
    val panelW = 248.dp
    val panelH = 100.dp
    val panelWPx = with(density) { panelW.toPx() }
    val panelHPx = with(density) { panelH.toPx() }
    val fingerGapPx = with(density) { 56.dp.toPx() }
    val px = (fingerX - panelWPx / 2f).coerceIn(0f, (screenWidth - panelWPx).coerceAtLeast(0f))
    val above = fingerY - panelHPx - fingerGapPx
    val py = (if (above >= 0f) above else fingerY + fingerGapPx)
        .coerceIn(0f, (screenHeight - panelHPx).coerceAtLeast(0f))

    // Staggered pop-in, same treatment as the mode gate's bubbles
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
                ToolGateBubble(tools.getOrNull(0), activeFlags.getOrNull(0) == true, hoveredCell == 0, bubbleVisible[0])
                ToolGateBubble(tools.getOrNull(1), activeFlags.getOrNull(1) == true, hoveredCell == 1, bubbleVisible[1])
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolGateBubble(tools.getOrNull(2), activeFlags.getOrNull(2) == true, hoveredCell == 2, bubbleVisible[2])
                ToolGateBubble(tools.getOrNull(3), activeFlags.getOrNull(3) == true, hoveredCell == 3, bubbleVisible[3])
            }
        }
    }
}

/** A null [tool] is an unfilled quadrant: shown so the grid keeps its shape, but inert. */
@Composable
private fun ToolGateBubble(tool: PinnableTool?, isOn: Boolean, hovered: Boolean, visible: Boolean) {
    val bgColor by animateColorAsState(
        targetValue = when {
            tool == null -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)
            hovered -> MaterialTheme.colorScheme.primary
            // primaryContainer, not secondaryContainer: in the dark scheme the secondary one
            // sits a couple of steps off surfaceContainerHigh, so a tool that was already on
            // read as just another idle cell - which is the whole thing this bubble has to say.
            isOn -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = MotionTokens.colorTransition
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            tool == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            hovered -> MaterialTheme.colorScheme.onPrimary
            isOn -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = MotionTokens.colorTransition
    )
    // A fill on its own is still at the mercy of dynamic colour, where the container tones
    // are whatever the wallpaper hands us and can land close to the surface again. The ring
    // is drawn in the full-strength accent, which is the one colour guaranteed to stand off
    // the surface in both schemes. Under the finger it flips to onPrimary so the "already
    // on" mark survives on top of the primary fill instead of disappearing into it.
    val borderColor by animateColorAsState(
        targetValue = when {
            tool == null || !isOn -> Color.Transparent
            hovered -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.primary
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
            border = if (tool == null) null else BorderStroke(2.dp, borderColor),
            // An on tool also sits higher than the rest of the grid, so the state carries at
            // a glance even before the colours are read.
            shadowElevation = when {
                tool == null -> 0.dp
                isOn -> 8.dp
                else -> 4.dp
            },
            tonalElevation = if (tool == null) 0.dp else 2.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    tool?.icon ?: Icons.Rounded.Add,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    tool?.label ?: "Empty",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (hovered || isOn) FontWeight.Bold else FontWeight.Medium,
                    color = contentColor
                )
            }
        }
    }
}
