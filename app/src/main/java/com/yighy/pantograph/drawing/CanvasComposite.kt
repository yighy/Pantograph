package com.yighy.pantograph.drawing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

/**
 * The drawing as a single picture, scaled down: the home screen's thumbnail and the timelapse's
 * frames are both this.
 */
object CanvasComposite {

    /**
     * Renders the visible layers of [state] over white, at most [maxSide] pixels on its longer
     * side and never above the canvas's own size. Null while the canvas has no size yet.
     *
     * [evenSize] rounds both sides down to even numbers, which video encoders require.
     */
    fun render(state: DrawingState, layerBitmaps: Map<Long, Bitmap>, maxSide: Int, evenSize: Boolean = false): Bitmap? {
        if (state.canvasWidth <= 0 || state.canvasHeight <= 0) return null
        // Rendered directly at the size wanted: a full-resolution render scaled afterwards is
        // wasted work for a preview.
        val scale = min(1f, maxSide.toFloat() / max(state.canvasWidth, state.canvasHeight))
        var w = (state.canvasWidth * scale).toInt().coerceAtLeast(1)
        var h = (state.canvasHeight * scale).toInt().coerceAtLeast(1)
        if (evenSize) {
            w = (w / 2 * 2).coerceAtLeast(2)
            h = (h / 2 * 2).coerceAtLeast(2)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.scale(w.toFloat() / state.canvasWidth, h.toFloat() / state.canvasHeight)
        // Reference layers are working aids, not artwork: they are on the canvas to be traced
        // over, and have no business in the picture of the project.
        state.layers.filter { it.isVisible && !it.isTrace }.forEach { layer ->
            layerBitmaps[layer.id]?.let {
                val paint = Paint().apply {
                    alpha = (layer.opacity * 255).toInt()
                    isFilterBitmap = true
                }
                canvas.drawBitmap(it, 0f, 0f, paint)
            }
        }
        return out
    }
}
