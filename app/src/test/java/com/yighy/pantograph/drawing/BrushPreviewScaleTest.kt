package com.yighy.pantograph.drawing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A thumbnail that swallows its own strip conveys nothing, and neither does one where every
 * brush under 30px is the same hairline. Both ends have to stay readable.
 */
class BrushPreviewScaleTest {

    // 48dp strip at 3x.
    private val strip = 144

    @Test
    fun `the largest brush stays inside the strip`() {
        val widest = BrushPreviewScale.displaySize(BrushPreviewScale.MAX_BRUSH_SIZE, strip)
        assertEquals(strip * BrushPreviewScale.MAX_HEIGHT_SHARE, widest, 0.01f)
        assertTrue("it must leave room around itself", widest < strip / 2f)
    }

    @Test
    fun `nothing exceeds the cap, whatever it is handed`() {
        for (size in listOf(0f, 1f, 50f, 300f, 5_000f)) {
            val shown = BrushPreviewScale.displaySize(size, strip)
            assertTrue("size $size produced $shown", shown <= strip * BrushPreviewScale.MAX_HEIGHT_SHARE + 0.01f)
            assertTrue("size $size went negative", shown >= 0f)
        }
    }

    @Test
    fun `a bigger brush always draws thicker`() {
        var previous = -1f
        for (size in 1..300) {
            val shown = BrushPreviewScale.displaySize(size.toFloat(), strip)
            assertTrue("size $size did not grow", shown > previous)
            previous = shown
        }
    }

    @Test
    fun `the thin end keeps enough spread to tell brushes apart`() {
        // The range most brushes live in: a straight proportional map would squeeze these into
        // a couple of pixels of each other.
        val small = BrushPreviewScale.displaySize(4f, strip)
        val medium = BrushPreviewScale.displaySize(20f, strip)
        assertTrue("4px and 20px should be visibly different", medium - small > 8f)
        assertTrue("even the finest brush should still draw", BrushPreviewScale.displaySize(1f, strip) >= 1f)
    }

    @Test
    fun `a taller strip shows proportionally thicker strokes`() {
        val onSmall = BrushPreviewScale.displaySize(100f, 100)
        val onLarge = BrushPreviewScale.displaySize(100f, 200)
        assertEquals(onSmall * 2f, onLarge, 0.01f)
    }
}
