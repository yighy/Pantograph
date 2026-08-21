package com.yighy.pantograph.drawing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These settings are mirrored from a preference back into the brush in hand, so one wired to
 * the wrong field does not just fail to apply - it overwrites a neighbouring setting every time
 * the preference re-emits. That is invisible until someone notices a brush growing a jitter it
 * was never saved with.
 */
class MirroredBrushSettingTest {

    /** A preset whose every mirrored value differs from the state defaults. */
    private val preset = BrushConfig(
        id = "1", name = "Loaded", size = 24f, flow = 1f,
        rotationJitter = 33f,
        scatterJitter = 0.42f,
        flowJitter = 0.17f,
        rotationFollow = 0.61f,
        velocityEnabled = true,
        velocitySize = 0.9f,
        velocityFlow = -0.4f,
        velocityScatter = 1.3f
    )

    private fun stateFields(state: DrawingState): Map<String, Any?> =
        DrawingState::class.java.declaredFields
            .filterNot { it.name.startsWith("$") }
            .associate { it.isAccessible = true; it.name to it.get(state) }

    private fun changedFields(before: DrawingState, after: DrawingState): List<String> {
        val a = stateFields(before)
        val b = stateFields(after)
        return a.keys.filter { a[it] != b[it] }
    }

    @Test
    fun `each setting writes the state field it claims, and only that one`() {
        // Catches the copy-paste where a new entry keeps the previous one's copy(...) target.
        for (setting in MirroredBrushSetting.ALL) {
            val after = applyFromPreset(setting, DrawingState())
            val changed = changedFields(DrawingState(), after)
            assertEquals(
                "${setting.label} changed $changed",
                listOf(setting.stateField),
                changed
            )
        }
    }

    @Test
    fun `no two settings own the same state field`() {
        val fields = MirroredBrushSetting.ALL.map { it.stateField }
        assertEquals("duplicate owners in $fields", fields.size, fields.toSet().size)
    }

    @Test
    fun `every claimed field actually exists on the state`() {
        val known = stateFields(DrawingState()).keys
        for (setting in MirroredBrushSetting.ALL) {
            assertTrue("${setting.stateField} is not a DrawingState field", setting.stateField in known)
        }
    }

    @Test
    fun `loading a preset leaves no mirrored setting on its old value`() {
        // The whole list applied at once, which is what selecting a preset amounts to.
        var state = DrawingState()
        for (setting in MirroredBrushSetting.ALL) state = applyFromPreset(setting, state)

        assertEquals(33f, state.brushRotationJitter, 0.0001f)
        assertEquals(0.42f, state.scatterJitter, 0.0001f)
        assertEquals(0.17f, state.flowJitter, 0.0001f)
        assertEquals(0.61f, state.rotationFollow, 0.0001f)
        assertEquals(true, state.velocityEnabled)
        assertEquals(0.9f, state.velocitySizeAmount, 0.0001f)
        assertEquals(-0.4f, state.velocityFlowAmount, 0.0001f)
        assertEquals(1.3f, state.velocityScatterAmount, 0.0001f)
    }

    @Test
    fun `the list covers every velocity and jitter parameter a preset carries`() {
        // The bug this replaced: scatter, flow jitter and follow-direction were mirrored in but
        // never written back, so they were restored from another preset's global.
        val expected = setOf(
            "brushRotationJitter", "scatterJitter", "flowJitter", "rotationFollow",
            "velocityEnabled", "velocitySizeAmount", "velocityFlowAmount", "velocityScatterAmount"
        )
        assertEquals(expected, MirroredBrushSetting.ALL.map { it.stateField }.toSet())
    }

    /** Reads this setting off [preset] and applies it, with the generics tied back together. */
    private fun <T> applyFromPreset(setting: MirroredBrushSetting<T>, state: DrawingState): DrawingState =
        setting.applyTo(state, setting.readFrom(preset))
}
