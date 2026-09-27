package com.yighy.pantograph.drawing

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.dp
import com.yighy.pantograph.ui.theme.MotionTokens

/**
 * What the colour gate shares with its panel: a level gate's state, plus the hue, saturation and
 * brightness it is working on.
 *
 * Kept across gestures. A colour cannot always say what its hue was - grey has none - so this is
 * the authority and the brush colour only tops it up with what it can still express. See
 * ColourGate.readFrom.
 */
@Stable
internal class ColourGateState : LevelGateState() {
    var workingHsv by mutableStateOf(Hsv(0f, 1f, 1f))
}

/**
 * The colour satellite. Same gear-stick interaction as the brush levels: a column per component,
 * its level on the vertical axis. The fourth column is the eyedropper, which is an action rather
 * than a level - see ColourGateParam.
 */
@Composable
internal fun ColourSatellite(
    viewModel: DrawingViewModel,
    arc: SatelliteArcs.Arc,
    gate: ColourGateState,
    gateColor: Color,
    isEyeDropperActive: Boolean,
    satelliteGateSensitivity: Float,
    satelliteScale: Float,
    satelliteAlpha: Float,
    miniThicknessDp: Float
) {
    val haptics = LocalHapticFeedback.current

    val colourGateScale by animateFloatAsState(
        targetValue = if (gate.active) 1.08f else 1f,
        animationSpec = MotionTokens.pulse
    )
    val colourGateBg by animateColorAsState(
        targetValue = when {
            gate.active -> MaterialTheme.colorScheme.primary
            isEyeDropperActive -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        animationSpec = MotionTokens.colorTransition
    )
    val colourGateIconTint by animateColorAsState(
        targetValue = when {
            gate.active -> MaterialTheme.colorScheme.onPrimary
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
                    val hsv = ColourGate.readFrom(live.red, live.green, live.blue, gate.workingHsv)
                    val stepSize = (param.max - param.min) / 10f
                    val raw = param.read(hsv) + if (increase) stepSize else -stepSize
                    // Same rule as the drag: hue comes round, the others stop at their ends.
                    // Clamping here left hue stuck at red for anyone driving the gate by
                    // accessibility actions, after the gesture itself had stopped doing that.
                    val next = if (param.wraps) GateMath.wrapInto(raw, param.min, param.max)
                        else raw.coerceIn(param.min, param.max)
                    val updated = param.applyTo(hsv, next)
                    gate.workingHsv = updated
                    val rgb = ColourGate.toRgb(updated)
                    viewModel.selectColor(Color(rgb[0], rgb[1], rgb[2]))
                    true
                }
            }
        } + CustomAccessibilityAction("Toggle eyedropper") { viewModel.toggleEyeDropper(); true }

    SatellitePill(
        arc = arc,
        scale = colourGateScale * satelliteScale,
        alpha = satelliteAlpha,
        background = colourGateBg,
        semantics = {
            contentDescription = if (isEyeDropperActive) "Colour, eyedropper armed" else "Colour"
            customActions = colourGateActions
        },
        gesture = Modifier.pointerInput(satelliteGateSensitivity) {
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
                val startIndex = gate.param
                var index = startIndex
                var committedRel = 0
                var anchorX = down.position.x
                var anchorY = down.position.y

                val live = viewModel.uiState.value.selectedColor
                var hsv = ColourGate.readFrom(live.red, live.green, live.blue, gate.workingHsv)
                gate.workingHsv = hsv
                var anchorValue = params[index].read(hsv)
                gate.value = anchorValue
                gate.fingerX = down.position.x
                gate.fingerY = down.position.y
                gate.active = true
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
                            index = newIndex
                            committedRel = newRel.coerceIn(-startIndex, params.size - 1 - startIndex)
                            gate.param = newIndex
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
                        gate.value = stepped.value
                        hsv = p.applyTo(hsv, stepped.value)
                        gate.workingHsv = hsv
                        val rgb = ColourGate.toRgb(hsv)
                        viewModel.selectColor(Color(rgb[0], rgb[1], rgb[2]))
                    }
                    if (released && params[index] == ColourGateParam.Pipette) {
                        viewModel.toggleEyeDropper()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                } finally {
                    gate.active = false
                }
            }
        }
    ) {
        val colourHintAlpha by animateFloatAsState(
            targetValue = if (gate.active) 0f else 0.55f,
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
}

/** What the colour satellite shows while it is held. See [ColourGatePanel]. */
@Composable
internal fun ColourGateOverlay(
    arc: SatelliteArcs.Arc,
    gate: ColourGateState,
    gateColor: Color,
    isEyeDropperActive: Boolean,
    screenWidth: Float,
    screenHeight: Float
) {
    GateOverlay(gate.active) { visible ->
        ColourGatePanel(
            paramIndex = gate.param,
            value = gate.value,
            hsv = gate.workingHsv,
            swatch = gateColor,
            eyeDropperArmed = isEyeDropperActive,
            fingerX = arc.x + gate.fingerX,
            fingerY = arc.y + gate.fingerY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            visible = visible
        )
    }
}
