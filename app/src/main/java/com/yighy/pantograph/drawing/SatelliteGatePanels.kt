package com.yighy.pantograph.drawing

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.yighy.pantograph.ui.theme.MotionTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The colour satellite's four columns.
 *
 * [Pipette] is the odd one out: it has no level, so its vertical axis is inert and it fires on
 * release instead. It is a column rather than a separate button because the eyedropper belongs
 * with the colour controls, and a fourth pill orbiting the button would have cost more room
 * than the whole gate.
 */
internal enum class ColourGateParam(
    val label: String,
    val min: Float,
    val max: Float,
    /** See [GateMath.step]: true only where the two ends of the range are the same value. */
    val wraps: Boolean = false
) {
    // Declaration order is the column order in the gate and in its readout panel.
    Hue("Hue", 0f, 360f, wraps = true),
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

/**
 * Runs a gate panel's bubbles in and out one beat apart.
 *
 * Outward the order reverses, so the row closes from its far end back towards the finger
 * instead of the whole thing blinking off at once - the enter already cascaded, and only
 * having one half of the pair was what made a gate feel like it was snatched away.
 *
 * The panel above must stay mounted for the whole outward cascade, which is why the enclosing
 * AnimatedVisibility exits on a delayed fade rather than a scale.
 */
@Composable
private fun StaggeredBubbles(flags: SnapshotStateList<Boolean>, visible: Boolean) {
    LaunchedEffect(visible) {
        val order = if (visible) flags.indices.toList() else flags.indices.reversed().toList()
        order.forEachIndexed { beat, i ->
            launch {
                delay(beat * 30L)
                flags[i] = visible
            }
        }
    }
}

/** The four brush parameters reachable from the right satellite, with their slider ranges. */
internal enum class BrushGateParam(val label: String, val min: Float, val max: Float) {
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
internal fun BrushGatePanel(
    paramIndex: Int,
    value: Float,
    currentValues: List<Float>,
    fingerX: Float,
    fingerY: Float,
    screenWidth: Float,
    screenHeight: Float,
    /** False the moment the panel starts leaving, which is what drives the outward cascade. */
    visible: Boolean
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

    val bubbleVisible = remember { mutableStateListOf(false, false, false, false) }
    StaggeredBubbles(bubbleVisible, visible)

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
                    enter = fadeIn(tween(160)) + scaleIn(MotionTokens.expressiveEnter, initialScale = 0.55f),
                    exit = fadeOut(tween(120)) + scaleOut(MotionTokens.expressiveExit, targetScale = 0.55f)
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
internal fun ColourGatePanel(
    paramIndex: Int,
    value: Float,
    hsv: Hsv,
    swatch: Color,
    eyeDropperArmed: Boolean,
    fingerX: Float,
    fingerY: Float,
    screenWidth: Float,
    screenHeight: Float,
    /** False the moment the panel starts leaving, which is what drives the outward cascade. */
    visible: Boolean
) {
    val density = LocalDensity.current
    // The hue strip is only worth its height while hue is the column being worked, so the
    // panel grows for it and shrinks back. The placement below reads the height it actually
    // has, so it keeps clearing the finger either way.
    val hueSelected = ColourGateParam.entries[paramIndex] == ColourGateParam.Hue
    val panelW = 288.dp
    // Animated, not switched: the height feeds the placement maths below, so snapping it
    // teleported the whole panel the instant hue was reached or left.
    val panelH by animateDpAsState(
        targetValue = if (hueSelected) 84.dp else 56.dp,
        animationSpec = MotionTokens.panelHeight,
        label = "colourPanelHeight"
    )
    val panelWPx = with(density) { panelW.toPx() }
    val panelHPx = with(density) { panelH.toPx() }
    val fingerGapPx = with(density) { 72.dp.toPx() }
    val px = (fingerX - panelWPx / 2f).coerceIn(0f, (screenWidth - panelWPx).coerceAtLeast(0f))
    val above = fingerY - panelHPx - fingerGapPx
    val py = (if (above >= 0f) above else fingerY + fingerGapPx)
        .coerceIn(0f, (screenHeight - panelHPx).coerceAtLeast(0f))

    val bubbleVisible = remember { mutableStateListOf(false, false, false, false) }
    StaggeredBubbles(bubbleVisible, visible)

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
                        enter = fadeIn(tween(160)) + scaleIn(MotionTokens.expressiveEnter, initialScale = 0.55f),
                        exit = fadeOut(tween(120)) + scaleOut(MotionTokens.expressiveExit, targetScale = 0.55f)
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

            // Grows and folds away with the hue column rather than appearing whole: it is the
            // one part of this panel that comes and goes mid-gesture, and a bare `if` made it
            // blink in and out while the finger was still moving.
            AnimatedVisibility(
                visible = hueSelected && visible,
                enter = expandVertically(MotionTokens.panelTransition, expandFrom = Alignment.Top) +
                    fadeIn(tween(140)),
                exit = shrinkVertically(MotionTokens.panelTransition, shrinkTowards = Alignment.Top) +
                    fadeOut(tween(90))
            ) {
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
internal fun ModeGatePanel(
    hoveredCell: Int,
    isLineMode: Boolean,
    isEraserMode: Boolean,
    swapTarget: String?,
    fingerX: Float,
    fingerY: Float,
    screenWidth: Float,
    screenHeight: Float,
    /** False the moment the panel starts leaving, which is what drives the outward cascade. */
    visible: Boolean
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

    val bubbleVisible = remember { mutableStateListOf(false, false, false, false) }
    StaggeredBubbles(bubbleVisible, visible)

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
        enter = fadeIn(tween(160)) + scaleIn(MotionTokens.expressiveEnter, initialScale = 0.55f),
        exit = fadeOut(tween(120)) + scaleOut(MotionTokens.expressiveExit, targetScale = 0.55f)
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
internal fun ToolGatePanel(
    tools: List<PinnableTool>,
    activeFlags: List<Boolean>,
    hoveredCell: Int,
    fingerX: Float,
    fingerY: Float,
    screenWidth: Float,
    screenHeight: Float,
    /** False the moment the panel starts leaving, which is what drives the outward cascade. */
    visible: Boolean
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

    val bubbleVisible = remember { mutableStateListOf(false, false, false, false) }
    StaggeredBubbles(bubbleVisible, visible)

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
        enter = fadeIn(tween(160)) + scaleIn(MotionTokens.expressiveEnter, initialScale = 0.55f),
        exit = fadeOut(tween(120)) + scaleOut(MotionTokens.expressiveExit, targetScale = 0.55f)
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
