package com.yighy.pantograph.drawing

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yighy.pantograph.ui.theme.MotionTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.min
import kotlin.math.roundToInt

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
        targetValue = if (isPenEngaged) fabPressScale else 1f,
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
                        // Through pressPen rather than straight to the pen: with Anchor on, a
                        // press arms the pen instead of lowering it.
                        viewModel.pressPen()

                        // With the anchor armed, the finger on the button has two jobs, told
                        // apart the way a home screen tells using an icon from lifting it:
                        // moving straight away steers the anchor, holding still first lifts the
                        // button itself. The frequent one - nudging the anchor, many times a
                        // series - gets the direct gesture; the rare one gets the deliberate one.
                        if (viewModel.uiState.value.isAnchorArmed) {
                            val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                            var lastTime = down.uptimeMillis
                            // Past the long-press window, one way or the other.
                            var decided = false
                            var movingButton = false
                            // Past a touch slop: a finger resting on the button is never quite
                            // still, and without the margin its tremor would walk the anchor
                            // across the canvas a fraction of a pixel at a time.
                            var steering = false
                            var slop = Offset.Zero
                            while (true) {
                                val event = if (!decided) {
                                    withTimeoutOrNull((deadline - lastTime).coerceAtLeast(0L)) { awaitPointerEvent() }
                                } else {
                                    awaitPointerEvent()
                                }
                                if (event == null) {
                                    decided = true
                                    // Held still through the delay. That lifts the button only if
                                    // nothing has been drawn in this hold yet: a pause between two
                                    // lines, button held while you think, is not a request to
                                    // move it - and would otherwise unhook it mid-series.
                                    val drawn = viewModel.uiState.value.isPenDown ||
                                        viewModel.getCurrentStrokeDistance() > 0f
                                    if (!drawn) {
                                        movingButton = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        // Disarms: the anchor goes with the hold it belonged to.
                                        viewModel.releasePen()
                                    }
                                    continue
                                }
                                val change = event.changes.find { it.id == down.id } ?: break
                                if (!change.pressed) break
                                lastTime = change.uptimeMillis
                                val step = change.position - change.previousPosition
                                if (movingButton) {
                                    change.consume()
                                    localX = (localX + step.x).coerceIn(0f, screenWidth - fabSizePx)
                                    localY = (localY + step.y).coerceIn(0f, screenHeight - fabSizePx)
                                    onPositionChanged(localX, localY)
                                    continue
                                }
                                if (!steering) {
                                    slop += step
                                    if (slop.getDistance() <= viewConfiguration.touchSlop) continue
                                    steering = true
                                    decided = true
                                }
                                change.consume()
                                viewModel.moveAnchor(step)
                            }
                            if (movingButton) viewModel.saveFabPosition(localX, localY)
                            else viewModel.releasePen()
                            return@awaitEachGesture
                        }

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
                                        viewModel.releasePen()
                                        // Only abort stroke if we were actually drawing (not picking color or selecting)
                                        val isSelectionMode = drawingMode.isSelectionTool()
                                        if (drawingMode is DrawingMode.Path) {
                                            // The path tool pushes no history entry until the
                                            // curve is committed, so aborting a stroke here
                                            // would pop somebody else's. It has its own way of
                                            // taking back the point this press just placed.
                                            viewModel.abortPathPress()
                                        } else if (!isEyeDropperMode && !isSelectionMode) {
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
                        viewModel.releasePen()
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
                drawingMode is DrawingMode.Path ->
                    Triple(Icons.Rounded.Timeline, "Path tool active", MaterialTheme.colorScheme.primaryContainer)
                else -> null
            }
            // Pressed always reads the same whatever tool is armed, so the identity lives in
            // the glyph and the "a stroke is happening" signal stays in the colour.
            val container = when {
                isPenEngaged -> MaterialTheme.colorScheme.errorContainer
                armed != null -> armed.third
                else -> MaterialTheme.colorScheme.tertiaryContainer
            }
            // Engaged rather than down, so an armed anchor keeps the pen glyph between lines
            // instead of flicking back to the hand each time the drawing finger lifts.
            val fabIcon = armed?.first
                ?: if (isPenEngaged) Icons.Default.Edit else Icons.Default.TouchApp
            // The path tool is held to place rather than held to draw, so it says so: "Drawing"
            // would be describing a stroke that is not happening.
            val fabDescription = when {
                drawingMode is DrawingMode.Path ->
                    if (isPenEngaged) "Placing a path point" else "Hold to place a path point"
                armed != null -> armed.second
                isPenEngaged -> "Drawing"
                else -> "Hold to draw"
            }

            FloatingActionButton(
                onClick = { },
                modifier = Modifier.fillMaxSize(),
                // A disc rather than Material's rounded square: this is the pen, not an action
                // button, and it sits among rings - the cursor's reticle, the anchor, the loupe's
                // crosshair. Its satellites are full pills for the same family of shapes.
                shape = CircleShape,
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
        fun SatelliteArcs.Arc.dpSize() = with(density) { DpSize(width.toDp(), height.toDp()) }
        // A glyph belongs in the middle of its arc, which the arc's bow puts a couple of dp
        // further out than the middle of the box around it.
        fun SatelliteArcs.Arc.contentAlignment() = Alignment { size, _, _ ->
            IntOffset(
                (contentCenterX - size.width / 2f).roundToInt(),
                (contentCenterY - size.height / 2f).roundToInt()
            )
        }

        val rightSatX = levelsArc.x
        val rightSatY = levelsArc.y
        val bottomSatX = modeArc.x
        val bottomSatY = modeArc.y
        val colourSatX = colourArc.x
        val colourSatY = colourArc.y
        val toolSatX = toolArc.x
        val toolSatY = toolArc.y
        val levelsSize = levelsArc.dpSize()
        val modeSize = modeArc.dpSize()
        val colourSize = colourArc.dpSize()
        val levelsShape: Shape = SatelliteArcShape(levelsArc)
        val modeShape: Shape = SatelliteArcShape(modeArc)
        val colourShape: Shape = SatelliteArcShape(colourArc)
        val toolShape: Shape = SatelliteArcShape(toolArc)
        // Turned on its side when it falls back to a flank rather than stacking.
        val toolSize = toolArc.dpSize()

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
                .size(levelsSize)
                .semantics {
                    contentDescription = "Brush levels"
                    customActions = brushGateActions
                }
                .shadow(4.dp, levelsShape)
                .clip(levelsShape)
                .background(brushGateBg)
                .pointerInput(satelliteGateSensitivity) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // Read live rather than through a captured value: this block is not
                        // rebuilt when the FAB is pressed. Hidden means inert - bail out
                        // without consuming, so the touch is nobody's business.
                        if (viewModel.uiState.value.isPenEngaged) return@awaitEachGesture
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
            contentAlignment = levelsArc.contentAlignment()
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
                .size(modeSize)
                .semantics {
                    contentDescription = when {
                        isEraserMode && isLineMode -> "Drawing mode: straight line eraser"
                        isEraserMode -> "Drawing mode: eraser"
                        isLineMode -> "Drawing mode: straight line"
                        else -> "Drawing mode: freehand"
                    }
                    customActions = modeGateActions
                }
                .shadow(4.dp, modeShape)
                .clip(modeShape)
                .background(modeGateBg)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (viewModel.uiState.value.isPenEngaged) return@awaitEachGesture
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
            contentAlignment = modeArc.contentAlignment()
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
                        val raw = param.read(hsv) + if (increase) stepSize else -stepSize
                        // Same rule as the drag: hue comes round, the others stop at their ends.
                        // Clamping here left hue stuck at red for anyone driving the gate by
                        // accessibility actions, after the gesture itself had stopped doing that.
                        val next = if (param.wraps) GateMath.wrapInto(raw, param.min, param.max)
                            else raw.coerceIn(param.min, param.max)
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
                .size(colourSize)
                .semantics {
                    contentDescription = if (isEyeDropperActive) "Colour, eyedropper armed" else "Colour"
                    customActions = colourGateActions
                }
                .shadow(4.dp, colourShape)
                .clip(colourShape)
                .background(colourGateBg)
                .pointerInput(satelliteGateSensitivity) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (viewModel.uiState.value.isPenEngaged) return@awaitEachGesture
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
                                travelPx = travelPx,
                                wrap = p.wraps
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
            contentAlignment = colourArc.contentAlignment()
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
            CustomAccessibilityAction(tool.label) { tool.toggle(viewModel); true }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(toolSatX.roundToInt(), toolSatY.roundToInt()) }
                .scale(satelliteScale * toolSatScale)
                .alpha(satelliteAlpha)
                .size(toolSize)
                .semantics {
                    contentDescription = pinnedTools.firstOrNull()
                        ?.let { "Quick tools, ${pinnedTools.size} pinned, first is ${it.label}" }
                        ?: "Quick tool slot, empty"
                    customActions = toolGateActions
                }
                .shadow(4.dp, toolShape)
                .clip(toolShape)
                .background(toolSatBg)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val tools = viewModel.uiState.value.pinnedTools
                        if (viewModel.uiState.value.isPenEngaged || tools.isEmpty()) {
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
                            it.toggle(viewModel)
                        }
                    }
                },
            contentAlignment = toolArc.contentAlignment()
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
            // Holds fully opaque while the bubbles cascade out underneath, then clears
            // whatever is left. Scaling here too would fight their own scale-out.
            exit = fadeOut(tween(80, delayMillis = 200))
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
                screenHeight = screenHeight,
                // Read off the enclosing transition rather than the gate's own flag: this has
                // to turn false when the panel starts leaving, not when the gesture ends.
                visible = transition.targetState == EnterExitState.Visible
            )
        }
        AnimatedVisibility(
            visible = modeGateActive,
            enter = scaleIn(MotionTokens.expressiveEnter, transformOrigin = TransformOrigin(0.5f, 0.5f)) +
                fadeIn(tween(120)),
            // Holds fully opaque while the bubbles cascade out underneath, then clears
            // whatever is left. Scaling here too would fight their own scale-out.
            exit = fadeOut(tween(80, delayMillis = 200))
        ) {
            ModeGatePanel(
                hoveredCell = modeGateCell,
                isLineMode = isLineMode,
                isEraserMode = isEraserMode,
                swapTarget = swapTargetName,
                fingerX = bottomSatX + modeGateFingerLocalX,
                fingerY = bottomSatY + modeGateFingerLocalY,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                // Read off the enclosing transition rather than the gate's own flag: this has
                // to turn false when the panel starts leaving, not when the gesture ends.
                visible = transition.targetState == EnterExitState.Visible
            )
        }
        AnimatedVisibility(
            visible = colourGateActive,
            enter = scaleIn(MotionTokens.expressiveEnter, transformOrigin = TransformOrigin(0.5f, 0.5f)) +
                fadeIn(tween(120)),
            // Holds fully opaque while the bubbles cascade out underneath, then clears
            // whatever is left. Scaling here too would fight their own scale-out.
            exit = fadeOut(tween(80, delayMillis = 200))
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
                screenHeight = screenHeight,
                // Read off the enclosing transition rather than the gate's own flag: this has
                // to turn false when the panel starts leaving, not when the gesture ends.
                visible = transition.targetState == EnterExitState.Visible
            )
        }
        AnimatedVisibility(
            visible = toolGateActive,
            enter = scaleIn(MotionTokens.expressiveEnter, transformOrigin = TransformOrigin(0.5f, 0.5f)) +
                fadeIn(tween(120)),
            // Holds fully opaque while the bubbles cascade out underneath, then clears
            // whatever is left. Scaling here too would fight their own scale-out.
            exit = fadeOut(tween(80, delayMillis = 200))
        ) {
            ToolGatePanel(
                tools = pinnedTools,
                activeFlags = toolGateActiveFlags,
                hoveredCell = toolGateCell,
                fingerX = toolSatX + toolGateFingerLocalX,
                fingerY = toolSatY + toolGateFingerLocalY,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                // Read off the enclosing transition rather than the gate's own flag: this has
                // to turn false when the panel starts leaving, not when the gesture ends.
                visible = transition.targetState == EnterExitState.Visible
            )
        }
    }
}
