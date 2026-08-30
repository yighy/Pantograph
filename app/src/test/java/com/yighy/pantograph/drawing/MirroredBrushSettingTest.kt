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
        id = "1", name = "Loaded",
        size = 24f, softness = 0.3f, opacity = 0.7f, flow = 0.55f,
        spacing = 0.25f, smoothing = 0.8f, rotation = 45f,
        sizeJitter = 0.35f, sizeMultiplier = 2.5f,
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
    fun `the list covers every property a preset carries`() {
        // Derived from BrushConfig rather than listed out, so a property added to a preset
        // cannot quietly stay per-project - which is the split this list exists to abolish.
        // Excluded: the preset's own identity, and the two assets, which are mirrored by hand
        // in the ViewModel because they have to be decoded before they mean anything.
        val notMirrored = setOf("id", "name", "folderId", "tipUri", "textureUri")
        val carried = BrushConfig::class.java.declaredFields
            .filterNot { it.name.startsWith("$") }
            .map { it.name }
            .filterNot { it in notMirrored }
        assertEquals(
            "a preset carries $carried, the mirror covers ${MirroredBrushSetting.ALL.map { it.label }}",
            carried.size,
            MirroredBrushSetting.ALL.size
        )
    }

    /** Reads this setting off [preset] and applies it, with the generics tied back together. */
    private fun <T> applyFromPreset(setting: MirroredBrushSetting<T>, state: DrawingState): DrawingState =
        setting.applyTo(state, setting.readFrom(preset))
}
