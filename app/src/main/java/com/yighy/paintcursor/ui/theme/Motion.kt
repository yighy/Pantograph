package com.yighy.paintcursor.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color

/**
 * Shared Material 3 Expressive motion specs, so the hover-draw satellite's gate panels and
 * bubbles all pop with the same spring feel instead of each call site tuning its own numbers.
 */
object MotionTokens {
    /** Small floating elements (gate panels, param/mode bubbles) entering the screen. */
    val expressiveEnter: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 380f)

    /** Matching dismissal for [expressiveEnter] — snappier so it doesn't linger on exit. */
    val expressiveExit: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.7f, stiffness = 500f)

    /** Tint/background cross-fades on selection or hover state changes. */
    val colorTransition: FiniteAnimationSpec<Color> = spring(dampingRatio = 0.75f, stiffness = 300f)
}
