package com.yighy.paintcursor.drawing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three pills orbiting a button the user can drag into any corner. The failure mode is two of
 * them landing on top of each other, which is invisible in a happy-path screenshot and obvious
 * the moment someone parks the button at the bottom of a landscape screen.
 */
class SatelliteLayoutTest {

    // Roughly a portrait phone at the default button size.
    private val fab = 56f
    private val mini = 26f
    private val gap = 10f
    private val w = 360f
    private val h = 800f

    private fun place(fabX: Float, fabY: Float, sw: Float = w, sh: Float = h) =
        SatelliteLayout.place(fabX, fabY, fab, mini, gap, sw, sh)

    /** Every satellite's box, plus the button's, for overlap checks. */
    private fun boxes(p: SatelliteLayout.Placement, fabX: Float, fabY: Float): List<FloatArray> {
        val toolW = if (p.toolStacked) fab else mini
        val toolH = if (p.toolStacked) mini else fab
        return listOf(
            floatArrayOf(fabX, fabY, fab, fab),
            floatArrayOf(p.levelsX, p.levelsY, mini, fab),
            floatArrayOf(p.modeX, p.modeY, fab, mini),
            floatArrayOf(p.toolX, p.toolY, toolW, toolH)
        )
    }

    private fun overlaps(a: FloatArray, b: FloatArray): Boolean =
        a[0] < b[0] + b[2] && a[0] + a[2] > b[0] && a[1] < b[1] + b[3] && a[1] + a[3] > b[1]

    private fun assertNothingOverlaps(fabX: Float, fabY: Float, sw: Float = w, sh: Float = h) {
        val p = place(fabX, fabY, sw, sh)
        val all = boxes(p, fabX, fabY)
        val names = listOf("fab", "levels", "mode", "tool")
        for (i in all.indices) {
            for (j in i + 1 until all.size) {
                assertFalse(
                    "${names[i]} overlaps ${names[j]} at fab=($fabX,$fabY) screen=${sw}x$sh",
                    overlaps(all[i], all[j])
                )
            }
        }
    }

    // ---- normal placement ----

    @Test
    fun `with room everywhere the three pills ring the button`() {
        val p = place(100f, 300f)
        assertEquals("levels to the right", 100f + fab + gap, p.levelsX, 0.01f)
        assertEquals(300f, p.levelsY, 0.01f)
        assertEquals("mode below", 300f + fab + gap, p.modeY, 0.01f)
        assertEquals("tool to the left", 100f - gap - mini, p.toolX, 0.01f)
        assertEquals(300f, p.toolY, 0.01f)
        assertFalse("no need for the column fallback", p.toolStacked)
    }

    // ---- edge flips ----

    @Test
    fun `levels satellite flips left against the right edge`() {
        val fabX = w - fab
        val p = place(fabX, 300f)
        assertEquals(fabX - gap - mini, p.levelsX, 0.01f)
    }

    @Test
    fun `the mode pill flips above the button against the bottom edge`() {
        val fabY = h - fab
        val p = place(100f, fabY)
        assertTrue("mode should sit above the button", p.modeY < fabY)
    }

    @Test
    fun `the tool pill yields its flank rather than sit on top of the levels one`() {
        // Hard against the right edge the levels pill has nowhere to go but left, which is
        // the tool pill's home. It must give way instead of overlapping.
        val fabX = w - fab
        val p = place(fabX, 300f)
        assertEquals("levels took the left flank", fabX - gap - mini, p.levelsX, 0.01f)
        assertTrue("so the tool fell back into the column", p.toolStacked)
        assertEquals(fabX, p.toolX, 0.01f)
    }

    @Test
    fun `against the left edge the tool pill drops into the column`() {
        // There is no opposite flank to cross to: the right one is taken by the levels pill,
        // which only vacates it when it is too narrow for anything anyway.
        val p = place(0f, 300f)
        assertTrue(p.toolStacked)
        assertEquals(0f, p.toolX, 0.01f)
        assertTrue("below the mode pill", p.toolY > p.modeY)
    }

    @Test
    fun `nothing is placed off the top or left of the screen`() {
        for (x in listOf(0f, 4f, 100f)) {
            for (y in listOf(0f, 4f, 100f)) {
                val p = place(x, y)
                assertTrue("levels off-screen at ($x,$y)", p.levelsX >= 0f && p.levelsY >= 0f)
                assertTrue("mode off-screen at ($x,$y)", p.modeX >= 0f && p.modeY >= 0f)
                assertTrue("tool off-screen at ($x,$y)", p.toolX >= 0f && p.toolY >= 0f)
            }
        }
    }

    // ---- the property that actually matters ----

    @Test
    fun `no two satellites ever overlap, wherever the button is parked`() {
        for (x in 0..360 step 20) {
            for (y in 0..800 step 40) {
                assertNothingOverlaps(
                    x.coerceAtMost((w - fab).toInt()).toFloat(),
                    y.coerceAtMost((h - fab).toInt()).toFloat()
                )
            }
        }
    }

    @Test
    fun `no two satellites overlap on a short landscape screen either`() {
        val sw = 800f
        val sh = 360f
        for (x in 0..800 step 40) {
            for (y in 0..360 step 20) {
                assertNothingOverlaps(
                    x.coerceAtMost((sw - fab).toInt()).toFloat(),
                    y.coerceAtMost((sh - fab).toInt()).toFloat(),
                    sw, sh
                )
            }
        }
    }
}
