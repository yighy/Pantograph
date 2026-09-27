package com.yighy.pantograph.drawing

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One thing that is quietly changing how the canvas behaves, and a tap to stop it.
 *
 * Every state that gets a chip here is a toggle, so the chip hands the job straight back to
 * whatever the menus already call - there is no second way to switch something off that would
 * have to be kept in step with this one.
 */
@Composable
internal fun ActiveStateChip(
    icon: ImageVector,
    label: String,
    description: String,
    onDismiss: () -> Unit,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    /** Null for a chip that performs an action rather than switching something off. */
    trailing: ImageVector? = Icons.Rounded.Close,
    /** Shown after the label, for a chip whose tool carries values you can drag. */
    values: List<ChipValue> = emptyList(),
    /**
     * Stands in for the plain click when the chip is also a gate. It has to take the tap as
     * well: a clickable underneath would claim the pointer before a drag could be told apart.
     */
    gesture: Modifier? = null,
    customActions: List<CustomAccessibilityAction> = emptyList(),
    /**
     * Where the active value sits in its range, 0..1, drawn as a fill from the start edge -
     * the chip reads as a small slider. Null for a chip with no value.
     */
    fill: Float? = null,
    fillColor: Color = Color.Unspecified,
    /** The ways the chip can be dragged, shown before the cross. Null for a chip with no value. */
    dragHint: ImageVector? = null,
    border: BorderStroke? = null
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
        border = border,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(gesture ?: Modifier.clickable(onClick = onDismiss))
            .semantics {
                contentDescription = description
                // With no clickable there is no click action for a screen reader to find, so
                // it is stated here. The tap means what it always meant.
                if (gesture != null) onClick(label = "Turn off") { onDismiss(); true }
                if (customActions.isNotEmpty()) this.customActions = customActions
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                // Across the width rather than up the height, even though the drag is vertical:
                // a pill this short has a few pixels of height to show a value in, and a
                // hundred-odd of width.
                .then(if (fill == null) Modifier else Modifier.gauge(fill, fillColor, track = false))
                .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
            if (values.isNotEmpty()) {
                // Tabular figures, so the digits do not change width between themselves either.
                val valueStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
                Spacer(Modifier.width(5.dp))
                values.forEachIndexed { i, v ->
                    if (i > 0) Spacer(Modifier.width(3.dp))
                    ValueSegment(v, fillColor, valueStyle, padding = 4.dp)
                }
            }
            // Arrows for the drag, as the cross is for the tap: without them the only sign the
            // chip could be dragged at all was a value that happened to be printed on it.
            dragHint?.let {
                Spacer(Modifier.width(4.dp))
                Icon(it, contentDescription = null, modifier = Modifier.size(14.dp).alpha(0.8f))
            }
            // The cross is the affordance: a chip that only named the state would read as a
            // label, and nobody taps a label. An action chip leads with its own glyph instead,
            // so it does not promise to turn anything off.
            trailing?.let {
                Spacer(Modifier.width(3.dp))
                Icon(it, contentDescription = null, modifier = Modifier.size(13.dp))
            }
        }
    }
}

/**
 * Draws where a value sits in its range behind whatever this modifies, filling from the start
 * edge. [track] adds a faint band under the whole width: a value low in its range - a tolerance
 * of 12 out of 200 - is a sliver, and a sliver with nothing beside it reads as no gauge at all.
 */
private fun Modifier.gauge(fill: Float, color: Color, track: Boolean): Modifier = drawBehind {
    if (track) drawRect(color.copy(alpha = color.alpha * 0.4f))
    val w = size.width * fill.coerceIn(0f, 1f)
    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
    drawRect(color, topLeft = Offset(left, 0f), size = Size(w, size.height))
}

/**
 * One value, with its own gauge behind it when it has one. Shared by the chip and its readout
 * so the two cannot come to show the same value differently.
 */
@Composable
private fun ValueSegment(v: ChipValue, fillColor: Color, style: TextStyle, padding: Dp) {
    Row(
        modifier = Modifier
            // The value a vertical drag would change reads at full strength and the other is
            // dimmed, so it is clear which one is held before anything moves.
            .alpha(if (v.active) 1f else 0.55f)
            .then(
                if (v.fill == null) Modifier
                else Modifier
                    .clip(RoundedCornerShape(50))
                    .gauge(v.fill, fillColor, track = true)
                    .padding(horizontal = padding)
            )
    ) {
        v.short?.let { Text("$it ", style = style) }
        // Held at the widest the value gets - see ToolParam.widest.
        Box {
            Text(v.widest, style = style, modifier = Modifier.alpha(0f))
            Text(v.text, style = style)
        }
    }
}

