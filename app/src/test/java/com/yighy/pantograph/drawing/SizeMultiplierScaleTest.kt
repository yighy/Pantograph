package com.yighy.pantograph.drawing

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detent has to make 1.00x easy to land on without eating the values around it - the two
 * pull in opposite directions, and only the second one fails quietly.
 */
class SizeMultiplierScaleTest {

    @Test
    fun `the ends of the track are the ends of the range`() {
        assertEquals(0f, SizeMultiplierScale.toMultiplier(0f), 0.0001f)
        assertEquals(SizeMultiplierScale.MAX, SizeMultiplierScale.toMultiplier(1f), 0.0001f)
    }

    @Test
    fun `position and multiplier round-trip outside the detent`() {
        for (multiplier in listOf(0.25f, 0.5f, 2f, 4f, 8f, 16f)) {
            val position = SizeMultiplierScale.toPosition(multiplier)
            assertEquals(
                "multiplier $multiplier",
                multiplier,
                SizeMultiplierScale.toMultiplier(position),
                0.001f
            )
        }
    }

    @Test
    fun `the curve puts the useful range in the first half of the track`() {
        // The point of the cubic: on a linear track 1x sits at 6% and 2x at 12%, which is what
        // made the multiplier impossible to dial in.
        assertTrue(SizeMultiplierScale.toPosition(1f) > 0.35f)
        assertTrue(SizeMultiplierScale.toPosition(2f) > 0.45f)
        assertTrue(SizeMultiplierScale.toPosition(4f) < 0.7f)
    }

    @Test
    fun `anywhere in the detent lands on exactly one`() {
        // Just inside the edges rather than exactly on them: the boundary itself is a float
        // comparison, and which side it falls on there is not worth pinning down.
        val neutral = SizeMultiplierScale.neutralPosition
        val edge = SizeMultiplierScale.DETENT * 0.9f
        for (offset in listOf(-edge, -0.005f, 0f, 0.005f, edge)) {
            val result = SizeMultiplierScale.toMultiplier(neutral + offset)
            assertEquals("offset $offset", SizeMultiplierScale.NEUTRAL, result, 0f)
        }
    }

    @Test
    fun `the detent leaves the values either side of it reachable`() {
        // What the detent costs is the whole trade-off: it swallows about 0.91x..1.09x, so
        // anything outside that has to still be dialable. Widening it eats this range fast -
        // at 2.5% of the track it reached 0.82x..1.20x.
        for (multiplier in listOf(0.5f, 0.75f, 0.85f, 1.2f, 1.5f, 3f)) {
            val result = SizeMultiplierScale.toMultiplier(SizeMultiplierScale.toPosition(multiplier))
            assertTrue(
                "$multiplier was pulled to $result",
                abs(result - multiplier) < 0.05f
            )
        }
    }

    @Test
    fun `the detent is narrow enough to leave most of the track alone`() {
        val snapped = (0..1000).count {
            SizeMultiplierScale.toMultiplier(it / 1000f) == SizeMultiplierScale.NEUTRAL
        }
        assertTrue("the detent covered $snapped/1001 of the track", snapped < 60)
    }
}
