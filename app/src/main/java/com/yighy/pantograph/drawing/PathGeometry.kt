package com.yighy.pantograph.drawing

import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot

/**
 * The curve behind the path tool. Pure maths, no Compose beyond [Offset] and no Android, so the
 * shape of a path can be checked without a device.
 *
 * A Catmull-Rom spline rather than Béziers, because of how this app is driven. A cubic Bézier
 * carries two control handles per point, which is four things to aim a relative cursor at for
 * every pair of neighbours. Catmull-Rom passes *through* its points: the only thing to grab is
 * the point itself, it is smooth with no handles at all, and "make this one sharp" is a flag
 * rather than a second kind of object to manipulate.
 */
object PathGeometry {

    /** How finely each span between two points is flattened. */
    const val SEGMENTS_PER_SPAN = 16

    /**
     * How near the cursor has to be to pick a point up, in canvas pixels at 1:1.
     *
     * Callers divide it by the canvas scale, so the reach stays the same size under the thumb
     * whatever the zoom - a fixed canvas-space radius would be unhittable zoomed in and would
     * swallow the whole path zoomed out.
     */
    const val GRAB_RADIUS = 26f

    /**
     * Walks the curve through [points] and returns it as a polyline.
     *
     * Two points with no corner between them come back as a dead straight line - the spline
     * degenerates to one when both its outer controls collapse onto the ends - which is what
     * makes "draw a straight line, then bend it" one tool rather than two.
     *
     * [closed] runs one more span, from the last point back to the first, and lets the two ends
     * see each other as neighbours. That last part is what makes a closed curve join smoothly
     * instead of arriving at a kink: an end with nobody beyond it borrows its own position for
     * the missing control, and a loop has somebody beyond it.
     */
    fun flatten(
        points: List<PathPoint>,
        closed: Boolean = false,
        segmentsPerSpan: Int = SEGMENTS_PER_SPAN
    ): List<Offset> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return listOf(points[0].position)

        val n = points.size
        val loop = closed && n >= 3
        val spans = if (loop) n else n - 1
        val out = ArrayList<Offset>(spans * segmentsPerSpan + 1)
        out.add(points[0].position)
        for (i in 0 until spans) {
            val p1 = points[i]
            val p2 = points[(i + 1) % n]
            // A corner breaks the tangent by standing in for its own outer neighbour: the curve
            // then arrives and leaves aimed straight at the next point, which is the kink. An
            // open path's ends do the same for the opposite reason - there is nobody there.
            val p0 = when {
                p1.isCorner -> p1.position
                loop -> points[(i - 1 + n) % n].position
                else -> points.getOrNull(i - 1)?.position ?: p1.position
            }
            val p3 = when {
                p2.isCorner -> p2.position
                loop -> points[(i + 2) % n].position
                else -> points.getOrNull(i + 2)?.position ?: p2.position
            }
            for (s in 1..segmentsPerSpan) {
                out.add(interpolate(p0, p1.position, p2.position, p3, s.toFloat() / segmentsPerSpan))
            }
        }
        return out
    }

    /** Uniform Catmull-Rom, tension 0.5. [t] runs 0..1 from [p1] to [p2]. */
    private fun interpolate(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
        val t2 = t * t
        val t3 = t2 * t
        fun axis(a0: Float, a1: Float, a2: Float, a3: Float): Float = 0.5f * (
            2f * a1 +
                (-a0 + a2) * t +
                (2f * a0 - 5f * a1 + 4f * a2 - a3) * t2 +
                (-a0 + 3f * a1 - 3f * a2 + a3) * t3
            )
        return Offset(
            axis(p0.x, p1.x, p2.x, p3.x),
            axis(p0.y, p1.y, p2.y, p3.y)
        )
    }

    /**
     * Index of the point within [radius] of [at], or -1. Ties go to the nearer one, so points
     * dragged on top of each other still resolve to a single choice rather than to whichever
     * happens to be first.
     */
    fun nearestIndex(points: List<PathPoint>, at: Offset, radius: Float): Int {
        var best = -1
        var bestDistance = radius
        points.forEachIndexed { i, point ->
            val distance = hypot(point.position.x - at.x, point.position.y - at.y)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }
}
