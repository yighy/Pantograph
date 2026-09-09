package com.yighy.pantograph.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loupe moves out of its own way, and the failure everyone writes first is a window that
 * flaps between the two sides while the brush sits still. These are the properties that stop
 * that: it moves only when reached, and moving is enough to settle it.
 */
class LoupePlacementTest {

    private val viewport = Size(1000f, 2000f)
    private val window = 132f
    private val margin = 12f
    private val reach = 24f

    private fun nextLeft(currentlyLeft: Boolean, brush: Offset) =
        LoupePlacement.nextSideIsLeft(currentlyLeft, brush, viewport, window, margin, reach)

    // ---- where the window is ----

    @Test
    fun `each side is pinned to its edge and centred vertically`() {
        val left = LoupePlacement.windowRect(true, viewport, window, margin)
        assertEquals(12f, left.left)
        assertEquals(144f, left.right)
        assertEquals(934f, left.top)
        assertEquals(1066f, left.bottom)

        val right = LoupePlacement.windowRect(false, viewport, window, margin)
        assertEquals(856f, right.left)
        assertEquals(988f, right.right)
        // Same band: only the horizontal edge it hugs changes.
        assertEquals(left.top, right.top)
    }

    // ---- when it moves ----

    @Test
    fun `it stays put while the brush is nowhere near it`() {
        assertTrue(nextLeft(true, Offset(700f, 1000f)))
        assertFalse(nextLeft(false, Offset(300f, 1000f)))
    }

    @Test
    fun `the brush landing on the window sends it to the other side`() {
        assertFalse(nextLeft(true, Offset(80f, 1000f)))
        assertTrue(nextLeft(false, Offset(900f, 1000f)))
    }

    @Test
    fun `it leaves before the brush is on top of it, not once it already is`() {
        // Just outside the window's own edge but inside the reach: still a trigger, otherwise
        // the window would only move once it was already covering the work.
        val left = LoupePlacement.windowRect(true, viewport, window, margin)
        assertFalse(nextLeft(true, Offset(left.right + reach / 2f, 1000f)))
        // And beyond the reach it is left alone.
        assertTrue(nextLeft(true, Offset(left.right + reach * 2f, 1000f)))
    }

    @Test
    fun `one move settles it - the far side is not a trigger for coming back`() {
        val afterFirst = nextLeft(true, Offset(80f, 1000f))
        assertFalse(afterFirst)
        // Same brush position, now evaluated against the right-hand seat: no reason to move.
        assertFalse(nextLeft(afterFirst, Offset(80f, 1000f)))
    }

    @Test
    fun `a brush at the same height but across the screen does not move it`() {
        // The vertical band alone must not trigger: the window only cares about being stood on.
        assertTrue(nextLeft(true, Offset(500f, 1000f)))
    }

    @Test
    fun `a brush in the window's column but far above it does not move it`() {
        assertTrue(nextLeft(true, Offset(80f, 200f)))
    }
}
