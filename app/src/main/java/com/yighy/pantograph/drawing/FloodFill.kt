package com.yighy.pantograph.drawing

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
     *
     * [growPixels] then pushes the filled region that many pixels outwards, under the edge that
     * stopped it. An antialiased outline does not end at a pixel: it fades out over two or three
     * of them, and none of those are within tolerance of the empty middle, so a plain fill
     * leaves a thin unfilled hem hugging every line. Raising the tolerance until they match is
     * the wrong trade - by then it also matches the paler parts of the line itself and the fill
     * escapes. Growing reaches under the fade instead of trying to classify it.
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
        maskPixels: IntArray? = null,
        growPixels: Int = 0
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

        if (growPixels > 0) {
            grow(pixels, width, height, visited, replacementColor, maskPixels, growPixels)
        }
    }

    /**
     * Pushes [visited] outwards [steps] pixels, laying [color] *underneath* whatever is already
     * in each newly reached pixel.
     *
     * Underneath, not over: the pixels being reached are the outline's own fade, and painting
     * over them would eat the antialiasing that made the line look smooth - trading the hem for
     * a line one pixel thinner and harder. Compositing the fill behind them keeps their colour
     * and their coverage, and simply fills in the part of them that was transparent.
     */
    private fun grow(
        pixels: IntArray,
        width: Int,
        height: Int,
        visited: java.util.BitSet,
        color: Int,
        maskPixels: IntArray?,
        steps: Int
    ) {
        // Starts from the whole region rather than from a boundary worked out first: that scan
        // would cost what this pass costs anyway, and the passes after it only ever walk the
        // rim, which is where the work actually is.
        var frontier = IntArray(64)
        var frontierSize = 0
        fun addTo(index: Int) {
            if (frontierSize == frontier.size) frontier = frontier.copyOf(frontierSize * 2)
            frontier[frontierSize++] = index
        }
        var i = visited.nextSetBit(0)
        while (i >= 0) {
            addTo(i)
            i = visited.nextSetBit(i + 1)
        }

        repeat(steps) {
            val current = frontier
            val currentSize = frontierSize
            frontier = IntArray(64)
            frontierSize = 0
            for (k in 0 until currentSize) {
                val index = current[k]
                val cx = index % width
                val cy = index / width
                for (d in 0 until 4) {
                    val nx = cx + if (d == 0) -1 else if (d == 1) 1 else 0
                    val ny = cy + if (d == 2) -1 else if (d == 3) 1 else 0
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                    val n = ny * width + nx
                    if (visited.get(n)) continue
                    if (maskPixels != null && (maskPixels[n] ushr 24) == 0) continue
                    visited.set(n)
                    pixels[n] = under(pixels[n], color)
                    addTo(n)
                }
            }
            if (frontierSize == 0) return
        }
    }

    /** [top] composited over [bottom], both straight (non-premultiplied) ARGB. */
    internal fun under(top: Int, bottom: Int): Int {
        val ta = (top ushr 24) and 0xFF
        if (ta == 255) return top
        val ba = (bottom ushr 24) and 0xFF
        if (ba == 0) return top

        val outA = ta + ba * (255 - ta) / 255
        if (outA == 0) return 0
        fun channel(shift: Int): Int {
            val t = (top ushr shift) and 0xFF
            val b = (bottom ushr shift) and 0xFF
            return ((t * ta * 255 + b * ba * (255 - ta)) / (outA * 255)).coerceIn(0, 255)
        }
        return (outA shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
