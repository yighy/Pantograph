package com.yighy.paintcursor.drawing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The satellite gate's feel is entirely arithmetic, and every property below is one a user
 * would notice breaking: a jump on first touch, a value that sticks after being pushed to a
 * limit, a column that flickers at its boundary.
 */
class GateMathTest {

    private val deadZone = 12f
    private val travel = 300f

    // ---- dead zone ----

    @Test
    fun `no movement inside the dead zone`() {
        assertEquals(0f, GateMath.effectiveDelta(0f, deadZone))
        assertEquals(0f, GateMath.effectiveDelta(11.9f, deadZone))
        assertEquals(0f, GateMath.effectiveDelta(-11.9f, deadZone))
    }

    @Test
    fun `value ramps from zero at the dead zone edge, it does not jump by its width`() {
        // Just past the edge the response must still be ~0, otherwise the first pixel of
        // movement outside the zone would snap the value by a whole dead zone's worth.
        assertEquals(0.1f, GateMath.effectiveDelta(12.1f, deadZone), 0.001f)
        assertEquals(-0.1f, GateMath.effectiveDelta(-12.1f, deadZone), 0.001f)
    }

    // ---- response curve ----

    @Test
    fun `full travel reaches the extremes exactly`() {
        assertEquals(1f, GateMath.shape(travel, travel), 0.0001f)
        assertEquals(-1f, GateMath.shape(-travel, travel), 0.0001f)
    }

    @Test
    fun `travel beyond full range stays clamped`() {
        assertEquals(1f, GateMath.shape(travel * 5, travel), 0.0001f)
        assertEquals(-1f, GateMath.shape(-travel * 5, travel), 0.0001f)
    }

    @Test
    fun `response is slower than linear near the anchor`() {
        // The whole point of the exponent: fine control where the finger starts.
        val halfway = GateMath.shape(travel / 2f, travel)
        assertTrue("expected < 0.5 but was $halfway", halfway < 0.5f)
    }

    @Test
    fun `response is symmetric about the anchor`() {
        assertEquals(GateMath.shape(100f, travel), -GateMath.shape(-100f, travel), 0.0001f)
    }

    // ---- full range reachability ----

    @Test
    fun `one full sweep reaches a bound from any starting value`() {
        // Guarantees no value is unreachable without lifting the finger and starting over.
        for (start in listOf(1f, 42f, 150f, 299f, 300f)) {
            val up = GateMath.step(start, 1000f, 1000f - travel, 1f, 300f, deadZone, travel)
            assertEquals("sweeping up from $start", 300f, up.value, 0.001f)
            val down = GateMath.step(start, 1000f, 1000f + travel, 1f, 300f, deadZone, travel)
            assertEquals("sweeping down from $start", 1f, down.value, 0.001f)
        }
    }

    // ---- re-anchoring at a limit (the "stuck at 1px" bug) ----

    @Test
    fun `hitting a limit re-anchors so the reversal responds immediately`() {
        // Shove well past the floor: value pins at min and the anchor follows the finger.
        val pinned = GateMath.step(150f, 1000f, 1000f + travel * 3, 1f, 300f, deadZone, travel)
        assertEquals(1f, pinned.value, 0.001f)
        assertTrue(pinned.pinned)
        assertEquals(1f, pinned.anchorValue, 0.001f)
        assertEquals(1000f + travel * 3, pinned.anchorPos, 0.001f)

        // Now pull back a modest amount from where the finger actually is. Without the
        // re-anchor this would still read 1px, because the mapping would still be measuring
        // from the original anchor 900px away.
        val recovering = GateMath.step(
            pinned.anchorValue, pinned.anchorPos, pinned.anchorPos - 150f,
            1f, 300f, deadZone, travel
        )
        assertTrue("value should have left the floor", recovering.value > 1f)
    }

    @Test
    fun `a normal step leaves the anchor untouched`() {
        val step = GateMath.step(150f, 1000f, 950f, 1f, 300f, deadZone, travel)
        assertFalse(step.pinned)
        assertEquals(150f, step.anchorValue, 0.001f)
        assertEquals(1000f, step.anchorPos, 0.001f)
    }

    @Test
    fun `pulling up raises the value and pulling down lowers it`() {
        val up = GateMath.step(150f, 1000f, 900f, 1f, 300f, deadZone, travel)
        val down = GateMath.step(150f, 1000f, 1100f, 1f, 300f, deadZone, travel)
        assertTrue(up.value > 150f)
        assertTrue(down.value < 150f)
    }

    @Test
    fun `the value never escapes its range`() {
        for (offset in listOf(-5000f, -600f, -50f, 0f, 50f, 600f, 5000f)) {
            val v = GateMath.step(150f, 1000f, 1000f + offset, 0f, 1f, deadZone, travel).value
            assertTrue("offset $offset produced $v", v in 0f..1f)
        }
    }

    // ---- quadrant gates ----

    private val trail = 44f

    @Test
    fun `nothing is selected until the finger leaves the dead zone`() {
        assertEquals(-1, GateMath.quadrant(0f, 0f, 18f))
        assertEquals(-1, GateMath.quadrant(10f, 10f, 18f))
        assertTrue(GateMath.quadrant(30f, 30f, 18f) >= 0)
    }

    @Test
    fun `quadrants run left to right, top row first`() {
        assertEquals(0, GateMath.quadrant(-50f, -50f, 18f))
        assertEquals(1, GateMath.quadrant(50f, -50f, 18f))
        assertEquals(2, GateMath.quadrant(-50f, 50f, 18f))
        assertEquals(3, GateMath.quadrant(50f, 50f, 18f))
    }

