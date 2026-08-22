package com.yighy.pantograph.drawing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug this encodes: opening an A4 project showed it at the zoom computed for the placeholder
 * canvas, and only a manual fit-to-screen corrected it. Nothing crashed and nothing looked
 * broken - the drawing simply started off-screen.
 */
class CanvasFitPolicyTest {

    private fun fit(
        requested: Boolean = false,
        w: Int = 2480, h: Int = 3508,
        lastW: Int = 0, lastH: Int = 0,
        untouched: Boolean = false
    ) = CanvasFitPolicy.shouldFit(requested, w, h, lastW, lastH, untouched)

    @Test
    fun `a canvas never fitted before is fitted`() {
        assertTrue(fit(lastW = 0, lastH = 0))
    }

    @Test
    fun `the real project size replacing the placeholder is fitted again`() {
        // Exactly the reported case: 1080x1920 is the state's default, the project is A4.
        assertTrue(
            "an A4 arriving over the placeholder must re-fit",
            fit(w = 2480, h = 3508, lastW = 1080, lastH = 1920, untouched = false)
        )
    }

    @Test
    fun `a viewport change leaves a framing the user chose alone`() {
        assertFalse(fit(w = 2480, h = 3508, lastW = 2480, lastH = 3508, untouched = false))
    }

    @Test
    fun `a viewport change re-fits a view nobody has framed yet`() {
        assertTrue(fit(w = 2480, h = 3508, lastW = 2480, lastH = 3508, untouched = true))
    }

    @Test
    fun `an explicit request always wins`() {
        assertTrue(fit(requested = true, lastW = 2480, lastH = 3508, untouched = false))
    }

    @Test
    fun `a canvas with no size is never fitted`() {
        for ((w, h) in listOf(0 to 100, 100 to 0, 0 to 0, -5 to 10)) {
            assertFalse("${w}x$h", fit(requested = true, w = w, h = h))
        }
    }
}
