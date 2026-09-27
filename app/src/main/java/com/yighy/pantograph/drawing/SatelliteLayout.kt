package com.yighy.pantograph.drawing

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the four satellites sit around the floating button. Pure geometry, no Compose, so
 * the edge cases - a button jammed into a corner, a landscape screen too short for the whole
 * column - can be checked without a device.
 *
 * All values are pixels in the same space as the button's own position.
 */
object SatelliteLayout {

    /** A side of the button, by the angle its axis points at - 0 right, clockwise. */
    enum class Side(val axisDeg: Float) { Right(0f), Bottom(90f), Left(180f), Top(270f) }

    /**
     * Where a satellite sits as the arcs see it: a side of the button, and how many rings out.
     * Ring 0 is against the button; a satellite queued behind another on the same side is on
     * the ring beyond it - the arcs' form of the column slots below.
     */
    data class Slot(val side: Side, val ring: Int)

    /**
     * @param toolStacked false in the normal case, where the tool satellite sits on a flank
     * beside the button and is turned on its side. True only when neither flank was available
     * and it had to fall back into the vertical column below the mode one, where it lies flat
     * - so its width and height swap with this flag.
     */
    data class Placement(
        val levelsX: Float,
        val levelsY: Float,
        val modeX: Float,
        val modeY: Float,
        val colourX: Float,
        val colourY: Float,
        val toolX: Float,
        val toolY: Float,
        val toolStacked: Boolean,
        val levelsSlot: Slot,
        val modeSlot: Slot,
        val colourSlot: Slot,
        val toolSlot: Slot
    )

    fun place(
        fabX: Float,
        fabY: Float,
        fabSizePx: Float,
        miniPx: Float,
        gapPx: Float,
        screenWidth: Float,
        screenHeight: Float
    ): Placement {
        // Levels satellite: to the right unless that runs off the edge.
        val levelsFitsRight = fabX + fabSizePx + gapPx + miniPx <= screenWidth
        val levelsX = if (levelsFitsRight) fabX + fabSizePx + gapPx else fabX - gapPx - miniPx

        // The column above and below the button is shared by the mode, colour and (when it
        // has nowhere else to go) tool pills, so they are placed by slot rather than each
        // working out its own offset. Slot 0 is the one nearest the button on that side.
        fun downSlot(i: Int) = fabY + fabSizePx + gapPx + i * (miniPx + gapPx)
        fun upSlot(i: Int) = fabY - gapPx - miniPx - i * (miniPx + gapPx)

        // Mode satellite: below unless that runs off the bottom.
        val columnGoesDown = fabY + fabSizePx + gapPx + miniPx <= screenHeight
        val modeY = if (columnGoesDown) downSlot(0) else upSlot(0)

        // Colour satellite: above, mirroring the mode one below.
        //
        // The two want opposite ends of the same column, so whichever is displaced by an edge
        // lands on the other's side and has to queue behind it. At the bottom of the screen
        // the mode pill has already flipped up into slot 0, which puts colour in slot 1; at
        // the top, colour is the one that flips and follows the mode pill down.
        val colourFitsAbove = fabY - gapPx - miniPx >= 0f
        val colourY = when {
            !columnGoesDown -> upSlot(1)
            colourFitsAbove -> upSlot(0)
            else -> downSlot(1)
        }

        // Tool satellite: the left flank, mirroring the levels one on the right.
        //
        // Note it has no opposite flank to flip to. The right flank is either wide enough, in
        // which case the levels pill is sitting in it, or too narrow, in which case nothing
        // fits there - those two conditions are exact negations of each other, so a right
        // flank is never both free and usable. When the left runs out of room the tool drops
        // into the vertical column, behind whatever is already queued there.
        val leftFlankX = fabX - gapPx - miniPx
        val leftFlankFree = levelsFitsRight && leftFlankX >= 0f

        val toolStacked = !leftFlankFree

        val toolX = if (leftFlankFree) leftFlankX else fabX
        val toolY = when {
            leftFlankFree -> fabY
            // Up: mode took slot 0 and colour slot 1.
            !columnGoesDown -> upSlot(2)
            // Down: mode has slot 0, and colour is only down here if it could not fit above.
            colourFitsAbove -> downSlot(1)
            else -> downSlot(2)
        }

        return Placement(
            levelsX = levelsX,
            levelsY = fabY,
            modeX = fabX,
            modeY = modeY,
            colourX = fabX,
            colourY = colourY,
            toolX = toolX,
            toolY = toolY,
            toolStacked = toolStacked,
            // The same decisions as the positions above, stated as sides and rings. Column slot
            // i is ring i on that side; a flank is always ring 0.
            levelsSlot = Slot(if (levelsFitsRight) Side.Right else Side.Left, 0),
            modeSlot = Slot(if (columnGoesDown) Side.Bottom else Side.Top, 0),
            colourSlot = when {
                !columnGoesDown -> Slot(Side.Top, 1)
                colourFitsAbove -> Slot(Side.Top, 0)
                else -> Slot(Side.Bottom, 1)
            },
            toolSlot = when {
                leftFlankFree -> Slot(Side.Left, 0)
                !columnGoesDown -> Slot(Side.Top, 2)
                colourFitsAbove -> Slot(Side.Bottom, 1)
                else -> Slot(Side.Bottom, 2)
            }
        )
    }
}

