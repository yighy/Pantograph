package com.yighy.pantograph.drawing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backs the studio's "Saved" badge. A comparison that quietly ignores a parameter doesn't
 * break anything visibly - it just tells the user their work is saved when it isn't.
 */
class BrushConfigTest {

    private val preset = BrushConfig(id = "1", name = "Inker", size = 24f, flow = 0.8f)

    @Test
    fun `identity is not part of the comparison`() {
        // The brush in hand is built with no id or name, and must still match its preset.
        val inHand = preset.copy(id = "", name = "")
        assertTrue(inHand.paintsSameAs(preset))
    }

    @Test
    fun `a renamed preset still paints the same`() {
        assertTrue(preset.copy(name = "Liner").paintsSameAs(preset))
    }

    @Test
    fun `filing a preset into a folder is not an unsaved edit`() {
        // Moving between folders is persisted on its own the moment it happens, so counting it
        // as a change would leave the studio showing "Save" over a preset that is up to date.
        assertTrue(preset.copy(folderId = 7L).paintsSameAs(preset))
        assertTrue(preset.copy(folderId = null).paintsSameAs(preset.copy(folderId = 3L)))
    }

    @Test
    fun `any changed parameter counts as unsaved`() {
        assertFalse(preset.copy(size = 25f).paintsSameAs(preset))
        assertFalse(preset.copy(flow = 0.81f).paintsSameAs(preset))
        assertFalse(preset.copy(scatterJitter = 0.1f).paintsSameAs(preset))
        assertFalse(preset.copy(velocityEnabled = true).paintsSameAs(preset))
        assertFalse(preset.copy(tipUri = "content://tip").paintsSameAs(preset))
    }

    @Test
    fun `every parameter is covered, including ones added later`() {
        // The point of comparing whole values instead of listing fields: this fails the day
        // someone adds a parameter and leaves it out of the equality check.
        // id, name and folderId say which preset this is and where it is filed, not how it
        // paints, so paintsSameAs normalises them away and they are excluded here too.
        val identityFields = setOf("id", "name", "folderId")
        val fields = BrushConfig::class.java.declaredFields
            .map { it.name }
            .filterNot { it in identityFields || it.startsWith("$") }
        assertTrue("expected the config to carry parameters", fields.isNotEmpty())

        // Nudging any single one of them must register as a change.
        val nudged = listOf(
            preset.copy(size = preset.size + 1f),
            preset.copy(softness = 0.5f),
            preset.copy(opacity = 0.5f),
            preset.copy(flow = 0.5f),
            preset.copy(spacing = 0.5f),
            preset.copy(smoothing = 0.9f),
            preset.copy(rotation = 45f),
            preset.copy(rotationJitter = 10f),
            preset.copy(sizeJitter = 0.2f),
            preset.copy(scatterJitter = 0.2f),
            preset.copy(flowJitter = 0.2f),
            preset.copy(rotationFollow = 0.5f),
            preset.copy(tipUri = "x"),
            preset.copy(textureUri = "x"),
            preset.copy(velocityEnabled = true),
            preset.copy(velocitySize = 0.3f),
            preset.copy(velocityFlow = 0.3f),
            preset.copy(velocityScatter = 0.3f),
            preset.copy(sizeMultiplier = 2f),
            preset.copy(tipShape = TipShape.Square),
            preset.copy(tipRatio = 0.4f),
            preset.copy(antiAlias = false),
            preset.copy(hueJitter = 0.2f),
            preset.copy(saturationJitter = 0.2f),
            preset.copy(valueJitter = 0.2f),
            preset.copy(smudge = 0.6f),
            preset.copy(smudgeLength = 0.9f)
        )
        assertTrue(
            "a parameter was added without a case here: ${fields.size} fields, ${nudged.size} cases",
            nudged.size == fields.size
        )
        nudged.forEach { assertFalse(it.paintsSameAs(preset)) }
    }

    @Test
    fun `toBrushConfig carries every parameter out of the state`() {
        // The gap that let tip shape and ratio through: BrushConfig gained them, the nudge test
        // above covered them, and nothing checked that the conversion from the brush in hand
        // actually reads them. It is the conversion the studio compares against to decide
        // whether a preset has unsaved changes, and keys its preview on - so a parameter missing
        // here is a control that silently does nothing.
        val loaded = DrawingState(
            selectedWidth = 33f,
            brushSoftness = 0.31f,
            brushOpacity = 0.32f,
            brushFlow = 0.33f,
            brushSpacing = 0.34f,
            brushSmoothing = 0.35f,
            brushRotation = 36f,
            brushRotationJitter = 37f,
            sizeJitter = 0.38f,
            scatterJitter = 0.39f,
            flowJitter = 0.4f,
            rotationFollow = 0.41f,
            brushTipUri = "tip",
            brushTextureUri = "texture",
            velocityEnabled = true,
            velocitySizeAmount = 0.42f,
            velocityFlowAmount = 0.43f,
            velocityScatterAmount = 0.44f,
            sizeMultiplier = 2.5f,
            tipShape = TipShape.Square,
            tipRatio = 0.45f,
            antiAlias = false,
            hueJitter = 0.46f,
            saturationJitter = 0.47f,
            valueJitter = 0.48f,
            smudge = 0.49f,
            smudgeLength = 0.9f
        )

        val converted = loaded.toBrushConfig()
        val defaults = BrushConfig()
        val identityFields = setOf("id", "name", "folderId")

        BrushConfig::class.java.declaredFields
            .filterNot { it.name in identityFields || it.name.startsWith("$") }
            .forEach { field ->
                field.isAccessible = true
                assertTrue(
                    "${field.name} came out of the state at its default - the conversion drops it",
                    field.get(converted) != field.get(defaults)
                )
            }
    }
}