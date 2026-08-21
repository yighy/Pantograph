package com.yighy.pantograph.drawing

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign

/**
 * The arithmetic behind the satellite gates, kept free of Compose and Android so it can be
 * tested directly. [HoverDrawButton] owns the gesture plumbing; everything that decides what
 * a finger position *means* lives here.
 */
object GateMath {

    /** Slow near the anchor, fast at full stretch. >1 = fine control where the finger starts. */
    const val RESPONSE_EXPONENT = 1.8f

    /**
     * Drag distance with the dead zone subtracted, so the value ramps from zero at the edge of
     * the zone instead of jumping by the width of it.
     */
    fun effectiveDelta(rawDelta: Float, deadZonePx: Float): Float = when {
        rawDelta > deadZonePx -> rawDelta - deadZonePx
        rawDelta < -deadZonePx -> rawDelta + deadZonePx
        else -> 0f
    }

    /** Maps travel to a -1..1 factor through the response curve. */
    fun shape(effectiveDelta: Float, travelPx: Float): Float {
        val t = (effectiveDelta / travelPx).coerceIn(-1f, 1f)
        return sign(t) * abs(t).pow(RESPONSE_EXPONENT)
    }

    /**
     * Result of one pointer event. [anchorValue] and [anchorPos] are fed back into the next
     * call: they only move when the value hits a limit, which is what stops travel spent past
     * that limit from having to be retraced before the value responds again.
     */
    data class GateStep(
        val value: Float,
        val anchorValue: Float,
        val anchorPos: Float,
        val pinned: Boolean
    )

    /**
     * @param anchorPos pointer position the current anchor was taken at
     * @param currentPos live pointer position, on the same axis, screen coordinates
     * (both grow downwards, so pulling *up* raises the value)
     * @param travelPx total finger travel for a full-range sweep, dead zone included. The
     * curve is normalised over what's left after the dead zone, so moving exactly this far
     * really does span the whole range - measuring against the raw figure instead left the
     * last few percent of the range out of reach in a single sweep.
     */
    fun step(
        anchorValue: Float,
        anchorPos: Float,
        currentPos: Float,
        min: Float,
        max: Float,
        deadZonePx: Float,
        travelPx: Float
    ): GateStep {
        val usableTravel = (travelPx - deadZonePx).coerceAtLeast(1f)
        val delta = effectiveDelta(anchorPos - currentPos, deadZonePx)
        val raw = anchorValue + shape(delta, usableTravel) * (max - min)
        val clamped = raw.coerceIn(min, max)
        return if (raw != clamped) {
            GateStep(clamped, anchorValue = clamped, anchorPos = currentPos, pinned = true)
        } else {
            GateStep(clamped, anchorValue = anchorValue, anchorPos = anchorPos, pinned = false)
        }
    }

    /** Touch-down point a quadrant gate measures its direction from. */
    data class Anchor(val x: Float, val y: Float)

    /**
     * How far the anchor is allowed to lag behind the finger. Also the distance a reversal has
     * to cover before the quadrant flips, so it wants to be comfortably clear of the dead zone
     * without being a journey.
     */
    const val ANCHOR_TRAIL_RADIUS_DP = 44f

    /**
     * Drags the anchor along so it never falls further than [maxRadiusPx] behind the finger.
     *
     * Without this the anchor is wherever the finger first landed, so changing your mind after
     * committing to a corner means retracing the entire journey back through that point before
     * the quadrant will change - the further you went, the longer the way back. Trailing bounds
     * that return trip to the radius, whatever the outward distance.
     */
    fun trailAnchor(
        anchorX: Float,
        anchorY: Float,
        fingerX: Float,
        fingerY: Float,
        maxRadiusPx: Float
    ): Anchor {
        val dx = fingerX - anchorX
        val dy = fingerY - anchorY
        val distance = hypot(dx, dy)
        if (distance <= maxRadiusPx || distance == 0f) return Anchor(anchorX, anchorY)
        val pull = (distance - maxRadiusPx) / distance
        return Anchor(anchorX + dx * pull, anchorY + dy * pull)
    }

    /**
     * Quadrant of a drag, or -1 while it is still inside the dead zone. Cells run left to
     * right, top row first.
     */
    fun quadrant(dragX: Float, dragY: Float, deadZonePx: Float): Int =
        if (hypot(dragX, dragY) < deadZonePx) -1
        else (if (dragY < 0f) 0 else 2) + (if (dragX < 0f) 0 else 1)

    /**
     * Column selection with hysteresis: the threshold sits half a step plus [hysteresisPx]
     * beyond the committed column, so jitter at a boundary can't flip it back and forth.
     * Returns a *relative* column, still to be offset by the starting column and clamped.
     */
    /**
     * Pulls a column gate's anchor forward so the drag can't bank travel past the ends of the
     * row. Sideways is the [GateStep] problem in another guise: sweep well beyond the last
     * column and every pixel of the overshoot has to be retraced before the column will move
     * back. Clamping the anchor to half a column outside the row bounds the reversal instead.
     */
    fun clampColumnAnchor(
        anchorX: Float,
        fingerX: Float,
        colStepPx: Float,
        minRel: Int,
        maxRel: Int
    ): Float {
        val lowest = (minRel - 0.5f) * colStepPx
        val highest = (maxRel + 0.5f) * colStepPx
        val rel = fingerX - anchorX
        return when {
            rel > highest -> fingerX - highest
            rel < lowest -> fingerX - lowest
            else -> anchorX
        }
    }

    fun nextColumn(committedRel: Int, dragX: Float, colStepPx: Float, hysteresisPx: Float): Int {
        val relPos = dragX / colStepPx
        val slack = hysteresisPx / colStepPx
        return when {
            relPos > committedRel + 0.5f + slack -> committedRel + 1
            relPos < committedRel - 0.5f - slack -> committedRel - 1
            else -> committedRel
        }
    }
}