    @Test
    fun `the anchor stays put while the finger is close to it`() {
        val a = GateMath.trailAnchor(100f, 100f, 120f, 110f, trail)
        assertEquals(100f, a.x, 0.01f)
        assertEquals(100f, a.y, 0.01f)
    }

    @Test
    fun `the anchor is dragged along, never further than the radius behind`() {
        val a = GateMath.trailAnchor(0f, 0f, 300f, 0f, trail)
        assertEquals(300f - trail, a.x, 0.01f)
        assertEquals(0f, a.y, 0.01f)
    }

    @Test
    fun `changing your mind costs the same however far you first went`() {
        // The bug: with a fixed anchor, a long drag into one corner had to be retraced in full
        // before the opposite corner could be selected. With a trailing anchor the return trip
        // is bounded by the radius, no matter how far the outward journey was.
        for (outward in listOf(100f, 400f, 1200f)) {
            var anchor = GateMath.Anchor(0f, 0f)
            var x = 0f
            var y = 0f
            // Head for the bottom-right corner.
            while (x < outward) {
                x += 8f; y += 8f
                anchor = GateMath.trailAnchor(anchor.x, anchor.y, x, y, trail)
            }
            assertEquals("outward $outward should have selected bottom-right", 3,
                GateMath.quadrant(x - anchor.x, y - anchor.y, 18f))

            // Now reverse, and count how far it takes to land on the opposite cell.
            var travelled = 0f
            while (GateMath.quadrant(x - anchor.x, y - anchor.y, 18f) != 0 && travelled < 10_000f) {
                x -= 8f; y -= 8f
                travelled += 8f
                anchor = GateMath.trailAnchor(anchor.x, anchor.y, x, y, trail)
            }
            assertTrue(
                "after going out $outward the reversal took $travelled",
                travelled <= 4 * trail
            )
        }
    }

    // ---- column hysteresis ----

    private val colStep = 56f
    private val hysteresis = 10f

    @Test
    fun `column holds until the threshold plus hysteresis is cleared`() {
        // Half a step is 28px; the switch must not happen until 28 + 10.
        assertEquals(0, GateMath.nextColumn(0, 30f, colStep, hysteresis))
        assertEquals(0, GateMath.nextColumn(0, 37f, colStep, hysteresis))
        assertEquals(1, GateMath.nextColumn(0, 39f, colStep, hysteresis))
    }

    @Test
    fun `column switching is symmetric`() {
        assertEquals(0, GateMath.nextColumn(0, -37f, colStep, hysteresis))
        assertEquals(-1, GateMath.nextColumn(0, -39f, colStep, hysteresis))
    }

    @Test
    fun `jitter at a committed boundary cannot flip the column back`() {
        // Sitting on column 1, wobbling around the raw boundary between 0 and 1 (28px).
        val committed = 1
        for (x in listOf(56f, 50f, 44f, 40f, 45f, 52f)) {
            assertEquals(
                "jitter at $x should have held column 1",
                committed,
                GateMath.nextColumn(committed, x, colStep, hysteresis)
            )
        }
    }

    @Test
    fun `the column anchor holds still while the drag is within the row`() {
        // minRel -1, maxRel 2: half a column of slack either side of that is fair game.
        assertEquals(0f, GateMath.clampColumnAnchor(0f, 100f, colStep, -1, 2), 0.01f)
        assertEquals(0f, GateMath.clampColumnAnchor(0f, -50f, colStep, -1, 2), 0.01f)
    }

    @Test
    fun `overshooting the row pulls the anchor along instead of banking the travel`() {
        // Sweep far past the last column: the anchor follows so the drag reads as just beyond
        // the end, not as the several columns' worth of travel actually covered.
        val overshoot = 10 * colStep
        val anchor = GateMath.clampColumnAnchor(0f, overshoot, colStep, 0, 3)
        assertEquals(3.5f * colStep, overshoot - anchor, 0.01f)
    }

    @Test
    fun `reversing after a wild overshoot moves a column almost immediately`() {
        // The bug: every pixel spent beyond the last column had to be retraced before the
        // selection would come back. Bounded now, however far the overshoot went.
        for (overshoot in listOf(3f, 10f, 40f).map { it * colStep }) {
            var anchor = 0f
            var x = 0f
            while (x < overshoot) {
                x += 8f
                anchor = GateMath.clampColumnAnchor(anchor, x, colStep, 0, 3)
            }
            assertEquals(3, GateMath.nextColumn(3, x - anchor, colStep, hysteresis))

            var travelled = 0f
            while (GateMath.nextColumn(3, x - anchor, colStep, hysteresis) == 3 && travelled < 10_000f) {
                x -= 8f
                travelled += 8f
                anchor = GateMath.clampColumnAnchor(anchor, x, colStep, 0, 3)
            }
            assertTrue(
                "overshoot ${overshoot}px took ${travelled}px to give a column back",
                travelled <= 2 * colStep
            )
        }
    }

    @Test
    fun `columns advance one step at a time even on a large jump`() {
        // Guards the caller's re-anchor-per-column logic: skipping two columns in one event
        // would leave the intermediate parameter's anchor unset.
        assertEquals(1, GateMath.nextColumn(0, colStep * 10, colStep, hysteresis))
        assertEquals(-1, GateMath.nextColumn(0, -colStep * 10, colStep, hysteresis))
    }
}
