package com.yighy.pantograph.drawing

/**
 * When the canvas should be re-fitted to the viewport.
 *
 * The rule this replaced asked whether the view was untouched - scale exactly 1 and no offset -
 * and fitted only then. That works once and never again, because the state carries a placeholder
 * canvas size until the project loads: the first fit happens against those placeholder
 * dimensions, which moves the scale off 1, and the real dimensions then arrive to find the view
 * already "touched". A project that was not close to the placeholder opened at the wrong zoom
 * and had to be fitted by hand.
 *
 * So the question is not whether the view has been moved but whether *this* canvas has ever been
 * fitted. Size is remembered rather than a flag, which is what lets a late-arriving project size
 * be recognised as a different canvas from the placeholder it replaced.
 */
object CanvasFitPolicy {

    /**
     * @param fitRequested the user pressed fit-to-screen; always wins.
     * @param lastFittedWidth zero when nothing has been fitted yet.
     * @param untouched the view is still at its resting scale and offset, so re-fitting it after
     * a viewport change costs the user nothing.
     */
    fun shouldFit(
        fitRequested: Boolean,
        canvasWidth: Int,
        canvasHeight: Int,
        lastFittedWidth: Int,
        lastFittedHeight: Int,
        untouched: Boolean
    ): Boolean {
        if (canvasWidth <= 0 || canvasHeight <= 0) return false
        if (fitRequested) return true
        // A canvas of a size never fitted before - first show, or the real project arriving
        // over the placeholder.
        if (canvasWidth != lastFittedWidth || canvasHeight != lastFittedHeight) return true
        // Same canvas, so this is a viewport change. Re-fit only while the user has not framed
        // anything themselves; otherwise a rotation would throw away their pan and zoom.
        return untouched
    }
}
