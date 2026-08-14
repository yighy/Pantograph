package com.yighy.paintcursor.drawing

/**
 * Per-stamp randomisation factors.
 *
 * The invariant that matters is that a jitter varies a value without moving its average. A
 * one-sided jitter is really two settings in a trench coat - the variation you asked for, plus
 * a silent change to the thing being varied.
 */
object BrushJitter {

    /**
     * Multiplier applied to a stamp's size, symmetric around 1.
     *
     * @param random01 a uniform sample in [0, 1)
     * @param amount 0 = no variation, 1 = anywhere from nothing to double
     *
     * Floored just above zero: a factor of exactly zero produces an empty destination rect,
     * which draws nothing and is indistinguishable from a dropped stamp.
     */
    fun sizeFactor(random01: Float, amount: Float): Float {
        if (amount <= 0f) return 1f
        return (1f + (random01 * 2f - 1f) * amount).coerceAtLeast(0.05f)
    }
}
