package com.yighy.pantograph.drawing

import androidx.compose.animation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The floating button itself: held to draw, dragged to move, and with Anchor armed, dragged to
 * steer the anchor. [position] is where it sits; its drag writes there, and the satellites read
 * it. [scale] is its pen-down pulse.
 */
@Composable
internal fun DrawButton(
    viewModel: DrawingViewModel,
    position: FabPosition,
    screenWidth: Float,
    screenHeight: Float,
    fabSizePx: Float,
    fabSizeSetting: Float,
    scale: Float,
    fabDragThreshold: Float,
    drawingMode: DrawingMode,
    isEyeDropperMode: Boolean,
    isPenEngaged: Boolean,
    onPositionChanged: (Float, Float) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    // Read live by the gesture, whose block outlives any one composition: it restarts only
    // when the drag threshold changes.
    val currentDrawingMode by rememberUpdatedState(drawingMode)
    val currentEyeDropperMode by rememberUpdatedState(isEyeDropperMode)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    position.x.coerceIn(0f, (screenWidth - fabSizePx).coerceAtLeast(0f)).roundToInt(),
                    position.y.coerceIn(0f, (screenHeight - fabSizePx).coerceAtLeast(0f)).roundToInt()
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
                                position.x = (position.x + step.x).coerceIn(0f, screenWidth - fabSizePx)
                                position.y = (position.y + step.y).coerceIn(0f, screenHeight - fabSizePx)
                                onPositionChanged(position.x, position.y)
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
                        if (movingButton) viewModel.saveFabPosition(position.x, position.y)
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
                                    val isSelectionMode = currentDrawingMode.isSelectionTool()
                                    if (currentDrawingMode is DrawingMode.Path) {
                                        // The path tool pushes no history entry until the
                                        // curve is committed, so aborting a stroke here
                                        // would pop somebody else's. It has its own way of
                                        // taking back the point this press just placed.
                                        viewModel.abortPathPress()
                                    } else if (!currentEyeDropperMode && !isSelectionMode) {
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
                            position.x = (position.x + (change.position.x - change.previousPosition.x)).coerceIn(0f, screenWidth - fabSizePx)
                            position.y = (position.y + (change.position.y - change.previousPosition.y)).coerceIn(0f, screenHeight - fabSizePx)
                            onPositionChanged(position.x, position.y)
                        }
                    }

                    if (gestureMode == 1) viewModel.saveFabPosition(position.x, position.y)
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
}
