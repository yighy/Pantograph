package com.yighy.paintcursor.drawing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Getting these wrong doesn't crash - it corrupts undo, which the user only discovers after
 * losing work. The cross-layer case in particular used to duplicate the selected area.
 */
class SelectionCommitPolicyTest {

    private val source = 1L
    private val other = 2L

    @Test
    fun `a copy always needs its own entry`() {
        // Nothing was snapshotted at lift time, so the paste is the only mutation on record.
        assertTrue(SelectionCommitPolicy.needsTargetSnapshot(false, source, source))
        assertTrue(SelectionCommitPolicy.needsTargetSnapshot(false, source, other))
    }

    @Test
    fun `a cut landing back on its own layer is already covered by the lift entry`() {
        // One undo must take the whole move back, not half of it.
        assertFalse(SelectionCommitPolicy.needsTargetSnapshot(true, source, source))
    }

    @Test
    fun `a cut landing on another layer needs an entry for that layer`() {
        // Without this the undo restores the cut pixels and leaves the pasted ones,
        // duplicating the selection across two layers.
        assertTrue(SelectionCommitPolicy.needsTargetSnapshot(true, source, other))
    }

    @Test
    fun `an unknown source is treated as same-layer for a cut`() {
        // Defensive: a null source can only mean no lift happened through liftSelection,
        // so there is no second layer to reconcile and the lift entry stands.
        assertFalse(SelectionCommitPolicy.needsTargetSnapshot(true, null, other))
    }
}
