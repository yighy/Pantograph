package com.yighy.paintcursor.drawing

import kotlin.math.abs
import kotlin.math.pow

/**
 * How the size multiplier's slider position maps to a multiplier.
 *
 * A plain linear 0..16 track spends fifteen sixteenths of its length above 1x, which squeezes
 * the range brushes actually live in - roughly a half to a double - into the first few
 * millimetres and leaves 1.00x itself all but unhittable. A cubic response spreads that region
 * across the first 40% of the track instead, so the multiplier moves slowly where the useful
 * values are and quickly out at the extremes nobody dials in precisely.
 *
 * On top of that, a detent parks the thumb exactly on 1.00x whenever it passes close by. It is
 * measured along the *track*, so what it costs in multipliers depends on how steep the curve is
 * where it sits: at 1.2% of travel either side it swallows roughly 0.91x to 1.09x, which is the
 * price of landing on 1.00x without having to aim. Widening it is not free - at 2.5% it was
 * already eating everything from 0.82x to 1.20x.
 */
object SizeMultiplierScale {

    /** Top of the multiplier: 300px at 16x is already wider than most canvases. */
    const val MAX = 16f

    /** The multiplier that changes nothing, and the one the detent is built around. */
    const val NEUTRAL = 1f

    /** Half-width of the detent, as a fraction of the track. */
    const val DETENT = 0.012f

    private const val CURVE = 3f

    /** Track position (0..1) for a given [multiplier]. */
    fun toPosition(multiplier: Float): Float =
        (multiplier / MAX).coerceIn(0f, 1f).pow(1f / CURVE)

    /** Where 1.00x sits along the track - a shade under 40%. */
    val neutralPosition: Float = toPosition(NEUTRAL)

    /**
     * Multiplier for a given track [position] (0..1), snapped to exactly [NEUTRAL] inside the
     * detent.
     *
     * Snapping to the exact constant rather than to something merely close matters: the label
     * reads "1.00x" either way, but only an exact 1 leaves the painted size bit-for-bit what it
     * was before the multiplier existed.
     */
    fun toMultiplier(position: Float): Float {
        val p = position.coerceIn(0f, 1f)
        if (abs(p - neutralPosition) <= DETENT) return NEUTRAL
        return MAX * p.pow(CURVE)
    }
}
