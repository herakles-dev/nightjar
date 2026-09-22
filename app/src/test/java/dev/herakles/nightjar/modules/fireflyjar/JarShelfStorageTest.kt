package dev.herakles.nightjar.modules.fireflyjar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM verification of the shelf's storage-usage readout: the
 * copy below [STORAGE_WARNING_THRESHOLD_BYTES] ("your jars are holding X"), the advisory copy at
 * or above it ("the jars are getting heavy — X"), and the empty-jar case rendering "0 bytes"
 * rather than "null" -- the acceptance criterion's own verify step.
 */
class JarShelfStorageTest {

    @Test
    fun `empty jar renders zero bytes, never null`() {
        assertEquals("your jars are holding 0 bytes", jarStorageUsageLabel(0L))
    }

    @Test
    fun `usage below the threshold uses the plain copy`() {
        // fireflyMediaSizeLabel (JarDetailScreen.kt, reused here on purpose -- see
        // jarStorageUsageLabel's KDoc) always renders MB to one decimal, so 42 MB exactly reads
        // "42.0 MB" -- matching FireflyCapacityLineTest's own established format ("1.2 MB" etc.),
        // not a rounded whole number.
        assertEquals("your jars are holding 42.0 MB", jarStorageUsageLabel(42_000_000L))
        assertFalse(jarStorageUsageIsWarning(42_000_000L))
    }

    @Test
    fun `usage above the threshold uses warmer advisory copy`() {
        assertEquals("the jars are getting heavy — 280.0 MB", jarStorageUsageLabel(280_000_000L))
        assertTrue(jarStorageUsageIsWarning(280_000_000L))
    }

    @Test
    fun `boundary just under the threshold stays the plain copy`() {
        val justUnder = STORAGE_WARNING_THRESHOLD_BYTES - 1
        assertFalse(jarStorageUsageIsWarning(justUnder))
        assertTrue(jarStorageUsageLabel(justUnder).startsWith("your jars are holding"))
    }

    @Test
    fun `boundary exactly at the threshold switches to the advisory copy`() {
        assertTrue(jarStorageUsageIsWarning(STORAGE_WARNING_THRESHOLD_BYTES))
        assertTrue(jarStorageUsageLabel(STORAGE_WARNING_THRESHOLD_BYTES).startsWith("the jars are getting heavy"))
    }

    @Test
    fun `crossing the threshold never gates or blocks -- it is a pure formatter`() {
        // No exception, no side effect -- just a string, called at an arbitrary huge value to
        // confirm there's no upper cap/crash either (advisory-only, no eviction).
        val label = jarStorageUsageLabel(50_000_000_000L)
        assertTrue(label.startsWith("the jars are getting heavy"))
    }
}
