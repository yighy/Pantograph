package com.yighy.pantograph.drawing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readout exists to be seen while a finger is on the chip, so every case below is one where
 * getting it wrong puts the value back under the hand or off the screen.
 */
class ReadoutPlacementTest {

    private val w = 200
    private val h = 60
    private val windowW = 1080
    private val windowH = 2400
    private val gap = 100
    private val margin = 30

    private fun place(fingerX: Int, fingerY: Int) =
        ReadoutPlacement.besideFinger(fingerX, fingerY, w, h, windowW, windowH, gap, margin)

    @Test
    fun `it sits to the left of the finger when there is room`() {
        val (x, _) = place(fingerX = 900, fingerY = 300)
        assertEquals(900 - gap - w, x)
    }

    @Test
    fun `it never overlaps the finger on the side it chose`() {
        val (x, _) = place(fingerX = 900, fingerY = 300)
        assertTrue("right edge ${x + w} should stay clear of the finger", x + w <= 900 - gap)
    }

    @Test
    fun `it moves to the right when the left has no room`() {
        val (x, _) = place(fingerX = 200, fingerY = 300)
        assertEquals(200 + gap, x)
    }

    @Test
    fun `it is centred on the fingertip vertically`() {
        val (_, y) = place(fingerX = 900, fingerY = 800)
        assertEquals(800 - h / 2, y)
    }

    @Test
    fun `it stays inside the top of the window`() {
        // The case the chips live in: a finger right up against the top of the screen.
        val (_, y) = place(fingerX = 900, fingerY = 10)
        assertEquals(margin, y)
    }

    @Test
    fun `it stays inside the bottom of the window`() {
        val (_, y) = place(fingerX = 900, fingerY = windowH)
        assertEquals(windowH - h - margin, y)
    }
}