/**
 * The satellites as arcs of a ring around the round button, one on each side.
 *
 * Pure geometry like [SatelliteLayout], for the same reason: what matters here is where touches
 * land, and that is arithmetic. An arc is drawn in a rectangular box, and the box - not the
 * arc - is where a touch lands, so the arcs are kept narrow enough that no two boxes ever
 * overlap. See [halfSweepDeg].
 *
 * Angles are in degrees, 0 pointing right and increasing clockwise, as Android draws them.
 */
object SatelliteArcs {

    /**
     * An arc's box on screen, and the arc within it in the box's own coordinates: centred on
     * ([centerX], [centerY]), between radii [inner] and [outer], from [startDeg] over [sweepDeg].
     * ([contentCenterX], [contentCenterY]) is the middle of the arc itself, where its glyph
     * belongs - which is not the middle of its box, the arc being bowed within it.
     */
    data class Arc(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val centerX: Float,
        val centerY: Float,
        val inner: Float,
        val outer: Float,
        val startDeg: Float,
        val sweepDeg: Float,
        val contentCenterX: Float,
        val contentCenterY: Float
    )

    /** Kept off the exact limit, so neighbouring boxes do not share an edge a rounding could cross. */
    const val MARGIN_DEG = 1f

    /**
     * How far each arc against the button may open either side of its axis.
     *
     * Two neighbouring arcs, a quarter turn apart, have boxes that meet in the corner between
     * them once an arc opens past atan(inner / outer). A touch in that corner would reach
     * whichever satellite happened to be drawn last, so the arcs stop short of it. Computed
     * from the radii rather than fixed, so the guarantee holds at every button size.
     */
    fun halfSweepDeg(inner: Float, outer: Float): Float =
        Math.toDegrees(atan2(inner, outer).toDouble()).toFloat() - MARGIN_DEG

    /**
     * The arc for a satellite in [slot], around a button centred at ([centerX], [centerY]).
     *
     * [gapPx] clear of its [buttonRadius], [thicknessPx] deep, and each further ring the same
     * gap and depth beyond the last - so an arc reaches exactly as far out along its axis as
     * the bar in that slot did.
     *
     * Rings beyond the first keep the first's width rather than its angle. At the same angle an
     * outer arc is wider, and its ends dip back towards the ring inside it until the two boxes
     * meet; at the same width it narrows as it goes out, like a bar that was bent, and its ends
     * stay clear. It also keeps a stacked satellite no wider than the one against the button,
     * which is what the layout's edge checks were written for.
     */
    fun arc(
        slot: SatelliteLayout.Slot,
        centerX: Float,
        centerY: Float,
        buttonRadius: Float,
        gapPx: Float,
        thicknessPx: Float
    ): Arc {
        val firstInner = buttonRadius + gapPx
        val firstOuter = firstInner + thicknessPx
        val firstHalf = Math.toRadians(halfSweepDeg(firstInner, firstOuter).toDouble())
        val across = (firstOuter * sin(firstHalf)).toFloat()

        val inner = firstInner + slot.ring * (thicknessPx + gapPx)
        val outer = inner + thicknessPx
        val h = asin((across / outer).toDouble().coerceAtMost(1.0))
        val halfDeg = Math.toDegrees(h).toFloat()

        // Along its axis an arc comes nearest the button at its ends, on the inner radius, and
        // reaches furthest in the middle, on the outer one. Across, it spans the chord.
        val near = (inner * cos(h)).toFloat()
        val mid = (inner + outer) / 2f
        val box = when (slot.side) {
            SatelliteLayout.Side.Right -> floatArrayOf(near, outer, -across, across)
            SatelliteLayout.Side.Bottom -> floatArrayOf(-across, across, near, outer)
            SatelliteLayout.Side.Left -> floatArrayOf(-outer, -near, -across, across)
            SatelliteLayout.Side.Top -> floatArrayOf(-across, across, -outer, -near)
        }
        val axis = when (slot.side) {
            SatelliteLayout.Side.Right -> 1f to 0f
            SatelliteLayout.Side.Bottom -> 0f to 1f
            SatelliteLayout.Side.Left -> -1f to 0f
            SatelliteLayout.Side.Top -> 0f to -1f
        }
        val (minX, maxX, minY, maxY) = box.toList()
        return Arc(
            x = centerX + minX,
            y = centerY + minY,
            width = maxX - minX,
            height = maxY - minY,
            centerX = -minX,
            centerY = -minY,
            inner = inner,
            outer = outer,
            startDeg = slot.side.axisDeg - halfDeg,
            sweepDeg = 2 * halfDeg,
            contentCenterX = -minX + axis.first * mid,
            contentCenterY = -minY + axis.second * mid
        )
    }
}
