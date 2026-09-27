package com.yighy.pantograph.drawing

import com.yighy.pantograph.drawing.SatelliteLayout.Side
import com.yighy.pantograph.drawing.SatelliteLayout.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An arc is drawn in a rectangular box, and the box is where its touches land. So the failure
 * that matters is two boxes overlapping: a touch in the shared corner would reach whichever
 * satellite happened to be drawn last, and nothing on screen would say which.
 */
class SatelliteArcsTest {

    private val cx = 200f
    private val cy = 400f

    /** Button sizes and depths across the range the settings allow, in pixels. */
    private val cases = listOf(
        Triple(40f, 26f, 8f),
        Triple(56f, 26f, 10f),
        Triple(80f, 34f, 12f),
        Triple(120f, 40f, 15f)
    )

    private fun arc(slot: Slot, fab: Float = 56f, mini: Float = 26f, gap: Float = 10f) =
        SatelliteArcs.arc(slot, cx, cy, fab / 2f, gap, mini)

    private fun overlap(a: SatelliteArcs.Arc, b: SatelliteArcs.Arc): Boolean =
        a.x < b.x + b.width && b.x < a.x + a.width &&
            a.y < b.y + b.height && b.y < a.y + a.height

    // ---- against the button ----

    @Test
    fun `neighbouring arcs never share a touch area, at any button size`() {
        cases.forEach { (fab, mini, gap) ->
            val first = Side.values().map { arc(Slot(it, 0), fab, mini, gap) }
            first.forEachIndexed { i, a ->
                first.drop(i + 1).forEach { b -> assertFalse("fab $fab: $a overlaps $b", overlap(a, b)) }
            }
        }
    }

    @Test
    fun `no arc's box reaches into the button`() {
        cases.forEach { (fab, mini, gap) ->
            val half = fab / 2f
            assertTrue(arc(Slot(Side.Right, 0), fab, mini, gap).x > cx + half)
            assertTrue(arc(Slot(Side.Bottom, 0), fab, mini, gap).y > cy + half)
            arc(Slot(Side.Left, 0), fab, mini, gap).let { assertTrue(it.x + it.width < cx - half) }
            arc(Slot(Side.Top, 0), fab, mini, gap).let { assertTrue(it.y + it.height < cy - half) }
        }
    }

    // ---- rings further out ----

    @Test
    fun `an arc reaches exactly as far out as the bar in its slot did`() {
        val fab = 56f
        val mini = 26f
        val gap = 10f
        (0..2).forEach { ring ->
            val reach = fab / 2f + gap + mini + ring * (mini + gap)
            assertEquals(cy - reach, arc(Slot(Side.Top, ring)).y, 0.01f)
            arc(Slot(Side.Bottom, ring)).let { assertEquals(cy + reach, it.y + it.height, 0.01f) }
        }
    }

    @Test
    fun `stacked rings on one side never share a touch area`() {
        cases.forEach { (fab, mini, gap) ->
            Side.values().forEach { side ->
                val rings = (0..2).map { arc(Slot(side, it), fab, mini, gap) }
                rings.forEachIndexed { i, a ->
                    rings.drop(i + 1).forEach { b -> assertFalse("fab $fab $side: rings overlap", overlap(a, b)) }
                }
            }
        }
    }

    @Test
    fun `a ring further out keeps the first ring's width`() {
        // Bent bars rather than wider and wider arcs: the outer ones' ends would otherwise dip
        // back into the ring inside them, and a stacked satellite would outgrow the edge checks.
        val widths = (0..2).map { arc(Slot(Side.Top, it)).width }
        widths.forEach { assertEquals(widths[0], it, 0.01f) }
    }

    @Test
    fun `an outer ring on one side keeps clear of a first ring on the next`() {
        cases.forEach { (fab, mini, gap) ->
            (1..2).forEach { ring ->
                val stacked = arc(Slot(Side.Top, ring), fab, mini, gap)
                listOf(Side.Right, Side.Left).forEach { flank ->
                    assertFalse(overlap(stacked, arc(Slot(flank, 0), fab, mini, gap)))
                }
            }
        }
    }

    // ---- inside the box ----

    @Test
    fun `the arc's centre in its box is the button's centre`() {
        Side.values().forEach { side ->
            (0..2).forEach { ring ->
                val a = arc(Slot(side, ring))
                assertEquals(cx, a.x + a.centerX, 0.01f)
                assertEquals(cy, a.y + a.centerY, 0.01f)
            }
        }
    }

    @Test
    fun `each arc is centred on its own axis`() {
        Side.values().forEach { side ->
            val a = arc(Slot(side, 0))
            assertEquals(side.axisDeg, a.startDeg + a.sweepDeg / 2f, 0.01f)
        }
    }

    @Test
    fun `the glyph sits on the middle of the arc, not the middle of the box`() {
        val a = arc(Slot(Side.Right, 0))
        val mid = (a.inner + a.outer) / 2f
        assertEquals(cx + mid, a.x + a.contentCenterX, 0.01f)
        assertEquals(cy, a.y + a.contentCenterY, 0.01f)
        // And that is not where the box's own middle is - which is the whole reason for it.
        assertTrue(a.x + a.width / 2f < cx + mid - 1f)
    }

    // ---- as the layout places them ----

    private fun arcsAt(fabX: Float, fabY: Float, sw: Float, sh: Float): List<SatelliteArcs.Arc> {
        val fab = 56f
        val mini = 26f
        val gap = 10f
        val p = SatelliteLayout.place(fabX, fabY, fab, mini, gap, sw, sh)
        return listOf(p.levelsSlot, p.modeSlot, p.colourSlot, p.toolSlot).map {
            SatelliteArcs.arc(it, fabX + fab / 2f, fabY + fab / 2f, fab / 2f, gap, mini)
        }
    }

    @Test
    fun `no two satellites ever overlap, wherever the button is parked`() {
        val sw = 360f
        val sh = 800f
        var x = 0f
        while (x <= sw - 56f) {
            var y = 0f
            while (y <= sh - 56f) {
                val arcs = arcsAt(x, y, sw, sh)
                arcs.forEachIndexed { i, a ->
                    arcs.drop(i + 1).forEach { b -> assertFalse("at ($x,$y)", overlap(a, b)) }
                }
                y += 17f
            }
            x += 13f
        }
    }

    @Test
    fun `no two satellites overlap on a short landscape screen either`() {
        val sw = 800f
        val sh = 360f
        var x = 0f
        while (x <= sw - 56f) {
            var y = 0f
            while (y <= sh - 56f) {
                val arcs = arcsAt(x, y, sw, sh)
                arcs.forEachIndexed { i, a ->
                    arcs.drop(i + 1).forEach { b -> assertFalse("at ($x,$y)", overlap(a, b)) }
                }
                y += 11f
            }
            x += 19f
        }
    }

    @Test
    fun `mid-screen each satellite is on its own side against the button`() {
        val p = SatelliteLayout.place(150f, 400f, 56f, 26f, 10f, 360f, 800f)
        assertEquals(Slot(Side.Right, 0), p.levelsSlot)
        assertEquals(Slot(Side.Bottom, 0), p.modeSlot)
        assertEquals(Slot(Side.Top, 0), p.colourSlot)
        assertEquals(Slot(Side.Left, 0), p.toolSlot)
    }

    @Test
    fun `against the bottom edge mode and colour stack above the button`() {
        val p = SatelliteLayout.place(150f, 800f - 56f, 56f, 26f, 10f, 360f, 800f)
        assertEquals(Slot(Side.Top, 0), p.modeSlot)
        assertEquals(Slot(Side.Top, 1), p.colourSlot)
    }
}
