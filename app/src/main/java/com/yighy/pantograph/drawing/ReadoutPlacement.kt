package com.yighy.pantograph.drawing

/**
 * Where a chip's readout goes while its value is being dragged.
 *
 * The satellites put theirs above the finger and fall back to below it. Neither works for the
 * chips. They sit at the top of the screen, where there is rarely room above, and below the
 * fingertip is the finger itself, reaching up from the hand - the fallback would have the value
 * hidden behind a knuckle instead of a nail. So the readout goes beside the fingertip, at its
 * height, which is the one direction that is clear up there.
 */
object ReadoutPlacement {

    /**
     * Top-left corner for a readout of [width] by [height] beside a finger at ([fingerX],
     * [fingerY]), everything in window pixels.
     *
     * Left of the finger when it fits: the chips are packed against the right edge, so that is
     * where the room is. Right of it when it does not. Centred on the fingertip vertically, so it
     * tracks a drag that is itself vertical, and kept [margin] inside the window throughout.
     */
    fun besideFinger(
        fingerX: Int,
        fingerY: Int,
        width: Int,
        height: Int,
        windowWidth: Int,
        windowHeight: Int,
        gap: Int,
        margin: Int
    ): Pair<Int, Int> {
        val left = fingerX - gap - width
        val x = if (left >= margin) left
            else (fingerX + gap).coerceAtMost((windowWidth - width - margin).coerceAtLeast(margin))
        val y = (fingerY - height / 2)
            .coerceIn(margin, (windowHeight - height - margin).coerceAtLeast(margin))
        return x to y
    }
}
