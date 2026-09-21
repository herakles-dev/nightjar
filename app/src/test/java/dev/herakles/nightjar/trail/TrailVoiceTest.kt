package dev.herakles.nightjar.trail

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
 * gate-37: "jar voice rules hold (lowercase, no exclamation, no emoji)" -- checked against every
 * string in `strings_trail.xml` (the riddles and fallbacks the task calls out by name, plus the
 * rest of the trail's v6 copy, since gate-38 puts all of it under the same "all v6 copy lives in
 * strings.xml" umbrella and there's no reason the voice rule would apply to some of it and not
 * the rest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrailVoiceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val allTrailStringIds = listOf(
        R.string.trail_riddle_art,
        R.string.trail_gloss_art,
        R.string.trail_riddle_humming,
        R.string.trail_riddle_humming_fallback,
        R.string.trail_gloss_humming,
        R.string.trail_riddle_singing,
        R.string.trail_riddle_singing_fallback,
        R.string.trail_gloss_singing,
        R.string.trail_meadow_honest_fallback,
        R.string.trail_hint_art,
        R.string.trail_hint_humming,
        R.string.trail_hint_singing,
        R.string.trail_hint_meadow,
        R.string.trail_hint_send,
        R.string.trail_hint_workshop,
        R.string.trail_skip,
        R.string.trail_restart,
        R.string.trail_practice_label,
    )

    private val riddleAndFallbackStringIds = listOf(
        R.string.trail_riddle_art,
        R.string.trail_riddle_humming,
        R.string.trail_riddle_humming_fallback,
        R.string.trail_riddle_singing,
        R.string.trail_riddle_singing_fallback,
    )

    @Test
    fun everyTrailStringIsLowercase() {
        for (id in allTrailStringIds) {
            val text = context.getString(id)
            assertEquals("resource $id must be lowercase: \"$text\"", text.lowercase(), text)
        }
    }

    @Test
    fun everyTrailStringHasNoExclamationMark() {
        for (id in allTrailStringIds) {
            val text = context.getString(id)
            assertFalse("resource $id must not contain '!': \"$text\"", text.contains("!"))
        }
    }

    @Test
    fun everyTrailStringHasNoEmoji() {
        for (id in allTrailStringIds) {
            val text = context.getString(id)
            assertFalse("resource $id must not contain an emoji: \"$text\"", containsEmoji(text))
        }
    }

    @Test
    fun riddleStringsSpecificallyHoldAllThreeVoiceRules() {
        for (id in riddleAndFallbackStringIds) {
            val text = context.getString(id)
            assertEquals("riddle $id must be lowercase: \"$text\"", text.lowercase(), text)
            assertFalse("riddle $id must not contain '!': \"$text\"", text.contains("!"))
            assertFalse("riddle $id must not contain an emoji: \"$text\"", containsEmoji(text))
        }
    }

    /** True if [text] contains a code point from a common emoji/pictograph/symbol Unicode block.
     *  Deliberately does not flag ordinary punctuation like an em dash (U+2014, used in
     *  [R.string.trail_meadow_honest_fallback]) or curly quotes -- only ranges that are
     *  emoji-or-pictograph-specific. */
    private fun containsEmoji(text: String): Boolean =
        text.codePoints().anyMatch { codePoint ->
            EMOJI_RANGES.any { range -> codePoint in range }
        }

    private companion object {
        val EMOJI_RANGES: List<IntRange> = listOf(
            0x1F300..0x1FAFF, // misc symbols & pictographs, emoticons, transport, supplemental symbols
            0x2600..0x26FF, // misc symbols (sun, umbrella, etc.)
            0x2700..0x27BF, // dingbats
            0x2B00..0x2BFF, // misc symbols and arrows (stars, etc.)
            0x1F1E6..0x1F1FF, // regional indicator symbols (flag emoji)
            0xFE0F..0xFE0F, // variation selector-16 (emoji presentation)
            0x200D..0x200D, // zero-width joiner (emoji sequences)
        )
    }
}
