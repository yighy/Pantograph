package com.yighy.paintcursor.drawing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the wand and colour-select tools actually grab. The difference between the two is only
 * the contiguity walk, and the transparent-pixel rule is the sort of special case that quietly
 * stops working.
 */
class MagicSelectTest {

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun selected(mask: IntArray, x: Int, y: Int, width: Int) = mask[y * width + x] == MagicSelect.SELECTED

    /**
     * 5x1 strip: two red pixels, a blue one, then two more red.
     * The wand tapped at the left should stop at the blue; colour select shouldn't.
     */
    private fun splitStrip() = intArrayOf(red, red, blue, red, red)

    @Test
    fun `wand takes only the connected run`() {
        val mask = MagicSelect.computeMask(splitStrip(), 5, 1, startX = 0, startY = 0, tolerance = 0, contiguous = true)

        assertTrue(selected(mask, 0, 0, 5))
        assertTrue(selected(mask, 1, 0, 5))
        assertFalse("the blue pixel is not a match", selected(mask, 2, 0, 5))
        assertFalse("cut off by the blue pixel", selected(mask, 3, 0, 5))
        assertFalse(selected(mask, 4, 0, 5))
    }

    @Test
    fun `colour select takes every match wherever it sits`() {
        val mask = MagicSelect.computeMask(splitStrip(), 5, 1, startX = 0, startY = 0, tolerance = 0, contiguous = false)

        assertTrue(selected(mask, 0, 0, 5))
        assertTrue(selected(mask, 1, 0, 5))
        assertFalse(selected(mask, 2, 0, 5))
        assertTrue("unreachable by the wand, still the same colour", selected(mask, 3, 0, 5))
        assertTrue(selected(mask, 4, 0, 5))
    }

    @Test
    fun `transparent pixels match on alpha alone`() {
        // Same zero alpha, wildly different (and meaningless) RGB
        val pixels = intArrayOf(0x00000000, 0x00FF00FF, 0x0012AB34, 0xFF000000.toInt())

        val mask = MagicSelect.computeMask(pixels, 4, 1, startX = 0, startY = 0, tolerance = 0, contiguous = true)

        assertTrue(selected(mask, 0, 0, 4))
        assertTrue("RGB is meaningless at zero alpha", selected(mask, 1, 0, 4))
        assertTrue(selected(mask, 2, 0, 4))
        assertFalse("opaque black is a real colour", selected(mask, 3, 0, 4))
    }

    @Test
    fun `tolerance widens the match`() {
        val nearRed = 0xFFFA0000.toInt()
        val pixels = intArrayOf(red, nearRed)

        val strict = MagicSelect.computeMask(pixels, 2, 1, 0, 0, tolerance = 0, contiguous = true)
        assertFalse(selected(strict, 1, 0, 2))

        val lenient = MagicSelect.computeMask(pixels, 2, 1, 0, 0, tolerance = 5, contiguous = true)
        assertTrue(selected(lenient, 1, 0, 2))
    }

    @Test
    fun `a tap outside the canvas selects nothing`() {
        val mask = MagicSelect.computeMask(intArrayOf(red, red), 2, 1, startX = 7, startY = 0, tolerance = 0, contiguous = true)

        assertEquals(0, mask.count { it != 0 })
    }
}
