package com.yighy.paintcursor.drawing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A colour as the gate works on it. Hue in degrees, the other two 0..1. */
data class Hsv(val hue: Float, val saturation: Float, val value: Float) {

    fun withHue(h: Float) = copy(hue = h.coerceIn(0f, 360f))
    fun withSaturation(s: Float) = copy(saturation = s.coerceIn(0f, 1f))
    fun withValue(v: Float) = copy(value = v.coerceIn(0f, 1f))
}

/**
 * Hue/saturation/brightness for the colour satellite, converted by hand rather than through
 * `android.graphics.Color` so the round trip can be checked on the JVM - the same reason
 * [FloodFill] and [MagicSelect] were pulled out of the Android types.
 *
 * The part worth knowing about is [readFrom]. HSV carries information RGB does not: a grey has
 * no hue, and black has neither hue nor saturation. Re-reading the brush colour on every
 * pointer event would therefore throw the hue away the instant saturation reached zero, and
 * dragging back up would come out red instead of the colour the user started from. The gate
 * keeps its own triple and takes from the live colour only what that colour can still tell it.
 */
object ColourGate {

    /** Below this a channel spread is rounding noise rather than a hue. */
    private const val EPSILON = 1e-6f

    /**
     * Reads an RGB colour (channels 0..1) as HSV, falling back to [previous] for whichever
     * components this particular colour cannot express.
     */
    fun readFrom(red: Float, green: Float, blue: Float, previous: Hsv): Hsv {
        val maxC = max(red, max(green, blue))
        val minC = min(red, min(green, blue))
        val delta = maxC - minC

        val hue = when {
            // Grey, including black and white: keep whatever hue the gate was already on.
            delta <= EPSILON -> previous.hue
            maxC == red -> 60f * (((green - blue) / delta) % 6f)
            maxC == green -> 60f * (((blue - red) / delta) + 2f)
            else -> 60f * (((red - green) / delta) + 4f)
        }
        // Black has no saturation either - every hue collapses onto it.
        val saturation = if (maxC <= EPSILON) previous.saturation else delta / maxC

        return Hsv(
            hue = ((hue % 360f) + 360f) % 360f,
            saturation = saturation.coerceIn(0f, 1f),
            value = maxC.coerceIn(0f, 1f)
        )
    }

    /** The RGB channels (0..1) for [hsv], as `[red, green, blue]`. */
    fun toRgb(hsv: Hsv): FloatArray {
        val chroma = hsv.value * hsv.saturation
        val sector = hsv.hue.coerceIn(0f, 360f) / 60f
        val second = chroma * (1f - abs((sector % 2f) - 1f))
        val (r, g, b) = when {
            sector < 1f -> Triple(chroma, second, 0f)
            sector < 2f -> Triple(second, chroma, 0f)
            sector < 3f -> Triple(0f, chroma, second)
            sector < 4f -> Triple(0f, second, chroma)
            sector < 5f -> Triple(second, 0f, chroma)
            else -> Triple(chroma, 0f, second)
        }
        val floor = hsv.value - chroma
        return floatArrayOf(r + floor, g + floor, b + floor)
    }
}
