package dev.herakles.nightjar.modules.fireflyjar

import dev.herakles.nightjar.picker.Module
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 follow-up (owner report, on-device): the firefly-detail "channel" meta-tile clipped
 * [Module.AUDIO_STEGANOGRAPHY]'s value ("a recording", 11 characters) against
 * [MetaCard]'s own clip boundary. [jarChannelDisplayLabel] is the display-only shortening;
 * [Module.jarChannel] itself is untouched (outside this task's edit scope, and still correct
 * data on its own terms).
 */
class JarChannelDisplayLabelTest {

    @Test
    fun `shortens the one value that overflowed the meta tile`() {
        assertEquals("recording", jarChannelDisplayLabel("a recording"))
    }

    @Test
    fun `leaves every other module's channel value unchanged`() {
        assertEquals("sound", jarChannelDisplayLabel("sound"))
        assertEquals("a picture", jarChannelDisplayLabel("a picture"))
        assertEquals("the air", jarChannelDisplayLabel("the air"))
    }

    @Test
    fun `every Module's own jarChannel resolves to a tile-safe display label`() {
        // Regression guard for the actual clipping bug: "a picture" (9 chars) was the longest
        // value that already fit the tile: any display label longer than that risks clipping
        // again the way "a recording" (11 chars) did.
        val longestKnownSafeLength = "a picture".length
        for (module in Module.entries) {
            val display = jarChannelDisplayLabel(module.jarChannel)
            assertTrue(
                "${module.name}'s display channel \"$display\" is longer than the tile has shown to fit",
                display.length <= longestKnownSafeLength,
            )
        }
    }
}
