package dev.herakles.nightjar.trail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.herakles.nightjar.NightjarAcoustics
import dev.herakles.nightjar.R
import dev.herakles.nightjar.WavFile
import dev.herakles.nightjar.modules.audiostego.AudioSampleCover
import dev.herakles.nightjar.modules.audiostego.synthesizeSampleCover
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android adapter around [PracticeFireflyGenerator]: resolves the bundled cover/riddle
 * `R.drawable`/`R.string` resources, calls the pure-JVM generator, and writes each result to
 * `filesDir/practice/` -- "a real carrier ... produced once by the
 * production encoder ... the moment the app is installed or the trail is restarted."
 *
 * These files are working carriers, analogous to the bundled sample cover images/clips each jar
 * screen already offers -- NOT `FireflyRecord` rows. A row only exists once the trail's UI
 * runs that jar's real "look for fireflies" decode against the file this object wrote, exactly
 * like catching any other firefly.
 */
object PracticeFireflies {

    /** One practice-carrier file per creating jar, under `filesDir/practice/`. */
    enum class Jar(val fileName: String) {
        ART("art.png"),
        HUMMING("humming.wav"),
        SINGING("singing.wav"),
    }

    /** Cover art uses to embed the art riddle -- either bundled sample image works (both
     *  comfortably exceed the riddle's ~142-byte size); gradient is the first-listed option in
     *  [dev.herakles.nightjar.modules.imagestego.SampleCover]. Not a `const val`: Android
     *  resource-id fields aren't guaranteed compile-time constants to the Kotlin compiler. */
    private val ART_COVER_RES_ID = R.drawable.stego_cover_gradient

    /** Cover the humming riddle is embedded into -- the same SPOKEN_WORD clip
     *  [dev.herakles.nightjar.modules.audiostego.AudioStegoScreenTest] measures spectrogram-LSB
     *  capacity against (457 bytes at default strength), per the
     *  "highest-capacity of the three on the bundled 5s covers" reasoning. */
    private val HUMMING_COVER = AudioSampleCover.SPOKEN_WORD

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var ensureJob: Job? = null

    /**
     * MainActivity.onCreate's one-line hook (task scope: "at most a one-line hook ... idempotent,
     * background dispatcher"). Fire-and-forget on [Dispatchers.IO]: generates the three practice
     * carrier files only if any is missing (fresh install, or an install that never finished
     * generating them). A second call while a generation is already in flight, or once all three
     * files already exist, is a cheap no-op rather than a duplicate encode.
     */
    fun ensureGenerated(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (ensureJob?.isActive == true) return
            ensureJob = backgroundScope.launch {
                if (!allFilesExist(appContext)) {
                    generate(appContext)
                }
            }
        }
    }

    /**
     * Regenerates and overwrites all three practice carrier files unconditionally. Called by
     * [ensureGenerated] when any file is missing, and by [TrailStateStore.restart] on "start the
     * trail again" ("re-seeds one fresh practice firefly per creating jar").
     */
    suspend fun generate(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dir = practiceDirectory(appContext)
        dir.mkdirs()

        val artCover = checkNotNull(BitmapFactory.decodeResource(appContext.resources, ART_COVER_RES_ID)) {
            "failed to decode art practice cover resource $ART_COVER_RES_ID"
        }
        val artRiddle = appContext.getString(R.string.trail_riddle_art)
        val artStego = PracticeFireflyGenerator.generateArt(artCover, artRiddle)
        writeFile(dir, Jar.ART, pngBytes(artStego))

        val hummingRiddles = PracticeFireflyGenerator.Riddles(
            primary = appContext.getString(R.string.trail_riddle_humming),
            fallback = appContext.getString(R.string.trail_riddle_humming_fallback),
        )
        val hummingCover = synthesizeSampleCover(HUMMING_COVER)
        val hummingResult = PracticeFireflyGenerator.generateHumming(hummingCover, hummingRiddles)
        writeFile(dir, Jar.HUMMING, WavFile.encodePcm16Mono(hummingResult.carrier, NightjarAcoustics.SAMPLE_RATE_HZ))

        val singingRiddles = PracticeFireflyGenerator.Riddles(
            primary = appContext.getString(R.string.trail_riddle_singing),
            fallback = appContext.getString(R.string.trail_riddle_singing_fallback),
        )
        val singingResult = PracticeFireflyGenerator.generateSinging(singingRiddles)
        writeFile(dir, Jar.SINGING, WavFile.encodePcm16Mono(singingResult.carrier, NightjarAcoustics.SAMPLE_RATE_HZ))
    }

    /** Where [jar]'s practice carrier lives on disk -- the trail's UI loads this as that jar's
     *  working carrier for the duration of its trail step. May not exist yet if [generate] hasn't run
     *  (or is still running); callers should check [File.exists]. */
    fun practiceFile(context: Context, jar: Jar): File = File(practiceDirectory(context.applicationContext), jar.fileName)

    private fun practiceDirectory(context: Context): File = File(context.filesDir, "practice")

    private fun allFilesExist(context: Context): Boolean =
        Jar.entries.all { practiceFile(context, it).exists() }

    private fun writeFile(dir: File, jar: Jar, bytes: ByteArray) {
        File(dir, jar.fileName).writeBytes(bytes)
    }

    private fun pngBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, stream)
        return stream.toByteArray()
    }

    /** PNG is lossless regardless of this value (ignored by the codec) -- kept at the
     *  conventional max for clarity, matching [Bitmap.compress]'s own documented PNG behavior. */
    private const val PNG_QUALITY = 100
}
