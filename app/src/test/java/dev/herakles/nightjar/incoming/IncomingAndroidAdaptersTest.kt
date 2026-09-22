package dev.herakles.nightjar.incoming

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * [IncomingAndroidAdapters] coverage (spec.md v6 receive-plumbing task W1-2 build notes: "reject
 * > 50 MB", "bound image decode with inJustDecodeBounds and refuse exact-LSB attempts above ~24
 * MP", "never crash on malformed input"). [IncomingAndroidAdapters.decodeBoundedBitmap] needs
 * `BitmapFactory`, hence Robolectric; [IncomingAndroidAdapters.exceedsPixelLimit] is plain
 * arithmetic and would pass without it too.
 *
 * The malformed-input cases below assert "does not throw" rather than "returns `Bounded.Failed`":
 * Robolectric 4.13's `BitmapFactory` shadow doesn't faithfully reproduce real Android's
 * null-for-unparseable-bytes contract for every shape of bad input (confirmed empirically -- it
 * can synthesize a placeholder `Bitmap` for a few garbage bytes, or throw for a truncated PNG
 * header, where real Android returns `null`/`outWidth == -1` either way). What this app's own
 * code actually controls (and [decodeBoundedBitmap]'s try/catch exists specifically for) is never
 * propagating that as a crash -- these tests hold the line on that contract without depending on
 * the shadow's fidelity.
 *
 * gate-41 safety re-audit, finding F-3: [decodeBoundedBitmap] and [readBoundedBytes] return
 * [IncomingAndroidAdapters.Bounded] rather than a plain nullable, so "too large" is distinct from
 * "couldn't decode at all" -- both are covered below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IncomingAndroidAdaptersTest {

    @Test
    fun `garbage bytes never crash decodeBoundedBitmap`() {
        IncomingAndroidAdapters.decodeBoundedBitmap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
    }

    @Test
    fun `a truncated PNG (magic only, no IHDR) never crashes decodeBoundedBitmap`() {
        IncomingAndroidAdapters.decodeBoundedBitmap(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        )
    }

    @Test
    fun `an empty byte array never crashes decodeBoundedBitmap`() {
        IncomingAndroidAdapters.decodeBoundedBitmap(ByteArray(0))
    }

    @Test
    fun `exceedsPixelLimit is false at and under the 24 MP cap`() {
        assertFalse(IncomingAndroidAdapters.exceedsPixelLimit(6000, 4000)) // exactly 24,000,000
        assertFalse(IncomingAndroidAdapters.exceedsPixelLimit(100, 100))
    }

    @Test
    fun `exceedsPixelLimit is true just over the 24 MP cap`() {
        assertTrue(IncomingAndroidAdapters.exceedsPixelLimit(6001, 4000))
    }

    @Test
    fun `exceedsPixelLimit never overflows to a false negative for very large dimensions`() {
        // width * height alone would overflow Int (and wrap, possibly negative) without the
        // Long promotion exceedsPixelLimit uses internally.
        assertTrue(IncomingAndroidAdapters.exceedsPixelLimit(100_000, 100_000))
    }

    @Test
    fun `readBoundedBytes reports TooLarge distinctly from a read failure`() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        val uri = android.net.Uri.parse("content://incoming-adapters-test/too-large")
        Shadows.shadowOf(context.contentResolver).registerInputStream(
            uri,
            java.io.ByteArrayInputStream(ByteArray(2)),
        )
        val result = IncomingAndroidAdapters.readBoundedBytes(context.contentResolver, uri, maxBytes = 1)
        assertTrue(result is IncomingAndroidAdapters.Bounded.TooLarge)
    }

    @Test
    fun `readBoundedBytes reports Ok for bytes at or under the bound`() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        val uri = android.net.Uri.parse("content://incoming-adapters-test/ok")
        val bytes = byteArrayOf(1, 2, 3)
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, java.io.ByteArrayInputStream(bytes))
        val result = IncomingAndroidAdapters.readBoundedBytes(context.contentResolver, uri, maxBytes = 3)
        assertTrue(result is IncomingAndroidAdapters.Bounded.Ok)
        assertArrayEquals(bytes, (result as IncomingAndroidAdapters.Bounded.Ok).value)
    }
}
