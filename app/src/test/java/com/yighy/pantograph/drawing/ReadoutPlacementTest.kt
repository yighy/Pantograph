package com.yighy.pantograph.drawing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readout exists to be seen while a finger is on the chip, so every case below is one where
 * getting it wrong puts the value back under the hand, off the screen, or flickering.
 */
class ReadoutPlacementTest {

    private val w = 200
    private val h = 60
    private val windowW = 1080
    private val windowH = 2400
    private val gapAbove = 50
    private val gapBelow = 120
    private val sideHysteresis = 40
    private val margin = 20
    private val hysteresis = 30

    /** The finger height at which the readout exactly stops fitting above. */
    private val threshold = gapAbove + h + margin

    private fun staysAbove(fingerY: Int, wasAbove: Boolean) =
        ReadoutPlacement.staysAbove(fingerY, h, gapAbove, margin, wasAbove, hysteresis)

    private fun place(fingerX: Int, fingerY: Int, above: Boolean, leansLeft: Boolean = true) =
        ReadoutPlacement.aboveOrBelow(
            fingerX, fingerY, w, h, windowW, windowH, above, leansLeft, gapAbove, gapBelow, margin
        )

    // ---- which side ----

    @Test
    fun `above when there is room`() {
        assertTrue(staysAbove(fingerY = 600, wasAbove = true))
    }

    @Test
    fun `below when there is no room above`() {
        // The chips' usual case in fullscreen: a finger right up near the top of the screen.
        assertFalse(staysAbove(fingerY = 40, wasAbove = true))
    }

    @Test
    fun `it stays above right up to the height where it stops fitting`() {
        assertTrue(staysAbove(fingerY = threshold, wasAbove = true))
        assertFalse(staysAbove(fingerY = threshold - 1, wasAbove = true))
    }

    @Test
    fun `a finger jittering at the threshold does not flip it back up`() {
        // Having just gone below, a pixel back is not enough to return.
        assertFalse(staysAbove(fingerY = threshold + 1, wasAbove = false))
        assertFalse(staysAbove(fingerY = threshold + hysteresis - 1, wasAbove = false))
    }

    @Test
    fun `it returns above once there is room to spare`() {
        assertTrue(staysAbove(fingerY = threshold + hysteresis, wasAbove = false))
    }

    // ---- where ----

    @Test
    fun `above, it clears the fingertip`() {
        val (_, y) = place(fingerX = 500, fingerY = 600, above = true)
        assertEquals(600 - gapAbove - h, y)
        assertTrue(y + h <= 600 - gapAbove)
    }

    @Test
    fun `below, it clears the finger`() {
        val (_, y) = place(fingerX = 500, fingerY = 600, above = false)
        assertEquals(600 + gapBelow, y)
    }

    // ---- across ----

    private fun leansLeft(fingerX: Int, wasLeft: Boolean?) =
        ReadoutPlacement.leansLeft(fingerX, windowW, wasLeft, sideHysteresis)

    @Test
    fun `a press on the right half leans left, away from a right hand`() {
        assertTrue(leansLeft(fingerX = 900, wasLeft = null))
        assertTrue(leansLeft(fingerX = windowW / 2, wasLeft = null))
    }

    @Test
    fun `a press on the left half leans right, away from a left hand`() {
        assertFalse(leansLeft(fingerX = 100, wasLeft = null))
        assertFalse(leansLeft(fingerX = windowW / 2 - 1, wasLeft = null))
    }

    @Test
    fun `a drag that crosses the middle takes the readout to the other half`() {
        assertFalse(leansLeft(fingerX = windowW / 2 - sideHysteresis - 1, wasLeft = true))
        assertTrue(leansLeft(fingerX = windowW / 2 + sideHysteresis, wasLeft = false))
    }

    @Test
    fun `a finger jittering at the middle does not send it back and forth`() {
        // Just across, either way, is not enough to jump the whole window.
        assertTrue(leansLeft(fingerX = windowW / 2 - sideHysteresis + 1, wasLeft = true))
        assertFalse(leansLeft(fingerX = windowW / 2 + sideHysteresis - 1, wasLeft = false))
    }

    @Test
    fun `it sits half a window from the finger`() {
        val (left, _) = place(fingerX = 900, fingerY = 600, above = true, leansLeft = true)
        assertEquals(900 - windowW / 2 - w / 2, left)
        val (right, _) = place(fingerX = 100, fingerY = 600, above = true, leansLeft = false)
        assertEquals(100 + windowW / 2 - w / 2, right)
    }

    @Test
    fun `the nearer the finger comes, the farther it goes`() {
        // Leaning left, a finger moving left pushes the readout left ahead of it.
        val (far, _) = place(fingerX = 1000, fingerY = 600, above = true, leansLeft = true)
        val (near, _) = place(fingerX = 800, fingerY = 600, above = true, leansLeft = true)
        assertTrue(near < far)
        assertEquals(1000 - 800, far - near)
    }

    @Test
    fun `near the middle it is pinned to the edge rather than pushed off screen`() {
        val (x, _) = place(fingerX = windowW / 2 + 10, fingerY = 600, above = true, leansLeft = true)
        assertEquals(margin, x)
        val (y, _) = place(fingerX = windowW / 2 - 10, fingerY = 600, above = true, leansLeft = false)
        assertEquals(windowW - w - margin, y)
    }

    @Test
    fun `below the bottom of the window it is kept on screen`() {
        val (_, y) = place(fingerX = 500, fingerY = windowH - 10, above = false)
        assertEquals(windowH - h - margin, y)
    }
}
