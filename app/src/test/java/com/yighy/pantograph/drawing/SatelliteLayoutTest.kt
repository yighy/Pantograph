package com.yighy.pantograph.drawing

import com.yighy.pantograph.drawing.SatelliteLayout.Side
import com.yighy.pantograph.drawing.SatelliteLayout.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four satellites around a button the user can drag into any corner. Which side each one takes
 * is decided here; that the arcs in those slots never share a touch area is SatelliteArcsTest's.
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

    @Test
    fun `with room everywhere each satellite is on its own side against the button`() {
        val p = place(150f, 400f)
        assertEquals(Slot(Side.Right, 0), p.levelsSlot)
        assertEquals(Slot(Side.Bottom, 0), p.modeSlot)
        assertEquals(Slot(Side.Top, 0), p.colourSlot)
        assertEquals(Slot(Side.Left, 0), p.toolSlot)
    }

    @Test
    fun `against the right edge levels flips left and the tool yields its flank`() {
        // Levels has nowhere to go but left, which is the tool's home. The tool must give way
        // rather than overlap, and drops into the column behind mode.
        val p = place(w - fab, 400f)
        assertEquals(Slot(Side.Left, 0), p.levelsSlot)
        assertEquals(Slot(Side.Bottom, 1), p.toolSlot)
    }

    @Test
    fun `against the left edge the tool drops into the column`() {
        val p = place(0f, 400f)
        assertEquals(Slot(Side.Right, 0), p.levelsSlot)
        assertEquals(Slot(Side.Bottom, 1), p.toolSlot)
    }

    @Test
    fun `against the bottom edge mode and colour stack above the button`() {
        val p = place(150f, h - fab)
        assertEquals(Slot(Side.Top, 0), p.modeSlot)
        assertEquals(Slot(Side.Top, 1), p.colourSlot)
    }

    @Test
    fun `against the top edge colour follows mode down`() {
        val p = place(150f, 0f)
        assertEquals(Slot(Side.Bottom, 0), p.modeSlot)
        assertEquals(Slot(Side.Bottom, 1), p.colourSlot)
    }

    @Test
    fun `against the left edge and low down the tool queues above instead`() {
        // Mode still fits below, but a second ring would hang off the bottom.
        val p = place(0f, h - fab - gap - mini - 1f)
        assertEquals(Slot(Side.Bottom, 0), p.modeSlot)
        assertEquals(Slot(Side.Top, 1), p.toolSlot)
    }

    @Test
    fun `in a bottom corner the tool queues behind both`() {
        val p = place(0f, h - fab)
        assertEquals(Slot(Side.Top, 2), p.toolSlot)
    }

    @Test
    fun `in a top corner the tool queues behind colour, below`() {
        val p = place(0f, 0f)
        assertEquals(Slot(Side.Bottom, 2), p.toolSlot)
    }

    /**
     * Along its own axis every satellite stays on screen: that is what each decision checks
     * before taking a side. Across, an arc is a few pixels wider than the button, and may
     * overhang by that much when the button is hard against an edge.
     */
    private fun assertReachStaysOnScreen(fabX: Float, fabY: Float, sw: Float, sh: Float) {
        val p = place(fabX, fabY, sw, sh)
        listOf(p.levelsSlot, p.modeSlot, p.colourSlot, p.toolSlot).forEach { slot ->
            val a = SatelliteArcs.arc(slot, fabX + fab / 2f, fabY + fab / 2f, fab / 2f, gap, mini)
            val at = "$slot at ($fabX,$fabY) on ${sw}x$sh"
            when (slot.side) {
                Side.Top -> assertTrue("off the top: $at", a.y >= -0.01f)
                Side.Bottom -> assertTrue("off the bottom: $at", a.y + a.height <= sh + 0.01f)
                Side.Left -> assertTrue("off the left: $at", a.x >= -0.01f)
                Side.Right -> assertTrue("off the right: $at", a.x + a.width <= sw + 0.01f)
            }
        }
    }

    @Test
    fun `no satellite reaches off screen, wherever the button is parked`() {
        for (x in 0..360 step 20) {
            for (y in 0..800 step 40) {
                assertReachStaysOnScreen(
                    x.coerceAtMost((w - fab).toInt()).toFloat(),
                    y.coerceAtMost((h - fab).toInt()).toFloat(),
                    w, h
                )
            }
        }
    }

    @Test
    fun `nor on a short landscape screen`() {
        val sw = 800f
        val sh = 360f
        for (x in 0..800 step 40) {
            for (y in 0..360 step 20) {
                assertReachStaysOnScreen(
                    x.coerceAtMost((sw - fab).toInt()).toFloat(),
                    y.coerceAtMost((sh - fab).toInt()).toFloat(),
                    sw, sh
                )
            }
        }
    }
}
