package com.yighy.pantograph.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * Where the loupe window sits, and how it gets out of its own way.
 *
 * Pure geometry so it can be tested: a window that decides its own side from the brush position
 * is exactly the kind of thing that looks right until the one case nobody tried.
 */
object LoupePlacement {

    /**
     * The window's rectangle on the given side: pinned to that edge, centred vertically.
     */
    fun windowRect(onLeft: Boolean, viewport: Size, windowPx: Float, marginPx: Float): Rect {
        val left = if (onLeft) marginPx else viewport.width - marginPx - windowPx
        val top = (viewport.height - windowPx) / 2f
        return Rect(left, top, left + windowPx, top + windowPx)
    }

    /**
     * Which side the window should be on, given the side it is on now.
     *
     * It moves only when the brush actually reaches the window - not when the brush crosses the
     * middle of the screen, which is the obvious rule and the wrong one: the brush sits on that
     * line often, and the window would flap across the screen every time it did. Reaching the
     * window is a condition that stops being true the moment the window answers it, since the
     * other side is a screen away, so one move settles it.
     *
     * [reachPx] widens the trigger a little past the window's own edge, so the window leaves
     * before the brush is under it rather than once it already is.
     */
    fun nextSideIsLeft(
        currentlyLeft: Boolean,
        brushOnScreen: Offset,
        viewport: Size,
        windowPx: Float,
        marginPx: Float,
        reachPx: Float
    ): Boolean {
        val here = windowRect(currentlyLeft, viewport, windowPx, marginPx)
        val reached = brushOnScreen.x >= here.left - reachPx &&
            brushOnScreen.x <= here.right + reachPx &&
            brushOnScreen.y >= here.top - reachPx &&
            brushOnScreen.y <= here.bottom + reachPx
        return if (reached) !currentlyLeft else currentlyLeft
    }
}
