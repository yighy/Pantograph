package com.yighy.pantograph

/**
 * The shape a project's card takes in the home grid.
 *
 * The card follows the canvas, so a wide project reads as wide and a tall one as tall - the
 * grid tells you the shape of your work before you open it. Left unbounded that breaks down at
 * the extremes: a 500x4000 canvas would want a card several screens tall, and an 8:1 panorama a
 * sliver too short to hold its own title.
 *
 * So the *card* is clamped and the *image* is not. The thumbnail is fitted rather than cropped
 * inside whatever box the clamp allows, which means an extreme canvas is letterboxed and still
 * shows its true proportion - the card only stops matching it exactly. Cropping instead would
 * have been the one option that quietly lies about the shape.
 */
object ProjectPreviewRatio {

    /**
     * Tallest card allowed, as width/height. Below this the thumbnail letterboxes.
     *
     * Set below the tall end of phone screens on purpose. A canvas matching the device is the
     * ordinary case here, not an extreme, and at 0.55 a 1080x2201 project - 0.49 - came out
     * with grey bars down both sides, which is the one shape the grid most needs to show
     * truthfully. 0.45 clears 20:9 and still catches a canvas that would run off the screen.
     */
    const val MIN = 0.45f

    /** Widest card allowed, as width/height. */
    const val MAX = 1.9f

    /** Used when a project has no usable size, rather than dividing by zero. */
    const val FALLBACK = 1f

    /** Width-to-height ratio for a card showing a [width] x [height] canvas. */
    fun forCanvas(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return FALLBACK
        return (width.toFloat() / height.toFloat()).coerceIn(MIN, MAX)
    }

    /** Whether [width] x [height] is extreme enough that its card no longer matches it. */
    fun isClamped(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return true
        val raw = width.toFloat() / height.toFloat()
        return raw < MIN || raw > MAX
    }
}