/**
 * The chip, larger and out from under the finger, for as long as its value is being dragged.
 *
 * The finger on a chip covers the very number it is changing, and the gauge with it. This is
 * the same content by the same rules - values, gauges, which one is held - put where it can be
 * read. See [ReadoutPlacement] for where that is.
 */
@Composable
private fun ChipReadout(
    icon: ImageVector,
    label: String,
    values: List<ChipValue>,
    fill: Float?,
    fillColor: Color
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        shadowElevation = 6.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(if (fill == null) Modifier else Modifier.gauge(fill, fillColor, track = false))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            val style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
            values.forEachIndexed { i, v ->
                if (i > 0) Spacer(Modifier.width(6.dp))
                ValueSegment(v, fillColor, style, padding = 6.dp)
            }
        }
    }
}

/** Clearance above the point of contact: only the tip of the finger is up there. */
private val READOUT_GAP_ABOVE = 36.dp

/** Clearance below it, where the rest of the finger follows. */
private val READOUT_GAP_BELOW = 72.dp

private val READOUT_MARGIN = 8.dp

/** Room to spare before the readout returns above - see [ReadoutPlacement.staysAbove]. */
private val READOUT_HYSTERESIS = 16.dp

/** How far past the middle the finger goes before the readout changes halves. */
private val READOUT_SIDE_HYSTERESIS = 16.dp

/**
 * Hands [ReadoutPlacement] the finger in window coordinates. Which side is decided elsewhere,
 * in the gesture: the choice depends on the side it was on a moment ago, and a position
 * provider is asked for a position, not given somewhere to remember one.
 *
 * A data class so the popup only repositions when something has actually changed: it compares
 * providers to decide.
 */
private data class AboveOrBelowFinger(
    val fingerX: Float,
    val fingerY: Float,
    val above: Boolean,
    val leansLeft: Boolean,
    val gapAbovePx: Int,
    val gapBelowPx: Int,
    val marginPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        // The finger arrives relative to the chip; the anchor is the chip, placed in the window.
        val (x, y) = ReadoutPlacement.aboveOrBelow(
            fingerX = anchorBounds.left + fingerX.roundToInt(),
            fingerY = anchorBounds.top + fingerY.roundToInt(),
            width = popupContentSize.width,
            height = popupContentSize.height,
            windowWidth = windowSize.width,
            windowHeight = windowSize.height,
            above = above,
            leansLeft = leansLeft,
            gapAbove = gapAbovePx,
            gapBelow = gapBelowPx,
            margin = marginPx
        )
        return IntOffset(x, y)
    }
}

/** One value as a chip shows it. */
internal class ChipValue(
    val short: String?,
    val text: String,
    val widest: String,
    val active: Boolean,
    /** Where this value sits in its range, 0..1, when it is drawn as a gauge of its own. */
    val fill: Float? = null
)

/**
 * A tool's chip. Tapping still turns the tool off. If the tool carries a value, dragging on the
 * chip adjusts it the way a satellite gate adjusts a brush setting: the same response curve, dead
 * zone and pinning at the ends, and the same sensitivity setting behind them. The travel is the
 * one thing that differs, and deliberately - see the gesture below.
 *
 * The chip is the right place because it is already exactly the set of things there is to set:
 * a parameter matters while its tool is armed, which is precisely when its chip is showing, in
 * fullscreen and out of it.
 */
