package com.yighy.pantograph.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A satellite drawn as an arc of the ring around the round button, with round ends like the
 * pills it replaces. The geometry comes from [SatelliteArcs], already in the box's coordinates.
 *
 * A data class so that an unchanged arc is an equal shape: the clip and the shadow compare
 * shapes to decide whether to redo their work.
 */
data class SatelliteArcShape(val arc: SatelliteArcs.Arc) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val inner = arc.inner
        val outer = arc.outer
        val mid = (inner + outer) / 2f
        val cap = (outer - inner) / 2f
        // The ends are half-circles across the band. The body stops short of the full span by
        // the angle a cap takes up, or the caps would carry the arc past the edge of its box -
        // and the box is where its touches land.
        val capDeg = Math.toDegrees(atan2(cap, mid).toDouble()).toFloat()
        val from = arc.startDeg + capDeg
        val to = arc.startDeg + arc.sweepDeg - capDeg
        val center = Offset(arc.centerX, arc.centerY)

        fun at(radius: Float, deg: Float): Offset {
            val rad = Math.toRadians(deg.toDouble())
            return center + Offset((cos(rad) * radius).toFloat(), (sin(rad) * radius).toFloat())
        }

        val path = Path().apply {
            // Outer edge, clockwise.
            arcTo(Rect(center, outer), from, to - from, forceMoveTo = true)
            // End cap: from the outer edge round to the inner one, bulging on past the end.
            arcTo(Rect(at(mid, to), cap), to, 180f, forceMoveTo = false)
            // Inner edge, back the other way.
            arcTo(Rect(center, inner), to, -(to - from), forceMoveTo = false)
            // Start cap: inner edge round to the outer one, bulging back past the start.
            arcTo(Rect(at(mid, from), cap), from + 180f, 180f, forceMoveTo = false)
            close()
        }
        return Outline.Generic(path)
    }
}
