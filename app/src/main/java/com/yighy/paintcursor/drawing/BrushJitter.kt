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

/**
 * How large a bitmap a stamp is rasterised into, and how much that bitmap must then be scaled
 * up to reach the size actually asked for.
 *
 * A 300px brush at 16x wants a 4800px square stamp - 92 MB of ARGB_8888, which is an
 * out-of-memory crash rather than a big brush. Past [MAX_RASTER] the stamp is rasterised at the
 * cap and scaled up when it is drawn instead: a soft round stamp upscales invisibly, and a
 * custom tip only softens at sizes where one dab already covers most of the canvas.
 */
object StampRaster {

    /** 1024 square of ARGB_8888 is 4 MB - the most worth holding for a single cached stamp. */
    const val MAX_RASTER = 1024

    /** Side of the bitmap to rasterise a stamp of [stampSize] px into. */
    fun rasterSize(stampSize: Float): Int = stampSize.toInt().coerceIn(1, MAX_RASTER)

    /**
     * Factor the rasterised stamp is drawn at to reach [stampSize].
     *
     * Exactly 1 below the cap, so an ordinary brush is drawn from a bitmap of its own size and
     * nothing about its rendering changes.
     */
    fun drawScale(stampSize: Float): Float =
        if (stampSize > MAX_RASTER) stampSize / rasterSize(stampSize) else 1f
}
