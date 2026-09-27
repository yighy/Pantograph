package com.yighy.pantograph.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wrong answer here either nags about a release that is not newer, or stays quiet about one
 * that is - both of which teach the user to ignore the notice.
 */
class ReleaseVersionTest {

    @Test
    fun `tags read with or without the v`() {
        assertEquals(listOf(0, 18, 0), ReleaseVersion.parse("v0.18.0"))
        assertEquals(listOf(0, 18, 0), ReleaseVersion.parse("0.18.0"))
    }

    @Test
    fun `a suffix is ignored`() {
        assertEquals(listOf(1, 0, 0), ReleaseVersion.parse("v1.0.0-beta"))
    }

    @Test
    fun `anything that is not a version reads as nothing`() {
        assertNull(ReleaseVersion.parse(""))
        assertNull(ReleaseVersion.parse("latest"))
        assertNull(ReleaseVersion.parse("v1..2"))
        assertNull(ReleaseVersion.parse("v1.2/../../x"))
    }

    @Test
    fun `numbers compare as numbers, not as text`() {
        assertTrue(ReleaseVersion.isNewer("v0.18.0", "0.9.1"))
        assertFalse(ReleaseVersion.isNewer("v0.9.1", "0.18.0"))
    }

    @Test
    fun `each part outranks the ones after it`() {
        assertTrue(ReleaseVersion.isNewer("v0.19.0", "0.18.5"))
        assertTrue(ReleaseVersion.isNewer("v1.0.0", "0.99.99"))
        assertTrue(ReleaseVersion.isNewer("v0.18.1", "0.18.0"))
    }

    @Test
    fun `the same release is not newer`() {
        assertFalse(ReleaseVersion.isNewer("v0.18.0", "0.18.0"))
        assertFalse(ReleaseVersion.isNewer("v0.19", "0.19.0"))
    }

    @Test
    fun `an unreadable version is never newer`() {
        assertFalse(ReleaseVersion.isNewer("nightly", "0.18.0"))
        assertFalse(ReleaseVersion.isNewer("v0.19.0", "dev"))
    }

    @Test
    fun `the page is built from the tag, on this repository`() {
        assertEquals(
            "https://github.com/yighy/Pantograph/releases/tag/v0.19.0",
            ReleaseVersion.pageUrl("v0.19.0")
        )
    }
}
