package com.yighy.pantograph.drawing

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.dp
import com.yighy.pantograph.ui.theme.MotionTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The drawing-mode satellite: a 2x2 gate picked by direction and applied on release - top row
 * the stroke's shape and the eraser, bottom row undo and a swap back to the previous brush.
 */
@Composable
internal fun ModeSatellite(
    viewModel: DrawingViewModel,
    arc: SatelliteArcs.Arc,
    gate: CellGateState,
    isLineMode: Boolean,
    isEraserMode: Boolean,
    satelliteScale: Float,
    satelliteAlpha: Float,
    miniThicknessDp: Float
) {
    val haptics = LocalHapticFeedback.current

    val modeGateScale by animateFloatAsState(
        targetValue = if (gate.active) 1.08f else 1f,
        animationSpec = MotionTokens.pulse
    )
    val modeGateBg by animateColorAsState(
        targetValue = when {
            gate.active -> MaterialTheme.colorScheme.primary
            isEraserMode -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        animationSpec = MotionTokens.colorTransition
    )
    val modeGateIconTint by animateColorAsState(
        targetValue = when {
            gate.active -> MaterialTheme.colorScheme.onPrimary
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

    SatellitePill(
        arc = arc,
        scale = modeGateScale * satelliteScale,
        alpha = satelliteAlpha,
        background = modeGateBg,
        semantics = {
            contentDescription = when {
                isEraserMode && isLineMode -> "Drawing mode: straight line eraser"
                isEraserMode -> "Drawing mode: eraser"
                isLineMode -> "Drawing mode: straight line"
                else -> "Drawing mode: freehand"
            }
            customActions = modeGateActions
        },
        gesture = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (viewModel.uiState.value.isPenEngaged) return@awaitEachGesture
                down.consume()
                val deadZonePx = 18.dp.toPx()
                val trailPx = GateMath.ANCHOR_TRAIL_RADIUS_DP.dp.toPx()
                var anchor = GateMath.Anchor(down.position.x, down.position.y)
                gate.cell = -1
                gate.fingerX = down.position.x
                gate.fingerY = down.position.y
                gate.active = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                // This coroutine is cancellable: hiding the satellites disposes
                // the node it runs in, and a cancel between here and the reset
                // below would strand the gate's active flag at true - leaving the panel
                // on screen with nothing holding it.
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.find { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        gate.fingerX = change.position.x
                        gate.fingerY = change.position.y
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
                        if (newCell != gate.cell) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        gate.cell = newCell
                    }
                } finally {
                    gate.active = false
                }
                val cell = gate.cell
                gate.cell = -1
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
        }
    ) {
        // Same affordance as the brush gate, turned along this pill's own axis.
        //
        // The chevrons are auto-mirrored ones, but only because the plain variants are
        // deprecated - they mean "this pill sweeps sideways", not "back" and "forward",
        // and the drag does not reverse under RTL. Mirroring a symmetric pair swaps two
        // glyphs that are each other's reflection, so the row draws identically either
        // way. Keep them as a pair; flipping one on its own would break that.
        val modeHintAlpha by animateFloatAsState(
            targetValue = if (gate.active) 0f else 0.55f,
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
                // Named by the pill's semantics, so the glyph stays decorative.
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
}

/** What the drawing-mode satellite shows while it is held. See [ModeGatePanel]. */
@Composable
internal fun ModeGateOverlay(
    viewModel: DrawingViewModel,
    arc: SatelliteArcs.Arc,
    gate: CellGateState,
    isLineMode: Boolean,
    isEraserMode: Boolean,
    screenWidth: Float,
    screenHeight: Float
) {
    // Name of the preset the swap cell would bring back, or null when there is none.
    // Resolved through the list rather than trusting the remembered id, so a preset deleted
    // since greys the cell out instead of leaving it offering a swap that would do nothing.
    val swapTargetName by remember(viewModel) {
        viewModel.uiState.map { state ->
            state.previousBrushId?.let { id -> state.customBrushes.find { it.id == id }?.name }
        }.distinctUntilChanged()
    }.collectAsState(null)

    GateOverlay(gate.active) { visible ->
        ModeGatePanel(
            hoveredCell = gate.cell,
            isLineMode = isLineMode,
            isEraserMode = isEraserMode,
            swapTarget = swapTargetName,
            fingerX = arc.x + gate.fingerX,
            fingerY = arc.y + gate.fingerY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            visible = visible
        )
    }
}
