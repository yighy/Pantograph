package com.yighy.paintcursor.drawing

/**
 * Where the three satellites sit around the floating button. Pure geometry, no Compose, so
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

        // Mode satellite: below unless that runs off the bottom.
        val columnGoesDown = fabY + fabSizePx + gapPx + miniPx <= screenHeight
        val modeY = if (columnGoesDown) fabY + fabSizePx + gapPx else fabY - gapPx - miniPx

        // Tool satellite: the left flank, mirroring the levels one on the right.
        //
        // Note it has no opposite side to flip to. The right flank is either wide enough, in
        // which case the levels pill is sitting in it, or too narrow, in which case nothing
        // fits there - those two conditions are exact negations of each other, so a right
        // flank is never both free and usable. When the left runs out of room the tool drops
        // into the vertical column past the mode pill instead.
        val leftFlankX = fabX - gapPx - miniPx
        val leftFlankFree = levelsFitsRight && leftFlankX >= 0f

        val toolStacked = !leftFlankFree

        val toolX = if (leftFlankFree) leftFlankX else fabX
        val toolY = when {
            leftFlankFree -> fabY
            columnGoesDown -> modeY + miniPx + gapPx
            else -> modeY - gapPx - miniPx
        }

        return Placement(
            levelsX = levelsX,
            levelsY = fabY,
            modeX = fabX,
            modeY = modeY,
            toolX = toolX,
            toolY = toolY,
            toolStacked = toolStacked
        )
    }
}
