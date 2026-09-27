package com.yighy.pantograph.drawing

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.dp
import com.yighy.pantograph.ui.theme.MotionTokens

/**
 * The tool satellite: up to four pinned tools, one per quadrant, picked by direction and toggled
 * on release - the same gate the mode satellite uses.
 */
@Composable
internal fun ToolSatellite(
    viewModel: DrawingViewModel,
    arc: SatelliteArcs.Arc,
    gate: CellGateState,
    pinnedTools: List<PinnableTool>,
    toolGateActiveFlags: List<Boolean>,
    satelliteScale: Float,
    satelliteAlpha: Float,
    miniThicknessDp: Float
) {
    val haptics = LocalHapticFeedback.current

    // The pill itself only reports "is any pinned tool currently on", since its glyph no
    // longer names a particular one. Which tool is which is the panel's job.
    val anyPinnedToolActive = toolGateActiveFlags.any { it }

    val toolSatScale by animateFloatAsState(
        targetValue = if (gate.active) 1.08f else 1f,
        animationSpec = MotionTokens.pulse,
        label = "toolSatScale"
    )
    val toolSatBg by animateColorAsState(
        targetValue = when {
            gate.active || anyPinnedToolActive -> MaterialTheme.colorScheme.primary
            pinnedTools.isEmpty() -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        animationSpec = MotionTokens.colorTransition,
        label = "toolSatBg"
    )
    val toolSatTint by animateColorAsState(
        targetValue = when {
            gate.active || anyPinnedToolActive -> MaterialTheme.colorScheme.onPrimary
            pinnedTools.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        },
        animationSpec = MotionTokens.colorTransition,
        label = "toolSatTint"
    )

    val toolGateActions = pinnedTools.map { tool ->
        CustomAccessibilityAction(tool.label) { tool.toggle(viewModel); true }
    }

    SatellitePill(
        arc = arc,
        scale = satelliteScale * toolSatScale,
        alpha = satelliteAlpha,
        background = toolSatBg,
        semantics = {
            contentDescription = pinnedTools.firstOrNull()
                ?.let { "Quick tools, ${pinnedTools.size} pinned, first is ${it.label}" }
                ?: "Quick tool slot, empty"
            customActions = toolGateActions
        },
        gesture = Modifier.pointerInput(Unit) {
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
                gate.cell = -1
                gate.fingerX = down.position.x
                gate.fingerY = down.position.y
                gate.active = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                        val cell = GateMath.quadrant(
                            change.position.x - anchor.x,
                            change.position.y - anchor.y,
                            deadZonePx
                        )
                        // An empty quadrant reads as neutral rather than as a cell you
                        // could release on and get nothing from.
                        val newCell = if (cell >= 0 && cell < tools.size) cell else -1
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
                tools.getOrNull(cell)?.let {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    it.toggle(viewModel)
                }
            }
        }
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
}

/** What the tool satellite shows while it is held. See [ToolGatePanel]. */
@Composable
internal fun ToolGateOverlay(
    arc: SatelliteArcs.Arc,
    gate: CellGateState,
    pinnedTools: List<PinnableTool>,
    toolGateActiveFlags: List<Boolean>,
    screenWidth: Float,
    screenHeight: Float
) {
    GateOverlay(gate.active) { visible ->
        ToolGatePanel(
            tools = pinnedTools,
            activeFlags = toolGateActiveFlags,
            hoveredCell = gate.cell,
            fingerX = arc.x + gate.fingerX,
            fingerY = arc.y + gate.fingerY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            visible = visible
        )
    }
}
