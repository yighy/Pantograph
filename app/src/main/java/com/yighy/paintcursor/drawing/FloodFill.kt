package com.yighy.paintcursor.drawing

import kotlin.math.abs
import kotlin.math.max

/**
 * Scanline flood fill over a raw ARGB pixel array.
 *
 * Expands whole horizontal runs at a time and never allocates per pixel (the naive per-pixel
 * queue boxed millions of points on large canvases, which is what made fills slow and GC-heavy).
 *
 * Deliberately free of Android types - it takes the pixels, not the Bitmap - so the run
 * expansion and the tolerance rule can be covered by plain JVM tests.
 */
object FloodFill {

    /**
     * Fills the region connected to ([x], [y]) in [pixels] with [replacementColor], in place.
     *
     * A pixel joins the region when every channel is within [tolerance] of [targetColor] and,
     * if [maskPixels] is given, its mask alpha is non-zero - that's how an active selection
     * stops the fill leaking outside the selected area.
     */
    fun fill(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        targetColor: Int,
        replacementColor: Int,
        tolerance: Float,
        maskPixels: IntArray? = null
    ) {
        if (width <= 0 || height <= 0) return
        if (x !in 0 until width || y !in 0 until height) return

        val visited = java.util.BitSet(width * height)

        val targetA = (targetColor ushr 24) and 0xFF
        val targetR = (targetColor ushr 16) and 0xFF
        val targetG = (targetColor ushr 8) and 0xFF
        val targetB = targetColor and 0xFF

        // Fillable = not yet filled, inside the selection, and within tolerance of the target
        fun matches(index: Int): Boolean {
            if (visited.get(index)) return false
            if (maskPixels != null && (maskPixels[index] ushr 24) == 0) return false
            val color = pixels[index]
            val diffA = abs(((color ushr 24) and 0xFF) - targetA)
            val diffR = abs(((color ushr 16) and 0xFF) - targetR)
            val diffG = abs(((color ushr 8) and 0xFF) - targetG)
            val diffB = abs((color and 0xFF) - targetB)
            return max(max(diffR, diffG), diffB) <= tolerance && diffA <= tolerance
        }

        // Stack of seed pixel indices; one seed per horizontal run
        var stack = IntArray(1024)
        var sp = 0
        fun push(index: Int) {
            if (sp == stack.size) stack = stack.copyOf(sp * 2)
            stack[sp++] = index
        }

        push(y * width + x)
        while (sp > 0) {
            val seed = stack[--sp]
            if (!matches(seed)) continue

            val py = seed / width
            val rowStart = py * width
            var x0 = seed - rowStart
            var x1 = x0
            while (x0 > 0 && matches(rowStart + x0 - 1)) x0--
            while (x1 < width - 1 && matches(rowStart + x1 + 1)) x1++
            for (i in rowStart + x0..rowStart + x1) {
                pixels[i] = replacementColor
                visited.set(i)
            }

            // Seed the adjacent rows: one push per contiguous fillable run under the span
            // (the popped seed re-expands past the span bounds if the run is wider)
            for (ny in py - 1..py + 1 step 2) {
                if (ny < 0 || ny >= height) continue
                val nRow = ny * width
                var i = x0
                while (i <= x1) {
                    if (matches(nRow + i)) {
                        push(nRow + i)
                        do i++ while (i <= x1 && matches(nRow + i))
                    } else i++
                }
            }
        }
    }
}
