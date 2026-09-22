package dev.herakles.nightjar.incoming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.herakles.nightjar.ImageStegoCarrier
import dev.herakles.nightjar.R
import dev.herakles.nightjar.modules.fireflyjar.FireflyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * [IncomingPipeline.route] coverage (gate-41 safety re-audit, finding F-10): the router
 * ([IncomingRouter]) and its Android adapters ([IncomingAndroidAdapters]) were both already well
 * tested, but nothing exercised [IncomingPipeline] itself -- the thin Android-facing entry point
 * that wraps them, and the one place findings F-1 through F-9 all actually land (the [Throwable]
 * boundary, the content:// scheme check, [IncomingOutcome.TooLarge]/[IncomingOutcome.OutOfSpace],
 * and the real [dev.herakles.nightjar.modules.fireflyjar.FireflyRepository] persist path).
 *
 * Uses [FireflyDatabase.getInstance] directly -- the real singleton [route] itself builds its
 * repository from -- rather than [androidx.room.Room.inMemoryDatabaseBuilder]
 * ([dev.herakles.nightjar.modules.fireflyjar.FireflyRepositoryTest]'s own precedent), since
 * [IncomingPipeline.persistCaught] isn't wired for database injection. Safe under
 * [RobolectricTestRunner]'s default per-test-method sandbox isolation, which resets the
 * `@Volatile` companion-object instance between test methods the same way it resets every other
 * static in this app between tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IncomingPipelineTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var nextUriId = 0

    @Before
    fun setUp() {
        File(context.filesDir, "fireflies").deleteRecursively()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "fireflies").deleteRecursively()
    }

    /** A fresh `content://` `Uri` per call -- [Shadows.shadowOf]'s registration is per-`Uri`, and
     *  several tests in this class register more than one stream in the same run. */
    private fun contentUri(): Uri = Uri.parse("content://incoming-pipeline-test/${nextUriId++}")

    private fun registerBytes(bytes: ByteArray): Uri {
        val uri = contentUri()
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    private fun registerStream(stream: InputStream): Uri {
        val uri = contentUri()
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, stream)
        return uri
    }

    // --- finding F-9: content:// scheme scoping ---

    @Test
    fun `a file scheme Uri is refused without ever being opened`() = runBlocking {
        val uri = Uri.parse("file:///data/data/dev.herakles.nightjar/files/some-file")

        val outcome = IncomingPipeline.route(context, uri, action = "SEND")

        assertTrue("expected Unsupported but got $outcome", outcome is IncomingOutcome.Unsupported)
    }

    // --- finding F-3: TooLarge is distinct from a generic read failure ---

    @Test
    fun `a stream over the 50MB bound resolves to TooLarge`() = runBlocking {
        // Lazily produced: never actually materializes 50MB+ of real bytes for this test.
        val limit = 51L * 1024 * 1024
        val hugeStream = object : InputStream() {
            var produced = 0L
            override fun read(): Int = error("bulk read only")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (produced >= limit) return -1
                val n = minOf(len.toLong(), limit - produced).toInt()
                produced += n
                return n
            }
        }
        val uri = registerStream(hugeStream)

        val outcome = IncomingPipeline.route(context, uri, action = "SEND")

        assertEquals(IncomingOutcome.TooLarge, outcome)
    }

    // --- finding F-2: the Throwable boundary ---

    @Test
    fun `a ContentProvider that throws IllegalStateException resolves to Unsupported, not a crash`() = runBlocking {
        // IllegalStateException specifically: pre-F-2, readBoundedBytes only caught IOException
        // and SecurityException, so this exact type would have propagated uncaught.
        val throwingStream = object : InputStream() {
            override fun read(): Int = throw IllegalStateException("simulated hostile provider")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IllegalStateException("simulated hostile provider")
        }
        val uri = registerStream(throwingStream)

        val outcome = IncomingPipeline.route(context, uri, action = "SEND")

        assertTrue("expected Unsupported but got $outcome", outcome is IncomingOutcome.Unsupported)
    }

    // --- baseline INV-12 coverage through the pipeline, not just the router ---

    @Test
    fun `garbage bytes resolve to Unsupported, not a crash`() = runBlocking {
        val uri = registerBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        val outcome = IncomingPipeline.route(context, uri, action = "SEND")

        assertTrue("expected Unsupported but got $outcome", outcome is IncomingOutcome.Unsupported)
    }

    @Test
    fun `a valid exact-LSB PNG is Caught and persisted as exactly one RECEIVED row`() = runBlocking {
        val cover = checkNotNull(BitmapFactory.decodeResource(context.resources, R.drawable.stego_cover_gradient)) {
            "failed to decode sample cover image resource"
        }
        val payload = "incoming pipeline test payload".toByteArray(Charsets.UTF_8)
        val stego = ImageStegoCarrier(cover).encode(payload)
        val out = ByteArrayOutputStream()
        check(stego.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG compress failed" }
        val uri = registerBytes(out.toByteArray())

        val outcome = IncomingPipeline.route(context, uri, action = "SEND")

        assertTrue("expected Caught but got $outcome", outcome is IncomingOutcome.Caught)
        outcome as IncomingOutcome.Caught
        assertTrue(payload.contentEquals(outcome.payload))

        val rows = FireflyDatabase.getInstance(context).fireflyDao().observeAll().first()
        assertEquals("exactly one row must be written for one Caught firefly", 1, rows.size)
        assertEquals("RECEIVED", rows[0].direction)
        assertEquals(payload.size, rows[0].payloadSizeBytes)
    }
}
