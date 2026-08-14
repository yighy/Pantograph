package com.yighy.paintcursor.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Getting this wrong in the permissive direction deletes someone's projects on an update, so
 * every uncertain case has to resolve towards keeping the data.
 */
class SchemaPolicyTest {

    @Test
    fun `pre-1_0 builds may still wipe on a missing migration`() {
        assertTrue(SchemaPolicy.allowsDestructiveFallback("0.1.0"))
        assertTrue(SchemaPolicy.allowsDestructiveFallback("0.4.0"))
        assertTrue(SchemaPolicy.allowsDestructiveFallback("0.99.99"))
    }

    @Test
    fun `1_0 and later require a real migration`() {
        assertFalse(SchemaPolicy.allowsDestructiveFallback("1.0.0"))
        assertFalse(SchemaPolicy.allowsDestructiveFallback("1.0.0-rc1"))
        assertFalse(SchemaPolicy.allowsDestructiveFallback("2.3.1"))
        assertFalse(SchemaPolicy.allowsDestructiveFallback("10.0.0"))
    }

    @Test
    fun `a malformed version is treated as shipped`() {
        // Losing the fallback on a typo costs a crash report; keeping it costs the user's work.
        assertFalse(SchemaPolicy.allowsDestructiveFallback(null))
        assertFalse(SchemaPolicy.allowsDestructiveFallback(""))
        assertFalse(SchemaPolicy.allowsDestructiveFallback("dev"))
        assertFalse(SchemaPolicy.allowsDestructiveFallback("v1.0.0"))
    }

    @Test
    fun `stray whitespace and trailing dots still parse`() {
        // The project's own versionName has carried a trailing dot, so this is not academic.
        assertTrue(SchemaPolicy.allowsDestructiveFallback("0.4.0."))
        assertTrue(SchemaPolicy.allowsDestructiveFallback("  0.4.0  "))
        assertFalse(SchemaPolicy.allowsDestructiveFallback("1.0.0."))
    }
}
