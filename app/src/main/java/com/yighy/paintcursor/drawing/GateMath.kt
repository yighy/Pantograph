package com.yighy.paintcursor.drawing

import kotlin.math.abs
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

    /**
     * Column selection with hysteresis: the threshold sits half a step plus [hysteresisPx]
     * beyond the committed column, so jitter at a boundary can't flip it back and forth.
     * Returns a *relative* column, still to be offset by the starting column and clamped.
     */
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
