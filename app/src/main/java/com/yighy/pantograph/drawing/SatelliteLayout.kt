package com.yighy.pantograph.drawing

/**
 * Where the four satellites sit around the floating button. Pure geometry, no Compose, so
 * the edge cases - a button jammed into a corner, a landscape screen too short for the whole
 * column - can be checked without a device.
 *
 * All values are pixels in the same space as the button's own position.
 */
object SatelliteLayout {

    /**
     * @param toolStacked false in the normal case, where the tool satellite sits on a flank
     * beside the button and is turned on its side. True only when neither flank was available
     * and it had to fall back into the vertical column below the mode one, where it lies flat
     * - so its width and height swap with this flag.
     */
    data class Placement(
        val levelsX: Float,
        val levelsY: Float,
        val modeX: Float,
        val modeY: Float,
        val colourX: Float,
        val colourY: Float,
        val toolX: Float,
        val toolY: Float,
        val toolStacked: Boolean
    )

    fun place(
        fabX: Float,
        fabY: Float,
        fabSizePx: Float,
        miniPx: Float,
        gapPx: Float,
        screenWidth: Float,
        screenHeight: Float
    ): Placement {
        // Levels satellite: to the right unless that runs off the edge.
        val levelsFitsRight = fabX + fabSizePx + gapPx + miniPx <= screenWidth
        val levelsX = if (levelsFitsRight) fabX + fabSizePx + gapPx else fabX - gapPx - miniPx

        // The column above and below the button is shared by the mode, colour and (when it
        // has nowhere else to go) tool pills, so they are placed by slot rather than each
        // working out its own offset. Slot 0 is the one nearest the button on that side.
        fun downSlot(i: Int) = fabY + fabSizePx + gapPx + i * (miniPx + gapPx)
        fun upSlot(i: Int) = fabY - gapPx - miniPx - i * (miniPx + gapPx)

        // Mode satellite: below unless that runs off the bottom.
        val columnGoesDown = fabY + fabSizePx + gapPx + miniPx <= screenHeight
        val modeY = if (columnGoesDown) downSlot(0) else upSlot(0)

        // Colour satellite: above, mirroring the mode one below.
        //
        // The two want opposite ends of the same column, so whichever is displaced by an edge
        // lands on the other's side and has to queue behind it. At the bottom of the screen
        // the mode pill has already flipped up into slot 0, which puts colour in slot 1; at
        // the top, colour is the one that flips and follows the mode pill down.
        val colourFitsAbove = fabY - gapPx - miniPx >= 0f
        val colourY = when {
            !columnGoesDown -> upSlot(1)
            colourFitsAbove -> upSlot(0)
            else -> downSlot(1)
        }

        // Tool satellite: the left flank, mirroring the levels one on the right.
        //
        // Note it has no opposite flank to flip to. The right flank is either wide enough, in
        // which case the levels pill is sitting in it, or too narrow, in which case nothing
        // fits there - those two conditions are exact negations of each other, so a right
        // flank is never both free and usable. When the left runs out of room the tool drops
        // into the vertical column, behind whatever is already queued there.
        val leftFlankX = fabX - gapPx - miniPx
        val leftFlankFree = levelsFitsRight && leftFlankX >= 0f

        val toolStacked = !leftFlankFree

        val toolX = if (leftFlankFree) leftFlankX else fabX
        val toolY = when {
            leftFlankFree -> fabY
            // Up: mode took slot 0 and colour slot 1.
            !columnGoesDown -> upSlot(2)
            // Down: mode has slot 0, and colour is only down here if it could not fit above.
            colourFitsAbove -> downSlot(1)
            else -> downSlot(2)
        }

        return Placement(
            levelsX = levelsX,
            levelsY = fabY,
            modeX = fabX,
            modeY = modeY,
            colourX = fabX,
            colourY = colourY,
            toolX = toolX,
            toolY = toolY,
            toolStacked = toolStacked
        )
    }
}
