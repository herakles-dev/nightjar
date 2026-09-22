package dev.herakles.nightjar.picker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * As of v6 (owner direction, 2026-09-21), the detector is shown as "the meadow" and
 * the image-steganography jar as "the art jar", and the meadow sits last so the three creating
 * jars come first. [Module] declaration order is display order for both the jar shelf
 * (`JarShelfScreen`) and the workshop list (`ModulePicker`), so this pins both.
 *
 * Stored fireflies are keyed by [Module.name] (never the ordinal), so the names asserted here
 * must not change either: renaming an enum constant would orphan every record in that jar.
 */
class ModuleShelfOrderTest {

    @Test
    fun `shelf order is singing, art, humming, meadow`() {
        assertEquals(
            listOf("the singing jar", "the art jar", "the humming jar", "the meadow"),
            Module.entries.map { it.jarName },
        )
    }

    @Test
    fun `stored module names are unchanged by the v6 reorder`() {
        assertEquals(
            listOf("ACOUSTIC_MODEM", "IMAGE_STEGANOGRAPHY", "AUDIO_STEGANOGRAPHY", "DETECTOR"),
            Module.entries.map { it.name },
        )
    }
}
