package com.yighy.pantograph.drawing

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
 * The brush levels satellite: a column per parameter - size, softness, opacity, flow - picked by
 * dragging sideways, its level set by dragging up and down, like a gear stick.
 */
@Composable
internal fun BrushSatellite(
    viewModel: DrawingViewModel,
    arc: SatelliteArcs.Arc,
    gate: LevelGateState,
    satelliteGateSensitivity: Float,
    satelliteScale: Float,
    satelliteAlpha: Float,
    miniThicknessDp: Float
) {
    // The satellite gates are worked blind - your own finger covers the bubbles - so every
    // threshold the gesture code already tracks (gate engaged, column changed, dead zone
    // cleared) gets a matching tick. LongPress marks committing to something, TextHandleMove
    // is the light per-step tick.
    val haptics = LocalHapticFeedback.current

    // Springy, MD3-Expressive feedback on activation: the satellite pulses and its
    // colors ease across instead of snapping, matching the FAB's own pen-down spring
    val brushGateScale by animateFloatAsState(
        targetValue = if (gate.active) 1.08f else 1f,
        animationSpec = MotionTokens.pulse
    )
    val brushGateBg by animateColorAsState(
        targetValue = if (gate.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        animationSpec = MotionTokens.colorTransition
    )
    val brushGateIconTint by animateColorAsState(
        targetValue = if (gate.active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
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

    SatellitePill(
        arc = arc,
        scale = brushGateScale * satelliteScale,
        alpha = satelliteAlpha,
        background = brushGateBg,
        semantics = {
            contentDescription = "Brush levels"
            customActions = brushGateActions
        },
        gesture = Modifier.pointerInput(satelliteGateSensitivity) {
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
                val startIndex = gate.param
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
                gate.value = anchorValue
                gate.fingerX = down.position.x
                gate.fingerY = down.position.y
                gate.active = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                // Tracks dead-zone state so the "value is live now" tick fires on the
                // crossing, not on every frame beyond it.
                var inDeadZone = true
                // See the mode gate: cancellation must not strand this flag.
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.find { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        gate.fingerX = change.position.x
                        gate.fingerY = change.position.y
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
                            gate.param = newIndex
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
                        gate.value = stepped.value
                        p.apply(viewModel, stepped.value)
                    }
                } finally {
                    gate.active = false
                }
            }
        }
    ) {
        // Chevrons flanking the icon: a plain centred glyph reads as "tap me", which is
        // the one thing this control does not do. They sit on the pill's long axis, the
        // axis that carries the value, and dim out while the gate is engaged so they
        // don't compete with the bubbles.
        val brushHintAlpha by animateFloatAsState(
            targetValue = if (gate.active) 0f else 0.55f,
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
                // Named by the pill's semantics, so the glyph stays decorative.
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
}

/** What the brush levels satellite shows while it is held. See [BrushGatePanel]. */
@Composable
internal fun BrushGateOverlay(
    viewModel: DrawingViewModel,
    arc: SatelliteArcs.Arc,
    gate: LevelGateState,
    screenWidth: Float,
    screenHeight: Float
) {
    // Live values for all four brush-gate params, so the bubble row can show every level
    // at a glance, not just the one currently being dragged (see BrushGatePanel)
    val gateSize by remember(viewModel) { viewModel.uiState.map { it.selectedWidth }.distinctUntilChanged() }.collectAsState(20f)
    val gateSoftness by remember(viewModel) { viewModel.uiState.map { it.brushSoftness }.distinctUntilChanged() }.collectAsState(0f)
    val gateOpacity by remember(viewModel) { viewModel.uiState.map { it.brushOpacity }.distinctUntilChanged() }.collectAsState(1f)
    val gateFlow by remember(viewModel) { viewModel.uiState.map { it.brushFlow }.distinctUntilChanged() }.collectAsState(1f)

    GateOverlay(gate.active) { visible ->
        BrushGatePanel(
            paramIndex = gate.param,
            value = gate.value,
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
            fingerX = arc.x + gate.fingerX,
            fingerY = arc.y + gate.fingerY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            visible = visible
        )
    }
}
