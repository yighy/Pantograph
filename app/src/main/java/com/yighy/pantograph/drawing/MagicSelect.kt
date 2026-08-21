package com.yighy.pantograph.drawing

import kotlin.math.abs

/**
 * Builds the selection mask behind the wand and colour-select tools.
 *
 * Wand walks outwards from the tapped pixel and stops at the first pixel outside the tolerance;
 * colour select takes every similar pixel on the layer wherever it sits. Both share the fill
 * tolerance setting and the same match rule.
 *
 * Free of Android types - it takes and returns pixel arrays - so the match rule and the
 * contiguity walk can be covered by plain JVM tests.
 */
object MagicSelect {

    /** Opaque white: what a selected pixel looks like in a mask bitmap. */
    const val SELECTED = 0xFFFFFFFF.toInt()

    /**
     * Returns a mask array the size of [pixels]: [SELECTED] where the pixel is selected,
     * 0 (fully transparent) everywhere else.
     *
     * With [contiguous] the selection is the connected region containing ([startX], [startY]);
     * without it, every matching pixel in the array is taken.
     */
    fun computeMask(
        pixels: IntArray,
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
        tolerance: Int,
        contiguous: Boolean
    ): IntArray {
        val mask = IntArray(width * height)
        if (width <= 0 || height <= 0) return mask
        if (startX !in 0 until width || startY !in 0 until height) return mask

        val target = pixels[startY * width + startX]
        val tA = (target ushr 24) and 0xFF
        val tR = (target ushr 16) and 0xFF
        val tG = (target ushr 8) and 0xFF
        val tB = target and 0xFF

        fun matches(c: Int): Boolean {
            val a = (c ushr 24) and 0xFF
            if (abs(a - tA) > tolerance) return false
            // Fully transparent pixels have meaningless RGB: alpha match is enough
            if (tA <= tolerance && a <= tolerance) return true
            return abs(((c ushr 16) and 0xFF) - tR) <= tolerance &&
                   abs(((c ushr 8) and 0xFF) - tG) <= tolerance &&
                   abs((c and 0xFF) - tB) <= tolerance
        }

        if (contiguous) {
            val visited = java.util.BitSet(width * height)
            val queue: java.util.Queue<Int> = java.util.LinkedList()
            val start = startY * width + startX
            queue.add(start)
            visited.set(start)
            while (queue.isNotEmpty()) {
                val index = queue.remove()
                if (!matches(pixels[index])) continue
                mask[index] = SELECTED
                val px = index % width
                val py = index / width
                if (px > 0 && !visited.get(index - 1)) { visited.set(index - 1); queue.add(index - 1) }
                if (px < width - 1 && !visited.get(index + 1)) { visited.set(index + 1); queue.add(index + 1) }
                if (py > 0 && !visited.get(index - width)) { visited.set(index - width); queue.add(index - width) }
                if (py < height - 1 && !visited.get(index + width)) { visited.set(index + width); queue.add(index + width) }
            }
        } else {
            for (i in pixels.indices) {
                if (matches(pixels[i])) mask[i] = SELECTED
            }
        }
        return mask
    }
}
