package com.yighy.pantograph.drawing

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class PathGeometryTest {

    private fun p(x: Float, y: Float, corner: Boolean = false) = PathPoint(Offset(x, y), corner)

    private fun distanceToSegment(point: Offset, a: Offset, b: Offset): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return hypot(point.x - a.x, point.y - a.y)
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(point.x - (a.x + t * dx), point.y - (a.y + t * dy))
    }

    /** Largest distance of any flattened sample from the straight line through the ends. */
    private fun bowOf(points: List<PathPoint>): Float {
        val flat = PathGeometry.flatten(points)
        val a = flat.first()
        val b = flat.last()
        val len = hypot(b.x - a.x, b.y - a.y)
        if (len == 0f) return 0f
        return flat.maxOf { abs((b.x - a.x) * (a.y - it.y) - (a.x - it.x) * (b.y - a.y)) / len }
    }

    @Test
    fun `two points flatten to a dead straight line`() {
        // The tool opens as a straight line and is bent afterwards, so this is not a nicety:
        // if the spline bowed between two points there would be no way to draw a straight one.
        assertEquals(0f, bowOf(listOf(p(0f, 0f), p(100f, 0f))), 0.001f)
        assertEquals(0f, bowOf(listOf(p(10f, 20f), p(310f, 220f))), 0.001f)
    }

    @Test
    fun `the curve passes through every point`() {
        val points = listOf(p(0f, 0f), p(50f, 80f), p(120f, 10f), p(200f, 90f))
        val flat = PathGeometry.flatten(points)
        points.forEach { point ->
            val nearest = flat.minOf { hypot(it.x - point.position.x, it.y - point.position.y) }
            assertTrue("no sample landed on ${point.position}", nearest < 0.001f)
        }
    }

    @Test
    fun `ends are the first and last points exactly`() {
        val points = listOf(p(3f, 4f), p(50f, 80f), p(120f, 10f))
        val flat = PathGeometry.flatten(points)
        assertEquals(3f, flat.first().x, 0.001f)
        assertEquals(4f, flat.first().y, 0.001f)
        assertEquals(120f, flat.last().x, 0.001f)
        assertEquals(10f, flat.last().y, 0.001f)
    }

    @Test
    fun `a middle point bows the line away from straight`() {
        val bowed = bowOf(listOf(p(0f, 0f), p(50f, 60f), p(100f, 0f)))
        assertTrue("a point off the axis should bend the curve, bow was $bowed", bowed > 10f)
    }

    @Test
    fun `marking a point as a corner turns its spans into straight legs`() {
        val apex = Offset(50f, 60f)
        val smooth = listOf(p(0f, 0f), p(50f, 60f), p(100f, 0f))
        val cornered = listOf(p(0f, 0f), p(50f, 60f, corner = true), p(100f, 0f))

        // How far the flattened curve strays from the two straight legs through the middle
        // point. A kink follows them exactly; a smooth curve bulges off them on its way round.
        fun strayFromLegs(points: List<PathPoint>): Float {
            val legs = listOf(Offset(0f, 0f) to apex, apex to Offset(100f, 0f))
            return PathGeometry.flatten(points).maxOf { sample ->
                legs.minOf { (a, b) -> distanceToSegment(sample, a, b) }
            }
        }

        assertEquals(0f, strayFromLegs(cornered), 0.001f)
        assertTrue(
            "a smooth middle point should round off its legs, strayed ${strayFromLegs(smooth)}",
            strayFromLegs(smooth) > 2f
        )
    }

    @Test
    fun `an empty or single path flattens without blowing up`() {
        assertTrue(PathGeometry.flatten(emptyList()).isEmpty())
        assertEquals(1, PathGeometry.flatten(listOf(p(5f, 5f))).size)
    }

    @Test
    fun `a closed path returns to its first point`() {
        val square = listOf(p(0f, 0f), p(100f, 0f), p(100f, 100f), p(0f, 100f))
        val flat = PathGeometry.flatten(square, closed = true)
        assertEquals(flat.first().x, flat.last().x, 0.001f)
        assertEquals(flat.first().y, flat.last().y, 0.001f)
    }

    @Test
    fun `closing adds exactly one more span`() {
        val square = listOf(p(0f, 0f), p(100f, 0f), p(100f, 100f), p(0f, 100f))
        val open = PathGeometry.flatten(square).size
        val closed = PathGeometry.flatten(square, closed = true).size
        assertEquals(PathGeometry.SEGMENTS_PER_SPAN, closed - open)
    }

    @Test
    fun `the seam of a closed path bends no more than any other vertex`() {
        // The whole point of letting the two ends see each other as neighbours. Measured
        // against an ordinary vertex of the same square rather than against a fixed angle:
        // the claim is that the seam is not special, and on a symmetric shape the two turns
        // are the same turn. A fixed threshold would only be testing how coarsely the curve
        // happens to be sampled.
        val square = listOf(p(0f, 0f), p(100f, 0f), p(100f, 100f), p(0f, 100f))
        val flat = PathGeometry.flatten(square, closed = true)
        val span = PathGeometry.SEGMENTS_PER_SPAN

        fun turnAt(index: Int): Float {
            val before = flat[index] - flat[index - 1]
            val after = flat[index + 1] - flat[index]
            return (before.x * after.x + before.y * after.y) /
                (hypot(before.x, before.y) * hypot(after.x, after.y))
        }

        // The seam is where the last sample lands back on the first point.
        val seam = (flat[flat.lastIndex] - flat[flat.lastIndex - 1]).let { incoming ->
            val outgoing = flat[1] - flat[0]
            (incoming.x * outgoing.x + incoming.y * outgoing.y) /
                (hypot(incoming.x, incoming.y) * hypot(outgoing.x, outgoing.y))
        }
        val ordinary = turnAt(span)

        assertEquals("the seam turned differently from an ordinary vertex", ordinary, seam, 0.01f)
    }

    @Test
    fun `closing is ignored below three points`() {
        // Two points cannot bound an area, and wrapping them would draw the same segment twice.
        val pair = listOf(p(0f, 0f), p(100f, 0f))
        assertEquals(
            PathGeometry.flatten(pair).size,
            PathGeometry.flatten(pair, closed = true).size
        )
    }

    @Test
    fun `nearestIndex finds a point inside the radius and nothing outside it`() {
        val points = listOf(p(0f, 0f), p(100f, 0f), p(200f, 0f))
        assertEquals(1, PathGeometry.nearestIndex(points, Offset(105f, 5f), radius = 20f))
        assertEquals(-1, PathGeometry.nearestIndex(points, Offset(150f, 0f), radius = 20f))
    }

    @Test
    fun `nearestIndex prefers the closer of two overlapping points`() {
        // Dragging one point onto another must still resolve to one of them, and to the nearer,
        // rather than to whichever comes first in the list.
        val points = listOf(p(100f, 0f), p(104f, 0f))
        assertEquals(1, PathGeometry.nearestIndex(points, Offset(106f, 0f), radius = 30f))
        assertEquals(0, PathGeometry.nearestIndex(points, Offset(98f, 0f), radius = 30f))
    }
}
