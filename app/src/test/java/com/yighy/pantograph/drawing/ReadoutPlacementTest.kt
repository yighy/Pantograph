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
    private val margin = 20
    private val hysteresis = 30

    /** The finger height at which the readout exactly stops fitting above. */
    private val threshold = gapAbove + h + margin

    private fun staysAbove(fingerY: Int, wasAbove: Boolean) =
        ReadoutPlacement.staysAbove(fingerY, h, gapAbove, margin, wasAbove, hysteresis)

    private fun place(fingerX: Int, fingerY: Int, above: Boolean) =
        ReadoutPlacement.aboveOrBelow(fingerX, fingerY, w, h, windowW, windowH, above, gapAbove, gapBelow, margin)

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

    @Test
    fun `it is centred on the finger across`() {
        val (x, _) = place(fingerX = 500, fingerY = 600, above = true)
        assertEquals(500 - w / 2, x)
    }

    @Test
    fun `against the right edge it is kept on screen`() {
        // Where the chips live: packed against the right edge.
        val (x, _) = place(fingerX = windowW - 10, fingerY = 600, above = true)
        assertEquals(windowW - w - margin, x)
    }

    @Test
    fun `below the bottom of the window it is kept on screen`() {
        val (_, y) = place(fingerX = 500, fingerY = windowH - 10, above = false)
        assertEquals(windowH - h - margin, y)
    }
}
