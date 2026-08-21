package com.yighy.pantograph.drawing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pin list is persisted, so a parsing slip doesn't just misbehave once - it silently
 * wipes someone's configured satellite on the next launch.
 */
class PinnableToolTest {

    // ---- persistence round-trip ----

    @Test
    fun `names survive a round trip`() {
        val tools = listOf(PinnableTool.Fill, PinnableTool.Wand)
        assertEquals(tools, PinnableTool.fromNames(PinnableTool.toNames(tools)))
    }

    @Test
    fun `an empty list is stored as nothing rather than an empty string`() {
        assertNull(PinnableTool.toNames(emptyList()))
        assertEquals(emptyList<PinnableTool>(), PinnableTool.fromNames(null))
    }

    @Test
    fun `a value written by the single-tool build still reads`() {
        // The old build stored one bare name under the same key.
        assertEquals(listOf(PinnableTool.Lasso), PinnableTool.fromNames("Lasso"))
    }

    @Test
    fun `unknown names are dropped instead of throwing`() {
        // A tool removed in a later version must not take the whole list down with it.
        assertEquals(
            listOf(PinnableTool.Fill, PinnableTool.Lazy),
            PinnableTool.fromNames("Fill,Removed,Lazy")
        )
        assertEquals(emptyList<PinnableTool>(), PinnableTool.fromNames("Nonsense"))
    }

    @Test
    fun `whitespace and duplicates in stored names are tolerated`() {
        assertEquals(
            listOf(PinnableTool.Fill, PinnableTool.Rect),
            PinnableTool.fromNames(" Fill , Rect ,Fill")
        )
    }

    @Test
    fun `a stored list longer than the ceiling is truncated`() {
        val overflowing = PinnableTool.entries.joinToString(",") { it.name }
        assertEquals(PinnableTool.MAX_PINNED, PinnableTool.fromNames(overflowing).size)
    }

    // ---- pin toggling ----

    @Test
    fun `pinning appends and unpinning removes`() {
        val one = PinnableTool.togglePin(emptyList(), PinnableTool.Fill)
        assertEquals(listOf(PinnableTool.Fill), one)
        assertEquals(emptyList<PinnableTool>(), PinnableTool.togglePin(one, PinnableTool.Fill))
    }

    @Test
    fun `order is preserved so the first pinned tool stays the one a tap fires`() {
        var pins = emptyList<PinnableTool>()
        listOf(PinnableTool.Fill, PinnableTool.Lazy, PinnableTool.Wand).forEach {
            pins = PinnableTool.togglePin(pins, it)
        }
        assertEquals(PinnableTool.Fill, pins.first())
    }

    @Test
    fun `pinning a fifth tool drops the oldest rather than silently refusing`() {
        var pins = emptyList<PinnableTool>()
        listOf(
            PinnableTool.Fill, PinnableTool.Gradient, PinnableTool.Lazy, PinnableTool.Lasso
        ).forEach { pins = PinnableTool.togglePin(pins, it) }
        assertEquals(PinnableTool.MAX_PINNED, pins.size)

        pins = PinnableTool.togglePin(pins, PinnableTool.Wand)
        assertEquals(PinnableTool.MAX_PINNED, pins.size)
        assertTrue("the new pin must be there", pins.contains(PinnableTool.Wand))
        assertTrue("the oldest should have gone", !pins.contains(PinnableTool.Fill))
    }

    @Test
    fun `unpinning at the ceiling makes room again`() {
        var pins = PinnableTool.entries.take(PinnableTool.MAX_PINNED)
        pins = PinnableTool.togglePin(pins, pins[1])
        assertEquals(PinnableTool.MAX_PINNED - 1, pins.size)
    }
}
