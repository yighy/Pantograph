package com.yighy.pantograph.drawing

/**
 * Where a chip's readout goes while its value is being dragged: above the finger, and below it
 * when there is no room above - the rule the satellites' readouts follow - and across, half a
 * window away from the finger, in the other half of the screen from it.
 *
 * It used to sit beside the fingertip, at its height. That kept it clear of the fingertip itself,
 * but a thumb reaching in from the side covers exactly the band a fingertip sits in, so the one
 * height it chose was the one most likely to be under the hand.
 */
object ReadoutPlacement {

    /**
     * Whether the readout goes above the fingertip, given which side it was on a moment ago.
     *
     * Above whenever it fits: only the tip of the finger is up there to clear. It leaves the
     * moment it stops fitting, but only comes back with [hysteresis] to spare. The readout
     * follows the finger, and a finger held still right at the height where it stops fitting
     * jitters by a pixel - without the margin, that sends it flipping from one side to the other
     * on every frame.
     */
    fun staysAbove(
        fingerY: Int,
        height: Int,
        gapAbove: Int,
        margin: Int,
        wasAbove: Boolean,
        hysteresis: Int
    ): Boolean {
        val spare = fingerY - gapAbove - height - margin
        return if (wasAbove) spare >= 0 else spare >= hysteresis
    }

    /**
     * Whether the readout goes in the left half of the window, given the finger at [fingerX] in
     * a window [windowWidth] wide and which half it was in a moment ago.
     *
     * The half the finger is not in: a finger on the right half most likely belongs to the right
     * hand, which reaches in from the right, and the other half is where it is not. Pass null for
     * [wasLeft] where the finger comes down, which simply picks by the middle. After that the
     * readout changes halves only once the finger is [hysteresis] past the middle: at the
     * middle it jumps the whole way across, and a finger held still there jitters by a pixel.
     */
    fun leansLeft(fingerX: Int, windowWidth: Int, wasLeft: Boolean?, hysteresis: Int): Boolean {
        val middle = windowWidth / 2
        return when (wasLeft) {
            null -> fingerX >= middle
            true -> fingerX >= middle - hysteresis
            false -> fingerX >= middle + hysteresis
        }
    }

    /**
     * Top-left corner for a readout of [width] by [height] above or below a finger at
     * ([fingerX], [fingerY]), everything in window pixels.
     *
     * The gaps differ on purpose. Above the point of contact there is only the tip of the finger
     * to clear, so [gapAbove] is small and the readout fits up there as often as it can. Below it
     * the whole finger follows, so [gapBelow] is large.
     *
     * Across, it is centred half a window away from the finger, on the side [leansLeft] picks:
     * the farther the finger goes towards it, the farther it goes ahead of the finger, until the
     * finger crosses the middle and it jumps to the other half. Kept [margin] inside the window
     * both ways, which near the middle pins it to the window's edge.
     */
    fun aboveOrBelow(
        fingerX: Int,
        fingerY: Int,
        width: Int,
        height: Int,
        windowWidth: Int,
        windowHeight: Int,
        above: Boolean,
        leansLeft: Boolean,
        gapAbove: Int,
        gapBelow: Int,
        margin: Int
    ): Pair<Int, Int> {
        val centre = if (leansLeft) fingerX - windowWidth / 2 else fingerX + windowWidth / 2
        val x = (centre - width / 2).coerceIn(margin, (windowWidth - width - margin).coerceAtLeast(margin))
        val y = if (above) fingerY - gapAbove - height else fingerY + gapBelow
        return x to y.coerceIn(margin, (windowHeight - height - margin).coerceAtLeast(margin))
    }
}
