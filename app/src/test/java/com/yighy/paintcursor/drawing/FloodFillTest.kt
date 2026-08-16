package com.yighy.paintcursor.drawing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bucket's containment rules. A fill that leaks one pixel past a line ruins the drawing and
 * is only visible after the fact, which is exactly the kind of mistake worth pinning down here.
 */
class FloodFillTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()

    /** 5x5 of [white] with a solid [black] wall down column 2. */
    private fun walledGrid(): IntArray = IntArray(25) { if (it % 5 == 2) black else white }

    @Test
    fun `fill stops at a wall`() {
        val pixels = walledGrid()

        FloodFill.fill(pixels, 5, 5, 0, 0, white, red, tolerance = 0f)

        for (y in 0 until 5) {
            for (x in 0 until 5) {
                val expected = when {
                    x < 2 -> red      // the side the fill started on
                    x == 2 -> black   // the wall itself
                    else -> white     // unreachable without crossing the wall
                }
                assertEquals("pixel ($x,$y)", expected, pixels[y * 5 + x])
            }
        }
    }

    @Test
    fun `fill spreads around a wall that does not span the canvas`() {
        val pixels = walledGrid()
        pixels[4 * 5 + 2] = white // open a gap in the bottom row

        FloodFill.fill(pixels, 5, 5, 0, 0, white, red, tolerance = 0f)

        // Everything that isn't wall is now reachable through the gap
        for (y in 0 until 5) {
            for (x in 0 until 5) {
                val isWall = x == 2 && y < 4
                assertEquals("pixel ($x,$y)", if (isWall) black else red, pixels[y * 5 + x])
            }
        }
    }

    @Test
    fun `a selection mask confines the fill`() {
        val pixels = IntArray(25) { white }
        // Selected: the left two columns only
        val mask = IntArray(25) { if (it % 5 < 2) 0xFFFFFFFF.toInt() else 0 }

        FloodFill.fill(pixels, 5, 5, 0, 0, white, red, tolerance = 0f, maskPixels = mask)

        for (y in 0 until 5) {
            for (x in 0 until 5) {
                assertEquals("pixel ($x,$y)", if (x < 2) red else white, pixels[y * 5 + x])
            }
        }
    }

    @Test
    fun `tolerance decides whether a near match is swallowed`() {
        val nearWhite = 0xFFFAFAFA.toInt() // 5 off on every channel

        val strict = IntArray(4) { white }.also { it[3] = nearWhite }
        FloodFill.fill(strict, 2, 2, 0, 0, white, red, tolerance = 0f)
        assertEquals("just outside a zero tolerance", nearWhite, strict[3])

        val lenient = IntArray(4) { white }.also { it[3] = nearWhite }
        FloodFill.fill(lenient, 2, 2, 0, 0, white, red, tolerance = 5f)
        assertEquals("within tolerance", red, lenient[3])
    }

    @Test
    fun `a start outside the canvas fills nothing`() {
        val pixels = IntArray(4) { white }

        FloodFill.fill(pixels, 2, 2, 5, 5, white, red, tolerance = 0f)

        assertEquals(listOf(white, white, white, white), pixels.toList())
    }
}
