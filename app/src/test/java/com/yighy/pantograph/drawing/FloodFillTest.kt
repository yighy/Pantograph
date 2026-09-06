package com.yighy.pantograph.drawing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `growing reaches under the edge that stopped the fill`() {
        // A 7x1 strip: empty middle, then a half-covered pixel either side standing in for the
        // fade of an antialiased line, then the line itself. A plain fill stops at the fade.
        val line = 0xFF000000.toInt()
        val fade = 0x80000000.toInt()
        val fill = 0xFFFF0000.toInt()
        fun strip() = intArrayOf(line, fade, 0, 0, 0, fade, line)

        val plain = strip()
        FloodFill.fill(plain, 7, 1, 3, 0, 0, fill, tolerance = 0f)
        assertEquals("the fade should be left alone without a grow", fade, plain[1])
        assertEquals(fill, plain[3])

        val grown = strip()
        FloodFill.fill(grown, 7, 1, 3, 0, 0, fill, tolerance = 0f, growPixels = 1)
        assertTrue("the fade pixel should have been reached", grown[1] != fade)
        assertEquals("the line itself must not be touched", line, grown[0])
    }

    @Test
    fun `a grown pixel keeps the edge's own colour and coverage`() {
        // The fill goes underneath, so a half-covered black edge over red comes out fully
        // opaque and dark - not flat red, which is what overwriting would give and what would
        // eat the antialiasing the grow exists to preserve.
        val blended = FloodFill.under(top = 0x80000000.toInt(), bottom = 0xFFFF0000.toInt())
        assertEquals("should end up opaque", 255, (blended ushr 24) and 0xFF)
        assertTrue("should still be darkened by the edge", ((blended ushr 16) and 0xFF) < 200)
        assertTrue("should still carry the fill's red", ((blended ushr 16) and 0xFF) > 50)
    }

    @Test
    fun `growing stops at the selection mask`() {
        val fill = 0xFFFF0000.toInt()
        val pixels = intArrayOf(0, 0, 0, 0, 0)
        // Only the middle three pixels are selected.
        val mask = intArrayOf(0, 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0)
        FloodFill.fill(pixels, 5, 1, 2, 0, 0, fill, tolerance = 0f, maskPixels = mask, growPixels = 3)
        assertEquals("grew past the selection", 0, pixels[0])
        assertEquals("grew past the selection", 0, pixels[4])
        assertEquals(fill, pixels[2])
    }
}