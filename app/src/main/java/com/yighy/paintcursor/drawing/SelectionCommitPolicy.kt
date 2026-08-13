package com.yighy.paintcursor.drawing

/**
 * Decides what history a floating selection needs when it lands, kept separate from
 * [DrawingViewModel] so the rules can be tested without bitmaps.
 */
object SelectionCommitPolicy {

    /**
     * Whether committing needs its own history entry for the layer being pasted into.
     *
     * A cut already saved a full snapshot of its source layer at lift time, and that entry
     * covers the paste as well - but only while the paste lands back on that same layer.
     * Once the user switches layers mid-move, the target has no entry of its own, and an undo
     * would restore the cut pixels while leaving the pasted copy behind, duplicating the area.
     *
     * A copy never snapshots anything at lift, so it always needs one.
     */
    fun needsTargetSnapshot(
        fromCut: Boolean,
        sourceLayerId: Long?,
        targetLayerId: Long
    ): Boolean = !fromCut || (sourceLayerId != null && sourceLayerId != targetLayerId)
}
