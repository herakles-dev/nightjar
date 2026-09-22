package dev.herakles.nightjar.share

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.herakles.nightjar.STURDY_MIN_COVER_LONG_SIDE_PX
import dev.herakles.nightjar.incoming.SturdyImageFireflyDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [outgoingKindFor]'s technique -> [OutgoingKind] mapping,
 * [sendAdviceStringRes]'s per-kind advice, and [sturdyRefusalMessage]'s jar-voice refusal copy
 * for a too-small photo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SendAdviceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ==========================================================================================
    // outgoingKindFor
    // ==========================================================================================

    @Test
    fun `AUDIO carrier kind maps to AUDIO regardless of technique`() {
        assertEquals(OutgoingKind.AUDIO, outgoingKindFor("AUDIO", null))
        assertEquals(OutgoingKind.AUDIO, outgoingKindFor("AUDIO", "PHASE_INVERSION"))
        assertEquals(OutgoingKind.AUDIO, outgoingKindFor("AUDIO", "SPECTROGRAM_LSB"))
        assertEquals(OutgoingKind.AUDIO, outgoingKindFor("AUDIO", "MFSK"))
    }

    @Test
    fun `IMAGE carrier with the sturdy technique maps to IMAGE_STURDY`() {
        assertEquals(
            OutgoingKind.IMAGE_STURDY,
            outgoingKindFor("IMAGE", SturdyImageFireflyDecoder.STURDY_TECHNIQUE),
        )
    }

    @Test
    fun `IMAGE carrier with a null or non-sturdy technique maps to IMAGE_EXACT`() {
        assertEquals(OutgoingKind.IMAGE_EXACT, outgoingKindFor("IMAGE", null))
        assertEquals(OutgoingKind.IMAGE_EXACT, outgoingKindFor("IMAGE", "something-else"))
    }

    @Test
    fun `null or unrecognized carrier kind maps to null -- nothing to send`() {
        assertNull(outgoingKindFor(null, null))
        assertNull(outgoingKindFor("UNKNOWN", null))
    }

    // ==========================================================================================
    // sendAdviceStringRes -- every kind resolves distinct, non-blank, jar-voice copy
    // ==========================================================================================

    @Test
    fun `every OutgoingKind resolves to non-blank advice`() {
        for (kind in OutgoingKind.entries) {
            val text = context.getString(sendAdviceStringRes(kind))
            assertTrue("$kind advice must not be blank", text.isNotBlank())
        }
    }

    @Test
    fun `the three kinds resolve to distinct advice text`() {
        val texts = OutgoingKind.entries.map { context.getString(sendAdviceStringRes(it)) }
        assertEquals(OutgoingKind.entries.size, texts.toSet().size)
    }

    @Test
    fun `exact advice names the file-or-document requirement`() {
        val text = context.getString(sendAdviceStringRes(OutgoingKind.IMAGE_EXACT))
        assertTrue(text.contains("file"))
    }

    @Test
    fun `audio advice explicitly rules out a voice note`() {
        val text = context.getString(sendAdviceStringRes(OutgoingKind.AUDIO))
        assertTrue(text.contains("voice note"))
    }

    // ==========================================================================================
    // sturdyRefusalMessage
    // ==========================================================================================

    @Test
    fun `sturdy refusal names both the photo's long side and the minimum`() {
        val message = sturdyRefusalMessage(context, 480)
        assertTrue(message.contains("480"))
        assertTrue(message.contains(STURDY_MIN_COVER_LONG_SIDE_PX.toString()))
    }

    // ==========================================================================================
    // outgoingMimeAndExtensionFor -- review finding #3 (v6/review-fix): an existing caught
    // firefly's real on-disk extension must win over OutgoingKind.AUDIO's hardcoded WAV default.
    // ==========================================================================================

    @Test
    fun `a modem firefly caught from a compressed m4a voice note shares as audio-m4a, not audio-wav`() {
        val (mimeType, extension) = outgoingMimeAndExtensionFor(OutgoingKind.AUDIO, "a1b2c3.m4a")
        assertEquals("audio/mp4", mimeType)
        assertEquals("m4a", extension)
    }

    @Test
    fun `every recognized compressed audio extension resolves to its own real MIME type`() {
        assertEquals("audio/mpeg" to "mp3", outgoingMimeAndExtensionFor(OutgoingKind.AUDIO, "hash.mp3"))
        assertEquals("audio/ogg" to "ogg", outgoingMimeAndExtensionFor(OutgoingKind.AUDIO, "hash.ogg"))
        assertEquals("audio/opus" to "opus", outgoingMimeAndExtensionFor(OutgoingKind.AUDIO, "hash.opus"))
        assertEquals("audio/amr" to "amr", outgoingMimeAndExtensionFor(OutgoingKind.AUDIO, "hash.amr"))
    }

    @Test
    fun `a genuinely-wav audio firefly still resolves to audio-wav`() {
        val (mimeType, extension) = outgoingMimeAndExtensionFor(OutgoingKind.AUDIO, "hash.wav")
        assertEquals("audio/wav", mimeType)
        assertEquals("wav", extension)
    }

    @Test
    fun `an unrecognized or missing extension falls back to OutgoingKind AUDIO's own wav default`() {
        assertEquals(OutgoingKind.AUDIO.mimeType to OutgoingKind.AUDIO.fileExtension, outgoingMimeAndExtensionFor(OutgoingKind.AUDIO, null))
        assertEquals(OutgoingKind.AUDIO.mimeType to OutgoingKind.AUDIO.fileExtension, outgoingMimeAndExtensionFor(OutgoingKind.AUDIO, "hash"))
        assertEquals(OutgoingKind.AUDIO.mimeType to OutgoingKind.AUDIO.fileExtension, outgoingMimeAndExtensionFor(OutgoingKind.AUDIO, "hash.xyz"))
    }

    @Test
    fun `extension matching is case-insensitive`() {
        val (mimeType, extension) = outgoingMimeAndExtensionFor(OutgoingKind.AUDIO, "hash.M4A")
        assertEquals("audio/mp4", mimeType)
        assertEquals("m4a", extension)
    }

    @Test
    fun `image kinds are never affected -- their own mimeType and extension pass through unchanged regardless of mediaPath`() {
        assertEquals(
            OutgoingKind.IMAGE_EXACT.mimeType to OutgoingKind.IMAGE_EXACT.fileExtension,
            outgoingMimeAndExtensionFor(OutgoingKind.IMAGE_EXACT, "hash.m4a"),
        )
        assertEquals(
            OutgoingKind.IMAGE_STURDY.mimeType to OutgoingKind.IMAGE_STURDY.fileExtension,
            outgoingMimeAndExtensionFor(OutgoingKind.IMAGE_STURDY, null),
        )
    }
}
