package com.yighy.paintcursor.drawing

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug this replaced: the factor could only ever shrink a stamp, so turning the jitter up
 * thinned the brush instead of just varying it. The average has to stay put.
 */
class BrushJitterTest {

    @Test
    fun `no jitter leaves the size alone`() {
        for (r in listOf(0f, 0.5f, 0.99f)) {
            assertEquals(1f, BrushJitter.sizeFactor(r, 0f), 0.0001f)
        }
    }

    @Test
    fun `the factor is symmetric about one`() {
        // Opposite ends of the random range must land the same distance either side of 1.
        val low = BrushJitter.sizeFactor(0f, 0.5f)
        val high = BrushJitter.sizeFactor(1f, 0.5f)
        assertEquals(0.5f, low, 0.0001f)
        assertEquals(1.5f, high, 0.0001f)
        assertEquals(1f - low, high - 1f, 0.0001f)
    }

    @Test
    fun `the midpoint is exactly the set size`() {
        assertEquals(1f, BrushJitter.sizeFactor(0.5f, 1f), 0.0001f)
    }

    @Test
    fun `the average holds at one however hard the jitter is driven`() {
        // The property the old one-sided factor broke: at full jitter it averaged 0.5.
        val random = Random(7)
        for (amount in listOf(0.1f, 0.5f, 1f)) {
            val mean = (1..20_000)
                .map { BrushJitter.sizeFactor(random.nextFloat(), amount) }
                .average()
            assertEquals("amount $amount drifted", 1.0, mean, 0.02)
        }
    }

    @Test
    fun `a stamp never collapses to nothing`() {
        // Zero would give an empty destination rect - a dropped stamp rather than a small one.
        for (r in listOf(0f, 0.001f, 0.5f, 0.999f)) {
            assertTrue(BrushJitter.sizeFactor(r, 1f) > 0f)
        }
    }

}

/**
 * The memory guard behind the size multiplier. Getting this wrong doesn't misdraw anything -
 * it runs the app out of memory at the top of the multiplier's range.
 */
class StampRasterTest {

    @Test
    fun `an ordinary brush is rasterised at its own size and drawn unscaled`() {
        // The property that keeps every existing brush rendering exactly as before.
        for (size in listOf(1f, 20f, 300f, 1024f)) {
            assertEquals(size.toInt(), StampRaster.rasterSize(size))
            assertEquals("size $size", 1f, StampRaster.drawScale(size), 0.0001f)
        }
    }

    @Test
    fun `an oversized stamp is capped and scaled up to compensate`() {
        // 300px at 16x: the raster stops at the cap, the draw makes up the rest.
        val size = 4800f
        assertEquals(StampRaster.MAX_RASTER, StampRaster.rasterSize(size))
        assertEquals(size, StampRaster.rasterSize(size) * StampRaster.drawScale(size), 0.5f)
    }

    @Test
    fun `the raster never collapses or exceeds the cap`() {
        for (size in listOf(0f, 0.4f, 1f, 5000f, 100_000f)) {
            val raster = StampRaster.rasterSize(size)
            assertTrue("size $size gave $raster", raster in 1..StampRaster.MAX_RASTER)
        }
    }
}
