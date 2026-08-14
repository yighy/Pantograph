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
