package com.yighy.pantograph.drawing

import kotlin.math.sqrt

/**
 * How a brush's real size is shown on a list thumbnail.
 *
 * Drawn true to size, anything past a fraction of the strip's height fills it edge to edge, so
 * every large brush becomes the same black block and the thumbnail stops telling you anything.
 */
object BrushPreviewScale {

    /** Top of the brush size slider. */
    const val MAX_BRUSH_SIZE = 300f

    /** Share of the strip's height the thickest brush is allowed to take. */
    const val MAX_HEIGHT_SHARE = 0.45f

    /**
     * Maps [size] onto what a strip of [heightPx] can show.
     *
     * The square root is what keeps the thin end usable: a straight proportional map would
     * squeeze everything below about 30px into hairlines a pixel or two apart, which is the
     * range most brushes actually live in. Thickness here is indicative, not literal - the
     * Brush Studio preview is where true size is shown.
     */
    fun displaySize(size: Float, heightPx: Int): Float =
        heightPx * MAX_HEIGHT_SHARE * sqrt((size / MAX_BRUSH_SIZE).coerceIn(0f, 1f))
}
