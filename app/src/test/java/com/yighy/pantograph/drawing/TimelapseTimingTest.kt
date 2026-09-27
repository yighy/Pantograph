package com.yighy.pantograph.drawing

import com.yighy.pantograph.drawing.TimelapseTiming.HOLD_US
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wrong plan shows as a video of the wrong length, one that skips the finished drawing, or
 * one that plays frames out of order - none of which a unit test on a device would be cheaper
 * to catch.
 */
class TimelapseTimingTest {

    private val frameUs = 1_000_000L / 30

    @Test
    fun `original pace is one frame each at 30 fps`() {
        val plan = TimelapseTiming.plan(frameCount = 90, targetSeconds = null)
        assertEquals((0 until 90).toList(), plan.dropLast(1).map { it.frame })
        assertEquals(89 * frameUs, plan[89].timeUs)
    }

    @Test
    fun `too many frames for the length are sampled evenly, ends kept`() {
        val plan = TimelapseTiming.plan(frameCount = 1000, targetSeconds = 15).dropLast(1)
        assertEquals(15 * 30, plan.size)
        assertEquals(0, plan.first().frame)
        assertEquals(999, plan.last().frame)
        assertTrue(plan.zipWithNext().all { (a, b) -> b.frame >= a.frame })
    }

    @Test
    fun `too few frames are each shown longer, to fill the length`() {
        val plan = TimelapseTiming.plan(frameCount = 10, targetSeconds = 30).dropLast(1)
        assertEquals((0 until 10).toList(), plan.map { it.frame })
        assertEquals(3_000_000L, plan[1].timeUs - plan[0].timeUs)
        assertEquals(27_000_000L, plan.last().timeUs)
    }

    @Test
    fun `the finished drawing is held before the end`() {
        val plan = TimelapseTiming.plan(frameCount = 50, targetSeconds = null)
        val last = plan.last()
        val before = plan[plan.size - 2]
        assertEquals(49, last.frame)
        assertEquals(49, before.frame)
        assertEquals(HOLD_US, last.timeUs - before.timeUs)
    }

    @Test
    fun `times only ever go forward`() {
        listOf(null, 15, 30).forEach { target ->
            listOf(1, 7, 449, 450, 451, 2000).forEach { n ->
                val plan = TimelapseTiming.plan(n, target)
                assertTrue("$n frames, target $target", plan.zipWithNext().all { (a, b) -> b.timeUs > a.timeUs })
            }
        }
    }

    @Test
    fun `a single frame still makes a video`() {
        val plan = TimelapseTiming.plan(frameCount = 1, targetSeconds = 15)
        assertEquals(listOf(0, 0), plan.map { it.frame })
        assertEquals(HOLD_US, TimelapseTiming.durationUs(plan))
    }

    @Test
    fun `nothing recorded, nothing planned`() {
        assertTrue(TimelapseTiming.plan(0, 15).isEmpty())
    }
}