@Composable
internal fun ToolStateChip(
    tool: PinnableTool,
    viewModel: DrawingViewModel
) {
    val onDismiss = { tool.toggle(viewModel) }
    val params = tool.params
    if (params.isEmpty()) {
        ActiveStateChip(
            icon = tool.icon,
            label = tool.label,
            description = "${tool.label} is on, tap to turn it off",
            onDismiss = onDismiss
        )
        return
    }

    // The initial value is read once, with the flow, rather than rebuilt from the state on
    // every recomposition only to be ignored after the first.
    val (currentFlow, initial) = remember(viewModel, tool) {
        viewModel.uiState.map { st -> params.map { it.read(st) } }.distinctUntilChanged() to
            params.map { it.read(viewModel.uiState.value) }
    }
    val current by currentFlow.collectAsState(initial)
    // Which value a vertical drag changes. Kept between drags, the way the colour gate keeps its
    // column, so a second adjustment starts on the value the last one left off on.
    var selected by remember(tool) { mutableIntStateOf(0) }
    val sensitivity by remember(viewModel) {
        viewModel.uiState.map { it.satelliteGateSensitivity }.distinctUntilChanged()
    }.collectAsState(1f)
    val haptics = LocalHapticFeedback.current
    var adjusting by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    // Where the finger is on the chip, for the readout to sit above or below it.
    var fingerX by remember { mutableFloatStateOf(0f) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    // What deciding the readout's sides needs: where the chip is in the window, how wide the
    // window is, how tall the readout is - an estimate until it has been drawn once - and which
    // sides it is on now.
    var chipTopInWindow by remember { mutableFloatStateOf(0f) }
    var chipLeftInWindow by remember { mutableFloatStateOf(0f) }
    var windowWidthPx by remember { mutableIntStateOf(0) }
    var readoutHeightPx by remember { mutableIntStateOf(with(density) { 44.dp.roundToPx() }) }
    var readoutAbove by remember { mutableStateOf(true) }
    var readoutLeft by remember { mutableStateOf(true) }
    // The gesture outlives recompositions of the chip; reading the dismiss through this makes
    // a tap act on the chip as it is when the finger lifts, not as it was when it landed.
    val currentDismiss by rememberUpdatedState(onDismiss)

    val gesture = Modifier.pointerInput(tool, sensitivity) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            // The dead zone and sensitivity come straight from the satellite gates. The travel
            // is half theirs. A satellite sits mid-screen with room both ways; a chip sits at
            // the top, so pushing up - raising the value - has only the gap to the screen edge
            // to work with, and at the satellites' length that bought about a tenth of the range
            // per push. Down, towards the canvas, has room to spare either way.
            val s = sensitivity.coerceIn(0.25f, 4f)
            val deadZonePx = 12.dp.toPx() / s
            val travelPx = 150.dp.toPx() / s
            // Sideways picks the value, as the colour gate's columns do: same step, same
            // hysteresis, same anchor clamping. A chip with a single value is a single column,
            // and the maths simply never leaves it.
            val colStepPx = 56.dp.toPx()
            val colHysteresisPx = 10.dp.toPx()
            val startIndex = selected.coerceIn(0, params.size - 1)
            val minRel = -startIndex
            val maxRel = params.size - 1 - startIndex
            var index = startIndex
            var committedRel = 0
            var anchorX = down.position.x
            var anchorValue = params[index].read(viewModel.uiState.value)
            var anchorY = down.position.y
            var dragging = false
            var inDeadZone = true
            fingerX = down.position.x
            fingerY = down.position.y
            // A fresh gesture starts from the preferred side and moves off it only if it must.
            readoutAbove = true
            val gapAbovePx = READOUT_GAP_ABOVE.roundToPx()
            val readoutMarginPx = READOUT_MARGIN.roundToPx()
            val hysteresisPx = READOUT_HYSTERESIS.roundToPx()
            val sideHysteresisPx = READOUT_SIDE_HYSTERESIS.roundToPx()
            readoutLeft = ReadoutPlacement.leansLeft(
                (chipLeftInWindow + down.position.x).roundToInt(), windowWidthPx, null, sideHysteresisPx
            )
            // Only a real lift counts as a tap. A cancelled gesture also leaves the loop, and
            // turning a tool off because the system took the pointer away is not a request.
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        released = true
                        break
                    }
                    if (!dragging) {
                        // Past the slop in any direction and this is a drag for good. One that
                        // wandered back to where it started would otherwise lift as a tap, and
                        // switch the tool off in the middle of adjusting it.
                        if ((change.position - down.position).getDistance() <= viewConfiguration.touchSlop) continue
                        dragging = true
                        adjusting = true
                    }
                    change.consume()
                    fingerX = change.position.x
                    fingerY = change.position.y
                    readoutAbove = ReadoutPlacement.staysAbove(
                        fingerY = (chipTopInWindow + change.position.y).roundToInt(),
                        height = readoutHeightPx,
                        gapAbove = gapAbovePx,
                        margin = readoutMarginPx,
                        wasAbove = readoutAbove,
                        hysteresis = hysteresisPx
                    )
                    readoutLeft = ReadoutPlacement.leansLeft(
                        (chipLeftInWindow + change.position.x).roundToInt(), windowWidthPx, readoutLeft, sideHysteresisPx
                    )
                    anchorX = GateMath.clampColumnAnchor(
                        anchorX = anchorX,
                        fingerX = change.position.x,
                        colStepPx = colStepPx,
                        minRel = minRel,
                        maxRel = maxRel
                    )
                    val newRel = GateMath.nextColumn(
                        committedRel, change.position.x - anchorX, colStepPx, colHysteresisPx
                    )
                    val newIndex = (startIndex + newRel).coerceIn(0, params.size - 1)
                    if (newIndex != index) {
                        index = newIndex
                        committedRel = newRel.coerceIn(minRel, maxRel)
                        selected = newIndex
                        // Re-anchored on the new value, where the finger is now, so the vertical
                        // travel spent on one value is not carried into the other.
                        anchorY = change.position.y
                        anchorValue = params[index].read(viewModel.uiState.value)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        inDeadZone = true
                    }
                    val param = params[index]
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
                        min = param.min,
                        max = param.max,
                        deadZonePx = deadZonePx,
                        travelPx = travelPx
                    )
                    anchorValue = stepped.anchorValue
                    anchorY = stepped.anchorPos
                    param.write(viewModel, stepped.value)
                }
            } finally {
                adjusting = false
            }
            if (released && !dragging) currentDismiss()
        }
    }

    // A value you can only reach by dragging is a value a screen reader cannot reach at all.
    // Tenth-of-range steps for each, as the colour gate offers for the same reason.
    val actions = params.flatMap { param ->
        val step = (param.max - param.min) / 10f
        listOf(true, false).map { increase ->
            CustomAccessibilityAction(
                label = "${if (increase) "Increase" else "Decrease"} ${param.label.lowercase()}"
            ) {
                val now = param.read(viewModel.uiState.value)
                param.write(viewModel, (now + if (increase) step else -step).coerceIn(param.min, param.max))
                true
            }
        }
    }

    val readout = params.indices.joinToString(", ") { i ->
        "${params[i].label.lowercase()} ${params[i].format(current[i])}"
    }
    val howTo = if (params.size == 1) "drag up or down to change it"
        else "drag up or down to change ${params[selected].label.lowercase()}, sideways to pick another"

    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = if (adjusting) 0.30f else 0.18f)
    // One value fills the whole chip, the widest gauge it can have. Several each get a gauge of
    // their own behind their own numbers: a single fill for the active one left the other
    // value's position unshown until you switched to it.
    val wholeFill = if (params.size == 1) (current[0] - params[0].min) / (params[0].max - params[0].min) else null
    val shown = params.mapIndexed { i, param ->
        ChipValue(
            // A lone value needs no name - the tool's is right beside it.
            short = if (params.size > 1) param.short else null,
            text = param.format(current[i]),
            widest = param.widest,
            active = params.size == 1 || i == selected,
            fill = if (params.size > 1) (current[i] - param.min) / (param.max - param.min) else null
        )
    }

    // The box is the popup's anchor: it is exactly the chip, which is what the finger
    // coordinates from the gesture are relative to.
    Box(modifier = Modifier.onGloballyPositioned {
        val inWindow = it.positionInWindow()
        chipTopInWindow = inWindow.y
        chipLeftInWindow = inWindow.x
        windowWidthPx = it.findRootCoordinates().size.width
    }) {
        ActiveStateChip(
            icon = tool.icon,
            label = tool.label,
            description = "${tool.label} is on, $readout. Tap to turn it off, $howTo",
            onDismiss = onDismiss,
            // Stronger, and outlined, while held: the chip visibly has hold of the value rather
            // than a number simply starting to move in the corner. Translucent throughout so one
            // text colour reads over both parts - a solid fill would need a different one either
            // side of its edge.
            fill = wholeFill,
            fillColor = fillColor,
            border = if (adjusting) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
            // Four ways where sideways picks between values, up and down where there is only one.
            dragHint = if (params.size > 1) Icons.Rounded.OpenWith else Icons.Rounded.UnfoldMore,
            values = shown,
            gesture = gesture,
            customActions = actions
        )
        // Only while dragging: a tap is over before anyone could read it, and the chip's own
        // numbers are visible whenever no finger is on them.
        if (adjusting) {
            Popup(
                popupPositionProvider = AboveOrBelowFinger(
                    fingerX = fingerX,
                    fingerY = fingerY,
                    above = readoutAbove,
                    leansLeft = readoutLeft,
                    gapAbovePx = with(density) { READOUT_GAP_ABOVE.roundToPx() },
                    gapBelowPx = with(density) { READOUT_GAP_BELOW.roundToPx() },
                    marginPx = with(density) { READOUT_MARGIN.roundToPx() }
                )
            ) {
                Box(modifier = Modifier.onSizeChanged { readoutHeightPx = it.height }) {
                    ChipReadout(tool.icon, tool.label, shown, wholeFill, fillColor)
                }
            }
        }
    }
}
