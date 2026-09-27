package com.yighy.pantograph.drawing

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Where a button sits in its group, which decides which of its corners are the group's. */
enum class GroupPosition { Only, First, Middle, Last }

private val ButtonSize = 48.dp
private val FullCorner = ButtonSize / 2
/** Between two buttons of a group: enough to read as separate buttons, small enough to read as one group. */
private val InnerCorner = 8.dp

/**
 * A row of buttons that read as one control: round at the group's two ends, nearly square where
 * two buttons meet, a hairline apart.
 */
@Composable
fun ButtonGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * One button of a [ButtonGroup]. Pressed, it fills with the primary colour and keeps its shape,
 * so the group's outline stays put as buttons go in and out.
 *
 * [isToggle] is whether [pressed] is the button's own on/off state, and so is announced as
 * "selected". A button that is only lit to say something else is on - the tools menu while a
 * tool is armed - leaves it false.
 *
 * [fill] replaces the button's own colour with one it is showing, such as the colour in hand.
 * The content then takes black or white, whichever reads on it, and being pressed can no longer
 * be a change of fill: it is a ring in that same black or white instead. Unpressed, a hairline
 * keeps the button's edge visible when the fill is close to what is behind it.
 */
@Composable
fun GroupedButton(
    position: GroupPosition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    isToggle: Boolean = false,
    enabled: Boolean = true,
    fill: Color? = null,
    content: @Composable () -> Unit
) {
    val start = if (position == GroupPosition.Only || position == GroupPosition.First) FullCorner else InnerCorner
    val end = if (position == GroupPosition.Only || position == GroupPosition.Last) FullCorner else InnerCorner
    val shape = RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)

    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (pressed) colors.primary else colors.secondaryContainer, label = "container")
    val themedContent by animateColorAsState(if (pressed) colors.onPrimary else colors.onSecondaryContainer, label = "content")
    // Not animated when it is a fill: it follows the colour live while a satellite drags it,
    // and an animation would trail behind the finger.
    // 0.18 is where black and white contrast equally with the fill (WCAG's formula); either
    // side of it, the one picked is the one that reads better.
    val contentColor = if (fill != null) {
        if (fill.luminance() > 0.18f) Color.Black else Color.White
    } else themedContent

    Box(
        modifier = modifier
            .size(ButtonSize)
            .clip(shape)
            .background(fill ?: container)
            .then(
                when {
                    fill == null -> Modifier
                    pressed -> Modifier.border(2.dp, contentColor, shape)
                    else -> Modifier.border(1.dp, colors.outlineVariant, shape)
                }
            )
            .then(if (isToggle) Modifier.semantics { selected = pressed } else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) contentColor else contentColor.copy(alpha = 0.38f)
        ) {
            content()
        }
    }
}
