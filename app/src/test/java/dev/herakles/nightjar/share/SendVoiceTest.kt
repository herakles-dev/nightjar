package dev.herakles.nightjar.share

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.herakles.nightjar.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * gate-37/gate-38's voice rule ("jar voice rules hold: lowercase, no exclamation, no emoji"),
 * checked against every string `strings_send.xml` adds (task W2-2) -- same shape
 * [dev.herakles.nightjar.trail.TrailVoiceTest]/
 * [dev.herakles.nightjar.incoming.IncomingOutcomeCopyTest] already established for their own
 * v6 copy additions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SendVoiceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Every plain (no format args) string `strings_send.xml` adds. */
    private val plainSendStringIds = listOf(
        R.string.send_row_send_this_firefly,
        R.string.send_row_hide_in_photo,
        R.string.send_advice_exact,
        R.string.send_advice_sturdy,
        R.string.send_advice_audio,
        R.string.send_not_locked,
        R.string.send_sending_busy_label,
        R.string.send_keep_a_copy,
        R.string.send_kept_a_copy,
        R.string.send_sturdy_carrier_caption,
        R.string.send_back,
        R.string.send_message_label,
        R.string.send_message_hint,
        R.string.send_technique_sturdy,
        R.string.send_technique_exact,
        R.string.send_too_small_to_hide,
        R.string.send_hide_advice_sturdy,
        R.string.send_hide_advice_exact,
        R.string.send_hide_it,
        R.string.send_hiding_busy_label,
        R.string.send_hide_failed,
    )

    /** [R.string.send_refusal_too_small] and [R.string.send_byte_and_size_line] take format
     *  args -- checked here against their raw, UNFORMATTED template text (`Context.getString(id)`
     *  with no args applies no substitution at all), the same authored copy a translator or
     *  reviewer would actually read. Formatting either with a real value (e.g.
     *  [dev.herakles.nightjar.modules.fireflyjar.fireflyMediaSizeLabel]'s own "MB"/"KB" units)
     *  would fold interpolated, non-authored data into this check -- the same reason
     *  [dev.herakles.nightjar.incoming.IncomingOutcomeCopyTest]'s own two-arg `Caught` body only
     *  ever interpolates a value ([dev.herakles.nightjar.picker.Module.jarChannel]) that's itself
     *  guaranteed lowercase. */
    private val formatStringIds = listOf(
        R.string.send_refusal_too_small,
        R.string.send_byte_and_size_line,
    )

    private fun allSendStrings(): List<String> =
        (plainSendStringIds + formatStringIds).map { context.getString(it) }

    @Test
    fun everySendStringIsLowercase() {
        for (text in allSendStrings()) {
            assertEquals("must be lowercase: \"$text\"", text.lowercase(), text)
        }
    }

    @Test
    fun everySendStringHasNoExclamationMark() {
        for (text in allSendStrings()) {
            assertFalse("must not contain '!': \"$text\"", text.contains("!"))
        }
    }

    @Test
    fun everySendStringHasNoEmoji() {
        for (text in allSendStrings()) {
            assertFalse("must not contain an emoji: \"$text\"", containsEmoji(text))
        }
    }

    @Test
    fun noSendStringUsesCatchOrCaughtToDescribeCreating() {
        // Owner direction (2026-09-22): embedding is create/created, never catch/caught. None of
        // this task's own new copy describes an embed action with that verb (pre-existing
        // "catch a firefly" strings elsewhere are out of scope -- a follow-up sweeps those).
        for (text in allSendStrings()) {
            assertFalse("must not use \"catch\": \"$text\"", text.contains("catch"))
            assertFalse("must not use \"caught\": \"$text\"", text.contains("caught"))
        }
    }

    /** Same emoji-range check as [dev.herakles.nightjar.trail.TrailVoiceTest] -- duplicated
     *  rather than shared, matching that file's own note that this project has no shared
     *  test-support source set. */
    private fun containsEmoji(text: String): Boolean =
        text.codePoints().anyMatch { codePoint -> EMOJI_RANGES.any { range -> codePoint in range } }

    private companion object {
        val EMOJI_RANGES: List<IntRange> = listOf(
            0x1F300..0x1FAFF,
            0x2600..0x26FF,
            0x2700..0x27BF,
            0x2B00..0x2BFF,
            0x1F1E6..0x1F1FF,
            0xFE0F..0xFE0F,
            0x200D..0x200D,
        )
    }
}
