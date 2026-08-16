package com.yighy.paintcursor.drawing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The failure this guards against is not a wrong colour, which anyone would see - it is a
 * sweep that cannot be undone. Drag saturation to zero and back and you should arrive where
 * you started, not at red.
 */
class ColourGateTest {

    private val neutral = Hsv(0f, 1f, 1f)

    private fun assertRgb(expected: Triple<Float, Float, Float>, actual: FloatArray, message: String) {
        assertEquals("$message red", expected.first, actual[0], 0.001f)
        assertEquals("$message green", expected.second, actual[1], 0.001f)
        assertEquals("$message blue", expected.third, actual[2], 0.001f)
    }

    @Test
    fun `the primaries land on their textbook hues`() {
        assertRgb(Triple(1f, 0f, 0f), ColourGate.toRgb(Hsv(0f, 1f, 1f)), "red")
        assertRgb(Triple(0f, 1f, 0f), ColourGate.toRgb(Hsv(120f, 1f, 1f)), "green")
        assertRgb(Triple(0f, 0f, 1f), ColourGate.toRgb(Hsv(240f, 1f, 1f)), "blue")
        assertRgb(Triple(1f, 0f, 0f), ColourGate.toRgb(Hsv(360f, 1f, 1f)), "wrapped red")
    }

    @Test
    fun `white and black come out of the ends of the brightness axis`() {
        assertRgb(Triple(1f, 1f, 1f), ColourGate.toRgb(Hsv(200f, 0f, 1f)), "white")
        assertRgb(Triple(0f, 0f, 0f), ColourGate.toRgb(Hsv(200f, 1f, 0f)), "black")
    }

    @Test
    fun `a colour survives the round trip`() {
        val cases = listOf(
            Hsv(0f, 1f, 1f), Hsv(45f, 0.5f, 0.8f), Hsv(120f, 0.25f, 0.3f),
            Hsv(200f, 0.9f, 1f), Hsv(280f, 0.6f, 0.55f), Hsv(330f, 1f, 0.2f)
        )
        for (hsv in cases) {
            val rgb = ColourGate.toRgb(hsv)
            val back = ColourGate.readFrom(rgb[0], rgb[1], rgb[2], neutral)
            assertEquals("$hsv hue", hsv.hue, back.hue, 0.1f)
            assertEquals("$hsv saturation", hsv.saturation, back.saturation, 0.005f)
            assertEquals("$hsv value", hsv.value, back.value, 0.005f)
        }
    }

    @Test
    fun `a grey keeps the hue the gate was already on`() {
        // The whole point of readFrom: mid-sweep, desaturating must not lose the hue.
        val working = Hsv(210f, 0.8f, 0.9f)
        val grey = ColourGate.toRgb(working.withSaturation(0f))
        val back = ColourGate.readFrom(grey[0], grey[1], grey[2], working)
        assertEquals(210f, back.hue, 0.001f)
        assertEquals(0f, back.saturation, 0.001f)
    }

    @Test
    fun `black keeps both the hue and the saturation`() {
        val working = Hsv(150f, 0.7f, 0.4f)
        val back = ColourGate.readFrom(0f, 0f, 0f, working)
        assertEquals(150f, back.hue, 0.001f)
        assertEquals(0.7f, back.saturation, 0.001f)
        assertEquals(0f, back.value, 0.001f)
    }

    @Test
    fun `desaturating to zero and back returns the colour it started from`() {
        // The sweep the user actually performs, run end to end.
        var working = Hsv(280f, 0.6f, 0.75f)
        val start = ColourGate.toRgb(working)

        for (saturation in listOf(0.4f, 0.2f, 0f)) {
            val rgb = ColourGate.toRgb(working.withSaturation(saturation))
            working = ColourGate.readFrom(rgb[0], rgb[1], rgb[2], working)
        }
        for (saturation in listOf(0.2f, 0.4f, 0.6f)) {
            val rgb = ColourGate.toRgb(working.withSaturation(saturation))
            working = ColourGate.readFrom(rgb[0], rgb[1], rgb[2], working)
        }

        assertRgb(Triple(start[0], start[1], start[2]), ColourGate.toRgb(working), "after the round sweep")
    }

    @Test
    fun `components clamp instead of wrapping past their ends`() {
        assertEquals(360f, Hsv(0f, 1f, 1f).withHue(400f).hue, 0.001f)
        assertEquals(0f, Hsv(0f, 1f, 1f).withHue(-20f).hue, 0.001f)
        assertEquals(1f, Hsv(0f, 0f, 1f).withSaturation(2f).saturation, 0.001f)
        assertEquals(0f, Hsv(0f, 0f, 1f).withValue(-1f).value, 0.001f)
    }
}
