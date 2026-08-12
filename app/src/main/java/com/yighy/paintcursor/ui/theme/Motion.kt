package com.yighy.paintcursor.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Shared Material 3 Expressive motion specs — "Studio Dark" pacing: fast and purposeful,
 * a light settle rather than a visible bounce, so the tool reads as responsive, not playful.
 */
object MotionTokens {
    /** Small floating elements (gate panels, param/mode bubbles) entering the screen. */
    val expressiveEnter: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.86f, stiffness = 500f)

    /** Matching dismissal for [expressiveEnter] — snappier so it doesn't linger on exit. */
    val expressiveExit: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 600f)

    /** Tint/background cross-fades on selection or hover state changes. */
    val colorTransition: FiniteAnimationSpec<Color> = spring(dampingRatio = 0.85f, stiffness = 400f)

    /** Scale pulse for activation feedback (FAB pen-down, gate-active state). */
    val pulse: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.7f, stiffness = 450f)

    /** Docked panel expand/collapse (e.g. the drawing toolbar's animateContentSize). */
    val panelTransition: FiniteAnimationSpec<IntSize> = spring(dampingRatio = 0.85f, stiffness = 380f)

    /** Floating panels sliding in from off-screen (layer rail, layer-edit panel). */
    val slideEnter: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.86f, stiffness = 500f)

    /** Matching dismissal for [slideEnter]. */
    val slideExit: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.9f, stiffness = 600f)
}
