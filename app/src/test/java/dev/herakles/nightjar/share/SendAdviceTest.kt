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
 * Task W2-2 (spec.md gate-33): [outgoingKindFor]'s technique -> [OutgoingKind] mapping,
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
}
