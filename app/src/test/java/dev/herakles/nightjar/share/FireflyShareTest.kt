package dev.herakles.nightjar.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * JVM coverage for `FireflyShare.kt`'s send plumbing (a v6 addition). Exercises only the
 * pure-JVM-testable surface: [outgoingFileName]'s naming policy,
 * [FireflyShare.sweepOutgoing]'s deletion policy, and a static source-scan proving the file
 * never reaches for `MediaStore` or an external-storage API.
 *
 * `prepareOutgoing`/`shareIntent` need a real Android `Context`/`Uri`/`Intent`
 * (`FileProvider.getUriForFile`, `Intent.createChooser`) and are exercised on-device / by the
 * UI-wiring task that calls them, not by this plain-JVM suite -- matching how this codebase
 * already splits `ImageStegoScreen.kt`'s `encodePngBytes` (unit-tested) from
 * `saveBitmapAsPngToMediaStore` (not, absent Robolectric's ContentResolver).
 */
class FireflyShareTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ==========================================================================================
    // outgoingFileName -- naming policy (neutral, disguise-safe, deterministic)
    // ==========================================================================================

    @Test
    fun `naming never contains firefly, nightjar or stego, for any kind`() {
        val now = Instant.parse("2026-03-14T09:41:00Z")
        val forbidden = listOf("firefly", "nightjar", "stego")
        for (kind in OutgoingKind.entries) {
            val name = outgoingFileName(kind, now, ZoneOffset.UTC).lowercase()
            for (word in forbidden) {
                assertFalse("\"$name\" should not contain \"$word\"", name.contains(word))
            }
        }
    }

    @Test
    fun `image exact names as IMG with a png extension`() {
        val now = Instant.parse("2026-03-14T09:41:07Z")
        assertEquals(
            "IMG_20260314_094107.png",
            outgoingFileName(OutgoingKind.IMAGE_EXACT, now, ZoneOffset.UTC),
        )
    }

    @Test
    fun `image sturdy names as IMG with a jpg extension`() {
        val now = Instant.parse("2026-03-14T09:41:07Z")
        assertEquals(
            "IMG_20260314_094107.jpg",
            outgoingFileName(OutgoingKind.IMAGE_STURDY, now, ZoneOffset.UTC),
        )
    }

    @Test
    fun `audio names as AUD with a wav extension`() {
        val now = Instant.parse("2026-03-14T09:41:07Z")
        assertEquals(
            "AUD_20260314_094107.wav",
            outgoingFileName(OutgoingKind.AUDIO, now, ZoneOffset.UTC),
        )
    }

    @Test
    fun `naming is deterministic given the same kind, clock and zone`() {
        val now = Instant.parse("2026-07-01T23:59:59Z")
        val first = outgoingFileName(OutgoingKind.AUDIO, now, ZoneOffset.UTC)
        val second = outgoingFileName(OutgoingKind.AUDIO, now, ZoneOffset.UTC)
        assertEquals(first, second)
    }

    @Test
    fun `naming changes when the clock moves, kind held constant`() {
        val first = outgoingFileName(OutgoingKind.AUDIO, Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC)
        val second = outgoingFileName(OutgoingKind.AUDIO, Instant.parse("2026-07-01T00:00:01Z"), ZoneOffset.UTC)
        assertFalse(first == second)
    }

    // Review finding #3 (v6/review-fix): an overridden extension (an existing caught firefly's
    // own real on-disk suffix -- dev.herakles.nightjar.share.outgoingMimeAndExtensionFor) must
    // win over [OutgoingKind.AUDIO]'s own hardcoded "wav", while the default (no override) keeps
    // every existing caller's naming exactly as it was.
    @Test
    fun `an overridden extension replaces the kind's own default extension`() {
        val now = Instant.parse("2026-03-14T09:41:07Z")
        assertEquals(
            "AUD_20260314_094107.m4a",
            outgoingFileName(OutgoingKind.AUDIO, now, ZoneOffset.UTC, extension = "m4a"),
        )
    }

    @Test
    fun `omitting the extension override still names as the kind's own default extension`() {
        val now = Instant.parse("2026-03-14T09:41:07Z")
        assertEquals(
            outgoingFileName(OutgoingKind.AUDIO, now, ZoneOffset.UTC),
            outgoingFileName(OutgoingKind.AUDIO, now, ZoneOffset.UTC, extension = OutgoingKind.AUDIO.fileExtension),
        )
    }

    // ==========================================================================================
    // FireflyShare.sweepOutgoing -- deletion policy
    // ==========================================================================================

    @Test
    fun `sweep deletes files older than maxAgeMillis`() {
        val dir = tempFolder.newFolder("outgoing")
        val oldFile = File(dir, "IMG_old.png").apply { writeBytes(byteArrayOf(1)) }
        val now = Instant.ofEpochMilli(10_000_000L)
        val maxAgeMillis = 60_000L
        oldFile.setLastModified(now.toEpochMilli() - maxAgeMillis - 1_000L)

        FireflyShare.sweepOutgoing(dir, now, maxAgeMillis)

        assertFalse("stale file should have been swept", oldFile.exists())
    }

    @Test
    fun `sweep keeps files within maxAgeMillis`() {
        val dir = tempFolder.newFolder("outgoing")
        val freshFile = File(dir, "IMG_fresh.png").apply { writeBytes(byteArrayOf(1)) }
        val now = Instant.ofEpochMilli(10_000_000L)
        val maxAgeMillis = 60_000L
        freshFile.setLastModified(now.toEpochMilli() - 1_000L)

        FireflyShare.sweepOutgoing(dir, now, maxAgeMillis)

        assertTrue("fresh file should survive the sweep", freshFile.exists())
    }

    @Test
    fun `sweep never touches files outside the directory it was given`() {
        val outgoing = tempFolder.newFolder("outgoing")
        val sibling = tempFolder.newFolder("sibling")
        val untouchable = File(sibling, "IMG_ancient.png").apply { writeBytes(byteArrayOf(1)) }
        val now = Instant.ofEpochMilli(10_000_000L)
        untouchable.setLastModified(0L) // as old as it gets

        FireflyShare.sweepOutgoing(outgoing, now, maxAgeMillis = 60_000L)

        assertTrue("a file outside the swept dir must never be touched", untouchable.exists())
    }

    @Test
    fun `sweep default max age is one hour`() {
        assertEquals(60 * 60 * 1000L, FireflyShare.DEFAULT_MAX_AGE_MILLIS)
    }

    @Test
    fun `sweep of a missing directory is a silent no-op`() {
        val missing = File(tempFolder.root, "does-not-exist")
        // Must not throw.
        FireflyShare.sweepOutgoing(missing, Instant.now(), maxAgeMillis = 60_000L)
    }

    // ==========================================================================================
    // Static source check -- FireflyShare.kt never reaches for MediaStore or external
    // storage (matches AudioStegDetectorTest.kt's `detectorSourceNeverReferencesTheCarrierOrDecode`
    // precedent for a static, grep-backed source-scan test).
    // ==========================================================================================

    @Test
    fun `FireflyShare source never references MediaStore or external storage APIs`() {
        val source = fireflyShareSourceFile()
        assertTrue("FireflyShare.kt should exist at $source", source.exists())
        // Strip KDoc/block comments and line comments first: this file's own KDoc talks *about*
        // MediaStore repeatedly (to explain why it's never touched), so scanning
        // raw text including comments would false-positive on its own documentation. Only real
        // code (imports, calls) should trip this assertion.
        val code = stripKotlinComments(source.readText())
        val forbidden = listOf(
            "MediaStore",
            "Environment.getExternalStorageDirectory",
            "getExternalFilesDir",
            "getExternalCacheDir",
            "EXTERNAL_CONTENT_URI",
            "WRITE_EXTERNAL_STORAGE",
            "READ_EXTERNAL_STORAGE",
            "READ_MEDIA_IMAGES",
            "READ_MEDIA_AUDIO",
        )
        for (needle in forbidden) {
            assertFalse(
                "FireflyShare.kt's code (comments excluded) must never reference \"$needle\"",
                code.contains(needle),
            )
        }
    }

    /** Removes block comments (including KDoc) and line comments from [text], so a source-scan
     *  test can assert against real code without tripping on documentation that legitimately
     *  discusses a forbidden API by name (to explain why it's avoided). Sufficient for this
     *  codebase's plain Kotlin source -- no comment-delimiter characters appear inside a string
     *  literal in `FireflyShare.kt`. */
    private fun stripKotlinComments(text: String): String =
        text
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")

    private fun fireflyShareSourceFile(): File {
        val relative = "app/src/main/java/dev/herakles/nightjar/share/FireflyShare.kt"
        val direct = File(relative)
        if (direct.exists()) return direct
        // Gradle unit tests typically run with the module dir (app/) as the working directory;
        // fall back to a module-relative path if the project root was used instead.
        return File("src/main/java/dev/herakles/nightjar/share/FireflyShare.kt")
    }
}
