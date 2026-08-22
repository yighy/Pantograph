package com.yighy.pantograph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid sizes every card from these numbers, so an unbounded one does not misdraw a card -
 * it produces one several screens tall, which is only discovered by someone who happens to own
 * an unusual canvas.
 */
class ProjectPreviewRatioTest {

    @Test
    fun `an ordinary canvas keeps its own proportion`() {
        assertEquals(1f, ProjectPreviewRatio.forCanvas(1000, 1000), 0.0001f)
        assertEquals(0.5625f, ProjectPreviewRatio.forCanvas(1080, 1920), 0.0001f)
        assertEquals(1.5f, ProjectPreviewRatio.forCanvas(1500, 1000), 0.0001f)
    }

    @Test
    fun `a canvas the shape of a phone screen is shown as it is`() {
        // The common case, and the one the floor used to letterbox: 1080x2201 is 0.49.
        for ((w, h) in listOf(1080 to 2201, 1080 to 2400, 1080 to 1920)) {
            assertFalse("${w}x$h should not be clamped", ProjectPreviewRatio.isClamped(w, h))
            assertEquals(w.toFloat() / h, ProjectPreviewRatio.forCanvas(w, h), 0.0001f)
        }
    }

    @Test
    fun `extremes are clamped rather than allowed to run off the screen`() {
        assertEquals(ProjectPreviewRatio.MIN, ProjectPreviewRatio.forCanvas(500, 4000), 0.0001f)
        assertEquals(ProjectPreviewRatio.MAX, ProjectPreviewRatio.forCanvas(8000, 1000), 0.0001f)
    }

    @Test
    fun `a canvas with no size falls back instead of dividing by zero`() {
        for (size in listOf(0 to 1000, 1000 to 0, 0 to 0, -100 to 500)) {
            val r = ProjectPreviewRatio.forCanvas(size.first, size.second)
            assertEquals("$size", ProjectPreviewRatio.FALLBACK, r, 0.0001f)
        }
    }

    @Test
    fun `every result is inside the allowed span`() {
        val cases = listOf(1 to 9999, 9999 to 1, 1080 to 1920, 1920 to 1080, 512 to 512, 3 to 7)
        for ((w, h) in cases) {
            val r = ProjectPreviewRatio.forCanvas(w, h)
            assertTrue("${w}x$h gave $r", r >= ProjectPreviewRatio.MIN && r <= ProjectPreviewRatio.MAX)
        }
    }

    @Test
    fun `isClamped marks exactly the canvases the card cannot match`() {
        assertFalse(ProjectPreviewRatio.isClamped(1080, 1920))
        assertFalse(ProjectPreviewRatio.isClamped(1000, 1000))
        assertTrue(ProjectPreviewRatio.isClamped(500, 4000))
        assertTrue(ProjectPreviewRatio.isClamped(8000, 1000))
        assertTrue("a sizeless project has nothing to match", ProjectPreviewRatio.isClamped(0, 0))
    }
}
