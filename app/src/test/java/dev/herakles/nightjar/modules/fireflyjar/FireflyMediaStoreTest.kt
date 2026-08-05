package dev.herakles.nightjar.modules.fireflyjar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verification for task #3 (gate-20, INV-5): [FireflyMediaStore], the app-private on-disk store
 * for persisted carrier media. Uses the same Robolectric setup as [FireflyLogTest] so the store
 * runs against a real `Context.filesDir`, not a mock.
 *
 * The `fireflies` directory is cleaned in [setUp]/[tearDown] so reruns are deterministic and
 * don't accumulate stray files across test invocations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FireflyMediaStoreTest {

    private lateinit var context: Context
    private lateinit var store: FireflyMediaStore
    private lateinit var directory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        directory = File(context.filesDir, "fireflies")
        directory.deleteRecursively()
        store = FireflyMediaStore(context)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun writeThenReadRoundTripsExactBytesAndReturnsBareFilename() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        val filename = store.write(bytes, "bin")

        assertFalse("filename must not contain a path separator", filename.contains(File.separator))
        assertFalse("filename must not contain a forward slash", filename.contains("/"))

        val readBack = store.read(filename)
        assertArrayEquals(bytes, readBack)
    }

    @Test
    fun twoWritesProduceDistinctFilenamesAndBothRemainReadable() {
        val firstBytes = byteArrayOf(10, 20, 30)
        val secondBytes = byteArrayOf(40, 50, 60, 70)

        val firstFilename = store.write(firstBytes, "png")
        val secondFilename = store.write(secondBytes, "wav")

        assertNotEquals(firstFilename, secondFilename)
        assertArrayEquals(firstBytes, store.read(firstFilename))
        assertArrayEquals(secondBytes, store.read(secondFilename))
    }

    @Test
    fun deleteRemovesOneFileAndLeavesTheOtherReadable() {
        val keepBytes = byteArrayOf(9, 9, 9)
        val keepFilename = store.write(keepBytes, "png")
        val doomedFilename = store.write(byteArrayOf(1, 1, 1), "png")

        store.delete(doomedFilename)

        assertNull(store.read(doomedFilename))
        assertArrayEquals(keepBytes, store.read(keepFilename))
    }

    @Test
    fun deleteOfMissingFileIsANoOp() {
        // Should not throw for a filename that was never written.
        store.delete("never-written.png")
    }

    @Test
    fun deleteAllLeavesTheDirectoryEmpty() {
        store.write(byteArrayOf(1), "png")
        store.write(byteArrayOf(2), "wav")

        store.deleteAll()

        assertTrue(directory.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun sweepOrphansDeletesStrayFilesAndKeepsKnownFiles() {
        val knownFilename = store.write(byteArrayOf(7, 7), "png")
        // Hand-plant a stray file the store never wrote via write() -- simulates a crash between
        // a media write and its owning FireflyRecord insert.
        val strayFile = File(directory, "orphan-stray.png")
        strayFile.writeBytes(byteArrayOf(8, 8))
        assertTrue(strayFile.exists())
        // Backdate it past the age floor: a real crash orphan was stranded by an earlier
        // process, so it is old. A freshly-written stray is deliberately protected instead
        // (see writeInFlightIsNotSweptEvenWhenAbsentFromTheKnownSet).
        strayFile.setLastModified(
            System.currentTimeMillis() - FireflyMediaStore.MIN_ORPHAN_AGE_MILLIS - 60_000L,
        )

        store.sweepOrphans(known = setOf(knownFilename))

        assertFalse("orphan file must be deleted", strayFile.exists())
        assertTrue("known file must be kept", File(directory, knownFilename).exists())
        assertArrayEquals(byteArrayOf(7, 7), store.read(knownFilename))
    }

    @Test
    fun readOfNeverWrittenFilenameReturnsNull() {
        assertNull(store.read("does-not-exist.bin"))
    }

    // --- content-addressed dedup (task #24) ---

    @Test
    fun writingIdenticalBytesTwiceReturnsTheSameNameAndLeavesOneFile() {
        val bytes = byteArrayOf(4, 8, 15, 16, 23, 42)

        val first = store.write(bytes, "wav")
        val second = store.write(bytes, "wav")

        assertEquals("identical carriers must collapse onto one name", first, second)
        assertEquals(1, directory.listFiles()?.size)
        assertArrayEquals("the deduped write must still read back exactly", bytes, store.read(second))
    }

    @Test
    fun writingDifferentBytesReturnsDifferentNamesAndLeavesTwoFiles() {
        val first = store.write(byteArrayOf(1, 1, 1), "wav")
        val second = store.write(byteArrayOf(2, 2, 2), "wav")

        assertNotEquals(first, second)
        assertEquals(2, directory.listFiles()?.size)
        assertArrayEquals(byteArrayOf(1, 1, 1), store.read(first))
        assertArrayEquals(byteArrayOf(2, 2, 2), store.read(second))
    }

    /**
     * Same bytes, different extension: the digest matches but the names do not, so these stay two
     * files. That is the intended behaviour — the extension is how a reader knows whether to
     * decode PNG or WAV, so collapsing them would produce a record whose media cannot be
     * interpreted. Identical bytes arriving under two extensions is not a real case anyway; a
     * PNG and a WAV of the same byte sequence do not occur.
     */
    @Test
    fun sameBytesUnderDifferentExtensionsStayDistinctFiles() {
        val bytes = byteArrayOf(7, 7, 7, 7)

        val asPng = store.write(bytes, "png")
        val asWav = store.write(bytes, "wav")

        assertNotEquals(asPng, asWav)
        assertEquals(2, directory.listFiles()?.size)
    }

    /**
     * A file sitting under the content-addressed name with the wrong length can only be a
     * truncated leftover from a process that died mid-write. Reusing it would hand back
     * corrupt media that reads "successfully", so the length check must force a rewrite.
     */
    @Test
    fun aTruncatedFileUnderTheContentAddressedNameIsRewritten() {
        val bytes = byteArrayOf(3, 1, 4, 1, 5, 9, 2, 6)
        val filename = store.write(bytes, "wav")
        File(directory, filename).writeBytes(byteArrayOf(3, 1))
        assertEquals(2L, File(directory, filename).length())

        val rewritten = store.write(bytes, "wav")

        assertEquals(filename, rewritten)
        assertArrayEquals("the truncated file must be healed, not reused", bytes, store.read(rewritten))
    }

    /**
     * The start-up sweep reads the database and then lists the directory. A catch completing
     * between those two steps leaves a file on disk that is genuinely absent from the known set
     * — and sweeping it would strand a live row with no carrier and no error anywhere. The age
     * floor is what makes that window unreachable, so this asserts the protection directly
     * rather than trusting the ordering.
     */
    @Test
    fun writeInFlightIsNotSweptEvenWhenAbsentFromTheKnownSet() {
        val justWritten = store.write(byteArrayOf(9, 9, 9), "wav")

        store.sweepOrphans(known = emptySet())

        assertTrue(
            "a file written seconds ago must survive a sweep that has not seen its row yet",
            File(directory, justWritten).exists(),
        )
        assertArrayEquals(byteArrayOf(9, 9, 9), store.read(justWritten))
    }

    /**
     * The directory is resolved and re-ensured on every access rather than cached once per
     * instance, so a store that has already written can survive its directory being removed
     * underneath it. A cached `by lazy` would leave [FireflyMediaStore.write] writing into a
     * missing parent and throwing `FileNotFoundException`.
     */
    @Test
    fun writeStillSucceedsAfterTheDirectoryIsDeletedUnderneathTheStore() {
        val first = store.write(byteArrayOf(1, 2, 3), "png")
        assertArrayEquals(byteArrayOf(1, 2, 3), store.read(first))

        directory.deleteRecursively()
        assertFalse(directory.exists())

        val second = store.write(byteArrayOf(4, 5, 6), "wav")

        assertTrue(directory.exists())
        assertArrayEquals(byteArrayOf(4, 5, 6), store.read(second))
        assertNull("the file removed with the directory is gone, not resurrected", store.read(first))
    }
}
