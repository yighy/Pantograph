package com.yighy.pantograph.drawing

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yighy.pantograph.ui.theme.MotionTokens
import kotlin.math.roundToInt

/**
 * Where the floating button sits, in pixels from the top left. Shared between the button, whose
 * drag moves it, and the satellites, which are laid out around it.
 */
@Stable
internal class FabPosition(x: Float, y: Float) {
    var x by mutableFloatStateOf(x)
    var y by mutableFloatStateOf(y)
}

/**
 * What a level gate - brush levels, colour - shares with the panel it shows while held: whether
 * it is held, which column is live and its value, and where the finger is.
 *
 * The finger is kept in the satellite's own coordinates and converted to screen space where the
 * panel is placed: pointerInput's block doesn't restart when the FAB moves, so a screen-space
 * value captured in there would still be relative to the old position.
 */
@Stable
internal open class LevelGateState {
    var active by mutableStateOf(false)
    var param by mutableIntStateOf(0)
    var value by mutableFloatStateOf(0f)
    var fingerX by mutableFloatStateOf(0f)
    var fingerY by mutableFloatStateOf(0f)
}

/** The same for a quadrant gate - mode, tools - where what is live is a cell, -1 for none. */
@Stable
internal class CellGateState {
    var active by mutableStateOf(false)
    var cell by mutableIntStateOf(-1)
    var fingerX by mutableFloatStateOf(0f)
    var fingerY by mutableFloatStateOf(0f)
}

/**
 * The pill every satellite is drawn in: an arc of the ring around the button, placed and sized
 * from [arc], scaled by [scale] and faded by [alpha], filled with [background]. [gesture] is its
 * pointer handling and [semantics] what a screen reader hears of it.
 */
@Composable
internal fun SatellitePill(
    arc: SatelliteArcs.Arc,
    scale: Float,
    alpha: Float,
    background: Color,
    semantics: SemanticsPropertyReceiver.() -> Unit,
    gesture: Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val shape: Shape = SatelliteArcShape(arc)
    Box(
        modifier = Modifier
            .offset { IntOffset(arc.x.roundToInt(), arc.y.roundToInt()) }
            .scale(scale)
            .alpha(alpha)
            .size(with(density) { DpSize(arc.width.toDp(), arc.height.toDp()) })
            .semantics(properties = semantics)
            .shadow(4.dp, shape)
            .clip(shape)
            .background(background)
            .then(gesture),
        // A glyph belongs in the middle of its arc, which the arc's bow puts a couple of dp
        // further out than the middle of the box around it.
        contentAlignment = Alignment { size, _, _ ->
            IntOffset(
                (arc.contentCenterX - size.width / 2f).roundToInt(),
                (arc.contentCenterY - size.height / 2f).roundToInt()
            )
        },
        content = content
    )
}

/**
 * The panel a satellite shows while it is held, drawn above everything and with no pointer input
 * of its own: the satellite owns the gesture. [content] is told whether the panel is on its way
 * in, as opposed to on its way out.
 */
@Composable
internal fun GateOverlay(active: Boolean, content: @Composable (visible: Boolean) -> Unit) {
    // AnimatedVisibility must stay unconditionally in the tree (not behind an `if`) so its
    // internal transition state survives across toggles - that's what lets it play a real enter
    // animation instead of just appearing already-visible on first show.
    AnimatedVisibility(
        visible = active,
        enter = scaleIn(MotionTokens.expressiveEnter, transformOrigin = TransformOrigin(0.5f, 0.5f)) +
            fadeIn(tween(120)),
        // Holds fully opaque while the bubbles cascade out underneath, then clears whatever is
        // left. Scaling here too would fight their own scale-out.
        exit = fadeOut(tween(80, delayMillis = 200))
    ) {
        // Read off the enclosing transition rather than the gate's own flag: this has to turn
        // false when the panel starts leaving, not when the gesture ends.
        content(transition.targetState == EnterExitState.Visible)
    }
}
