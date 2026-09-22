package dev.herakles.nightjar.modules.imagestego

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.BitmapPixelSurface
import dev.herakles.nightjar.CovertCarrier
import dev.herakles.nightjar.CovertDetector
import dev.herakles.nightjar.DebugProbe
import dev.herakles.nightjar.DecodeFailure
import dev.herakles.nightjar.DecodeResult
import dev.herakles.nightjar.DetectionResult
import dev.herakles.nightjar.ImageSteganalysis
import dev.herakles.nightjar.ImageStegoCarrier
import dev.herakles.nightjar.ModuleId
import dev.herakles.nightjar.R
import dev.herakles.nightjar.SturdyCoverPrep
import dev.herakles.nightjar.SturdyImageCarrier
import dev.herakles.nightjar.encodeSturdyJpeg
import dev.herakles.nightjar.prepareSturdyCover
import dev.herakles.nightjar.toBitmap
import dev.herakles.nightjar.incoming.FileSniffer
import dev.herakles.nightjar.incoming.IncomingOutcome
import dev.herakles.nightjar.incoming.IncomingPipeline
import dev.herakles.nightjar.incoming.SniffedType
import dev.herakles.nightjar.modules.fireflyjar.FireflyRepository
import dev.herakles.nightjar.modules.fireflyjar.FireflyRecord
import dev.herakles.nightjar.modules.fireflyjar.HideInPhotoFlow
import dev.herakles.nightjar.modules.fireflyjar.HidePhotoSelection
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.prepareSturdyCover
import dev.herakles.nightjar.trail.PracticeFireflies
import dev.herakles.nightjar.trail.TrailStateStore
import dev.herakles.nightjar.trail.TrailStep
import dev.herakles.nightjar.trail.trailHighlight
import dev.herakles.nightjar.ui.ExpandGlyph
import dev.herakles.nightjar.ui.FullscreenImageViewer
import dev.herakles.nightjar.ui.theme.AccentSignal
import dev.herakles.nightjar.ui.theme.BgBase
import dev.herakles.nightjar.ui.theme.BorderDefault
import dev.herakles.nightjar.ui.theme.FireflyCreated
import dev.herakles.nightjar.ui.theme.FireflyReceived
import dev.herakles.nightjar.ui.theme.JarActionCatchBorder
import dev.herakles.nightjar.ui.theme.JarActionCatchFill
import dev.herakles.nightjar.ui.theme.JarActionCheckBorder
import dev.herakles.nightjar.ui.theme.JarActionCheckFill
import dev.herakles.nightjar.ui.theme.JarActionLookBorder
import dev.herakles.nightjar.ui.theme.JarActionLookFill
import dev.herakles.nightjar.ui.theme.JarTextPrimary
import dev.herakles.nightjar.ui.theme.JarTextSecondary
import dev.herakles.nightjar.ui.theme.JarTextTertiary
import dev.herakles.nightjar.ui.theme.JarTileFill
import dev.herakles.nightjar.ui.theme.JarType
import dev.herakles.nightjar.ui.theme.JarWatchingDim
import dev.herakles.nightjar.ui.theme.TextPrimary
import dev.herakles.nightjar.ui.theme.TextSecondary
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Task #13 — Module 1 (image steganography) real UI, replacing `ModuleStubScreen` for
 * [dev.herakles.nightjar.picker.Module.IMAGE_STEGANOGRAPHY].
 *
 * Written against the [CovertCarrier]/[CovertDetector] interfaces only, the same discipline
 * tasks #7 and #10 used for the modem and detector screens: no reference to the concrete
 * `ImageStegoCarrier` (task #11) or `ImageSteganalysis` (task #12) anywhere in
 * [ImageStegoScreen]/[ImageStegoContent]/[ImageStegoController] below. [jarCatchFlow] (task
 * #12 Firefly Jar) further down this file is the one deliberate exception to that rule — see
 * its own KDoc for why.
 *
 * One deliberate adaptation of that pattern: [CovertCarrier]<Bitmap> is bound to a specific
 * cover image at construction time (`ImageStegoCarrier(coverImage: Bitmap)`'s KDoc — unlike
 * the acoustic modem's stateless carrier). Since this screen lets the operator switch between
 * two bundled cover images, it can't take a single fixed `CovertCarrier<Bitmap>` instance the
 * way `AcousticModemScreen` takes a single `CovertCarrier<PcmAudio>`. It instead takes a
 * factory function `(Bitmap) -> CovertCarrier<Bitmap>` — still zero references to the concrete
 * class, just rebuilding an interface-typed instance per selected cover.
 *
 * Wiring note for whoever lands next: `MainActivity.NightjarApp()`'s `Screen.ModuleStub`
 * branch currently routes `Module.IMAGE_STEGANOGRAPHY` to `ModuleStubScreen`. Swap that one
 * case to call `ImageStegoScreen(carrierFactory = { cover -> ImageStegoCarrier(cover) },
 * detector = ImageSteganalysis(), onBack = ...)` — the same one-line change #7/#10 left
 * behind for the modem/detector screens.
 *
 * Two bundled sample cover images (task #11's `stego_cover_gradient.png` /
 * `stego_cover_mosaic.png`, both 100x100, `res/drawable`) remain as quick-select options, and
 * task #33 adds a third source: the modern Android Photo Picker
 * (`ActivityResultContracts.PickVisualMedia`), so the operator can pick any image already on
 * the device as the cover instead of only the two bundled samples. Photo Picker needs no
 * `READ_MEDIA_IMAGES`/`READ_EXTERNAL_STORAGE` permission — it runs as a separate, system-owned
 * picker UI and hands this app a short-lived, read-only grant on just the one Uri the operator
 * chose — so there's no manifest permission or runtime permission prompt to add here.
 *
 * CRITICAL correctness note carried forward for whoever builds task #34 (save): a picked file
 * can be any format a `ContentResolver` will decode (JPEG, WEBP, HEIF, PNG...), but LSB
 * steganography only survives lossless pixel data. [decodePickedCoverImage] below decodes the
 * picked Uri into a fully in-memory ARGB_8888 [Bitmap] exactly once and nothing downstream of
 * that point (this screen, [ImageStegoController], [dev.herakles.nightjar.ImageStegoCarrier])
 * ever touches the original Uri or file format again — [CoverSource.Picked] holds the decoded
 * [Bitmap] itself, not a Uri. Task #34's save step must keep it that way: write the *bitmap*
 * back out losslessly (e.g. PNG), never reopen/re-derive from the original picked Uri, which
 * would risk a lossy JPEG round-trip that silently destroys the embedded payload.
 */

/** The two bundled sample cover images task #11 shipped in `res/drawable`. */
enum class SampleCover(val label: String, val resId: Int) {
    GRADIENT("gradient", R.drawable.stego_cover_gradient),
    MOSAIC("mosaic", R.drawable.stego_cover_mosaic),
}

/**
 * v6 (task W2-4, spec.md gate-30): which Module-1 image technique the *technical* screen is
 * currently driving -- the frozen exact-LSB codec ([ImageStegoCarrier], INV-9) or the
 * JPEG-surviving sturdy codec ([SturdyImageCarrier], architecture.md "Sturdy image technique
 * (v6)"). Screen-local UI selection state, same reasoning [SampleCover] already is -- not carrier
 * state. [jarCatchFlow] (the art jar) is untouched by this addition and stays exact-only, per
 * this task's own scope note.
 */
enum class ImageTechnique { EXACT, STURDY }

/**
 * Where the active cover image came from: one of the two bundled [SampleCover]s, or a
 * [Picked] image the operator chose from their device via the Photo Picker (task #33).
 *
 * [Picked] carries the already-decoded [Bitmap], not the source `Uri` — see the CRITICAL
 * correctness note in this file's top KDoc. `Bitmap` has no structural `equals`, so
 * `CoverSource` equality (and therefore Compose's `remember(coverSource)` change detection)
 * falls back to reference identity, which is exactly right here: a new pick always produces a
 * new `Bitmap` instance.
 */
sealed interface CoverSource {
    data class Sample(val cover: SampleCover) : CoverSource
    data class Picked(val bitmap: Bitmap) : CoverSource
}

/** Label for the cover-image preview's `contentDescription` — [SampleCover.label], or a
 * generic label for a device-picked image (Photo Picker hands back a bare Uri, not a
 * human-friendly file name, and resolving one via `MediaStore`/`DocumentsContract` is more
 * plumbing than this preview description needs). */
private val CoverSource.previewLabel: String
    get() = when (this) {
        is CoverSource.Sample -> cover.label
        is CoverSource.Picked -> "device photo"
    }

/** The 3 one-shot actions this screen runs (embed, extract, check for hidden data), plus outcomes. */
sealed interface StegoStatus {
    data object Idle : StegoStatus
    data object Embedding : StegoStatus
    data object Extracting : StegoStatus
    data object Analyzing : StegoStatus
    data class Embedded(val payloadBytes: Int) : StegoStatus
    data class ExtractedSuccess(val text: String) : StegoStatus
    data class ExtractedFailure(val reason: DecodeFailure, val detail: String?) : StegoStatus
    // v6 (task W2-6, gate on the check's honest caveat): [coverWasLossyContainer] snapshots
    // whether the image actually checked traced back to a picked photo whose real container
    // (sniffed by magic bytes, not a declared MIME/extension) was JPEG/WebP/HEIC -- SturdyImage
    // SteganalysisRealPhotoTest.kt's real-photo measurement found this check's flagged/clear
    // reading on a JPEG is often just that photo's own JPEG-quantization pattern, not a signal
    // that anything is hidden. Defaults false so every existing call site (all @Preview
    // functions, the jar-disguised "peek inside" flow) keeps compiling/behaving unchanged --
    // same "codec-M01" precedent as this file's other optional params.
    data class Analyzed(val result: DetectionResult, val coverWasLossyContainer: Boolean = false) : StegoStatus

    /**
     * S-01 defense in depth: an embed/extract/check attempt ran out of memory mid-operation
     * (huge picked-cover bitmap copy, or `ImageSteganalysis`'s per-pixel channel-sample array —
     * see [ImageStegoController]'s catch sites) instead of the fixed [downsampleFactor] bug that
     * normally prevents this. Idle-equivalent (the operator can immediately retry with a smaller
     * image), never a crash.
     */
    data class Failed(val message: String) : StegoStatus
}

/**
 * Outcome of task #34's save-as-PNG / share-via-MediaStore action pair. Deliberately kept
 * separate from [StegoStatus] rather than folded into it: save/share are a secondary action
 * pair that runs *after* a successful [ImageStegoController.embed] without needing to replace
 * whatever [StegoStatus] is currently showing (an operator should be able to see "embedded 18
 * bytes" and "saved to your photo gallery" at the same time, not have one overwrite the
 * other). Mirrors the `isCoverLoading`/`coverLoadError` pair task #33 already used for a
 * secondary, `Context`-dependent async action kept out of [ImageStegoController] (which stays
 * free of any Android `Context` reference).
 */
sealed interface SaveStatus {
    data object Idle : SaveStatus
    data object Saving : SaveStatus

    /**
     * Task #36: split out from [Saving] so the status word matches which action the operator
     * actually tapped — before this, tapping "share" still rendered "saving" the whole time the
     * write ran, since both actions shared one state. [ImageStegoScreen]'s `onShare` runs the
     * identical persist-as-PNG write [Saving] already covers; sharing only additionally opens
     * the share sheet once that write finishes.
     */
    data object Sharing : SaveStatus
    data class Saved(val uri: Uri) : SaveStatus
    data class Failed(val message: String) : SaveStatus
}

/**
 * Stateful root: owns the [ImageStegoController], the sample-cover selection, the payload
 * text field, and bitmap decoding from resources. [ImageStegoContent] below is the
 * pure/previewable UI (no side effects, no `Context`, no resource decoding).
 */
@Composable
fun ImageStegoScreen(
    carrierFactory: (Bitmap) -> CovertCarrier<Bitmap>,
    detector: CovertDetector<Bitmap>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // v6 (task W2-4, gate-30): "exact" (the injected carrierFactory, unchanged) or "sturdy"
    // (SturdyImageBitmapCarrier, this file's own adapter over SturdyImageCarrier -- see that
    // class's KDoc for why the technical screen builds it directly rather than threading a
    // second factory parameter through MainActivity.kt/jarCatchFlow). Rebuilding the controller
    // whenever technique changes gives a fresh Idle/no-workingBitmap start, the same reset a
    // fresh cover selection already gets below.
    var technique by remember { mutableStateOf(ImageTechnique.EXACT) }
    val controller = remember(carrierFactory, detector, technique) {
        ImageStegoController(
            carrierFactory = { bitmap ->
                when (technique) {
                    ImageTechnique.EXACT -> carrierFactory(bitmap)
                    ImageTechnique.STURDY -> SturdyImageBitmapCarrier(bitmap)
                }
            },
            detector = detector,
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    var coverSource: CoverSource by remember { mutableStateOf(CoverSource.Sample(SampleCover.GRADIENT)) }
    var payloadText by remember { mutableStateOf("") }
    var isCoverLoading by remember { mutableStateOf(false) }
    var coverLoadError by remember { mutableStateOf<String?>(null) }
    // codec-M01: set instead of coverLoadError (this is informational, not a failure) whenever a
    // picked cover actually had transparency and got flattened onto an opaque background --
    // never touched for the two bundled sample covers, which are already opaque.
    var coverImportNotice by remember { mutableStateOf<String?>(null) }
    // v6 (task W2-6, gate-30 follow-up): whether the CURRENT cover's real container (sniffed by
    // magic bytes at pick time, never a declared MIME/extension) is JPEG/WebP/HEIC -- false for
    // both bundled sample covers (always PNG resources) and reset whenever a fresh cover is
    // picked. Feeds "check for hidden data"'s honest caveat (SturdyImageSteganalysisRealPhotoTest
    // .kt's measured false-flag rate on real JPEG photos), never used for anything else -- this
    // screen still never keeps the picked Uri/original bytes around (decodePickedCoverImage's own
    // "discard the original format" contract, this file's top KDoc), only this one display bit.
    var pickedCoverIsLossyContainer by remember { mutableStateOf(false) }
    var saveStatus: SaveStatus by remember { mutableStateOf<SaveStatus>(SaveStatus.Idle) }

    val pickCoverScope = rememberCoroutineScope()
    val pickCoverImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult // operator backed out of the picker; keep current cover
        isCoverLoading = true
        coverLoadError = null
        coverImportNotice = null
        pickCoverScope.launch {
            // v6 (task W2-6): sniffed alongside the decode, off the main thread -- a few header
            // bytes only (see sniffPickedCoverContainer), never the full file, and never kept
            // anywhere but this one boolean (decodePickedCoverImage's own KDoc: "nothing left
            // pointing at the original lossy file").
            val lossyContainer = withContext(Dispatchers.IO) { sniffPickedCoverIsLossy(context, uri) }
            val decoded = withContext(Dispatchers.IO) { decodePickedCoverImage(context, uri) }
            isCoverLoading = false
            if (decoded == null) {
                coverLoadError = "couldn't load that image. try a different one."
            } else {
                // codec-M01: flatten transparency (if any) once at import, off the main thread --
                // real work for a large picked photo, same reasoning as the decode itself.
                val flattened = withContext(Dispatchers.Default) { flattenToOpaque(decoded) }
                if (flattened !== decoded) {
                    coverImportNotice = "that image had transparent areas — they were filled in " +
                        "so the hidden data survives on this device."
                }
                pickedCoverIsLossyContainer = lossyContainer
                coverSource = CoverSource.Picked(flattened)
            }
        }
    }

    val coverBitmap = remember(coverSource) {
        when (val source = coverSource) {
            is CoverSource.Sample -> BitmapFactory.decodeResource(context.resources, source.cover.resId)
            is CoverSource.Picked -> source.bitmap
        }
    }
    val maxPayloadBytes = remember(controller, coverBitmap) { controller.maxPayloadBytesFor(coverBitmap) }

    // v6 (task W2-4, gate-30): sturdy's ~640px robustness floor (architecture.md "Sturdy image
    // technique (v6)") -- computed once per cover/technique so the cover section can show its
    // refusal plainly (this file's top KDoc CRITICAL note pattern: real feedback, not a silent
    // disabled button) rather than let embed() fail unexplained. Null for the exact technique,
    // which has no such floor.
    val sturdyCoverPrep = remember(coverBitmap, technique) {
        if (technique == ImageTechnique.STURDY) prepareSturdyCover(coverBitmap) else null
    }

    // Switching the cover (sample or freshly picked) OR the technique resets the working image
    // (and any embed/extract/check result) back to a clean start — a per-cover workflow needs
    // this even though the modem/detector screens (single fixed carrier) never had to reset
    // anything on select. Mirrors AudioStegoScreen's LaunchedEffect(coverAudio, technique).
    LaunchedEffect(coverBitmap, technique) {
        controller.selectCover(coverBitmap)
    }

    // Task #34: reset save/share status whenever the working image changes (fresh cover
    // selection, or a fresh embed) — a stale "saved"/"couldn't save" readout pointing at a
    // working image that's since moved on would mislead the operator.
    LaunchedEffect(controller.workingBitmap) {
        saveStatus = SaveStatus.Idle
    }

    val saveScope = rememberCoroutineScope()

    // v6 (task W2-4, gate-30): sturdy always writes JPEG (encodeSturdyJpeg -- the whole point of
    // the technique is surviving that recompression), exact keeps writing PNG unchanged. Same
    // two-phase-write MediaStore path either way; only the encode call and MIME type differ.
    suspend fun persistWorkingImage(bitmap: Bitmap): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (technique) {
                    ImageTechnique.EXACT -> saveBitmapAsPngToMediaStore(context, bitmap)
                    ImageTechnique.STURDY -> saveBitmapAsJpegToMediaStore(context, bitmap)
                }
            }
        }

    fun shareIntentFor(uri: Uri): Intent = when (technique) {
        ImageTechnique.EXACT -> buildPngShareIntent(uri)
        ImageTechnique.STURDY -> buildJpegShareIntent(uri)
    }

    val onSave: () -> Unit = save@{
        val bitmap = controller.workingBitmap
        if (!controller.hasEmbeddedPayload || bitmap == null) return@save
        saveStatus = SaveStatus.Saving
        saveScope.launch {
            saveStatus = persistWorkingImage(bitmap).fold(
                onSuccess = { uri -> SaveStatus.Saved(uri) },
                onFailure = { failure -> SaveStatus.Failed(failure.message ?: "couldn't save the image") },
            )
        }
    }

    val onShare: () -> Unit = share@{
        val bitmap = controller.workingBitmap
        if (!controller.hasEmbeddedPayload || bitmap == null) return@share
        // Reuse an already-saved Uri for this working image rather than writing a second copy.
        val alreadySavedUri = (saveStatus as? SaveStatus.Saved)?.uri
        if (alreadySavedUri != null) {
            context.startActivity(Intent.createChooser(shareIntentFor(alreadySavedUri), null))
            return@share
        }
        saveStatus = SaveStatus.Sharing
        saveScope.launch {
            saveStatus = persistWorkingImage(bitmap).fold(
                onSuccess = { uri ->
                    context.startActivity(Intent.createChooser(shareIntentFor(uri), null))
                    SaveStatus.Saved(uri)
                },
                onFailure = { failure -> SaveStatus.Failed(failure.message ?: "couldn't save the image to share") },
            )
        }
    }

    ImageStegoContent(
        status = controller.status,
        workingBitmap = controller.workingBitmap ?: coverBitmap,
        coverSource = coverSource,
        isCoverLoading = isCoverLoading,
        coverLoadError = coverLoadError,
        coverImportNotice = coverImportNotice,
        onSelectSample = {
            // v6 (task W2-6): both bundled sample covers are PNG resources -- never lossy.
            pickedCoverIsLossyContainer = false
            coverSource = CoverSource.Sample(it)
        },
        onPickFromDevice = {
            pickCoverImage.launch(
                PickVisualMediaRequest(mediaType = ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        technique = technique,
        onSelectTechnique = { technique = it },
        sturdyCoverPrep = sturdyCoverPrep,
        payloadText = payloadText,
        onPayloadTextChange = { payloadText = it },
        maxPayloadBytes = maxPayloadBytes,
        onEmbed = {
            val embedCover = when (technique) {
                ImageTechnique.EXACT -> coverBitmap
                ImageTechnique.STURDY -> (sturdyCoverPrep as? SturdyCoverPrep.Ready)?.bitmap ?: coverBitmap
            }
            controller.embed(embedCover, payloadText.encodeToByteArray())
        },
        onExtract = { controller.extract(controller.workingBitmap ?: coverBitmap) },
        onCheck = {
            controller.analyze(controller.workingBitmap ?: coverBitmap, pickedCoverIsLossyContainer)
        },
        saveStatus = saveStatus,
        hasEmbeddedPayload = controller.hasEmbeddedPayload,
        onSave = onSave,
        onShare = onShare,
        onBack = onBack,
    )
}

/**
 * Task #12 — jar-framed catch flow for [Module.IMAGE_STEGANOGRAPHY] ("the art jar",
 * [dev.herakles.nightjar.picker.JarRole.CREATION]), hosted by `JarDetailScreen` as its
 * `moduleFlow` slot (design/screen-flow.md § Screen 7). `JarDetailScreen` already renders the
 * "back to the shelf" row, the jar name/caption, and the firefly swarm above whatever this
 * composable returns (architecture.md § 6), so this function renders only the
 * catch/look-for-fireflies/peek-inside controls plus their result.
 *
 * A themed skin over the exact same [ImageStegoController]/[StegoStatus] state machine
 * [ImageStegoScreen] above already defines — screen-flow.md § Screen 7: "does not reimplement
 * embed/extract... logic, it re-presents it." Builds its own [ImageStegoController] wrapping the
 * concrete [ImageStegoCarrier]/[ImageSteganalysis] pair directly: unlike [ImageStegoScreen],
 * this function's call site (`catchFlowFor` in `JarCatchFlows.kt`, task #9) has a fixed
 * `(repository, onExit)` signature with no carrier/detector injection point the way `MainActivity`'s
 * technical-screen route has, so there's nowhere else for that wiring to live. Zero changes to
 * [ImageStegoController], [ImageStegoCarrier], or [ImageSteganalysis].
 *
 * Three actions, softened per spec.md gate-13 / screen-flow.md § Screen 7's copy-mapping table:
 *  - "catch a firefly" expands the cover selector (the 2 bundled [SampleCover]s only — no Photo
 *    Picker, no save/share; those stay technical-screen-only, per screen-flow.md's "what this
 *    addition deliberately does not build") + payload field inline; confirming calls the same
 *    [ImageStegoController.embed]. On [StegoStatus.Embedded], writes a
 *    `FireflyRecord(direction = "CREATED")` — this app hid something.
 *  - "look for fireflies" calls [ImageStegoController.extract] directly, no extra fields (same
 *    wireframe). On [StegoStatus.ExtractedSuccess], writes a
 *    `FireflyRecord(direction = "RECEIVED")` — this app found something someone else hid.
 *  - "peek inside" is the existing "check for hidden data" verb ([ImageStegoController.analyze]),
 *    included per gate-13 but writes NO [FireflyRecord]: a peek can find zero or nonzero hidden
 *    data in *someone else's* photo, so it's an analysis verb, not a creation/reception event
 *    this app itself performed — the same CREATED/RECEIVED-only contract [FireflyDao]'s KDoc
 *    already draws.
 *
 * [onExit] is accepted (the `catchFlowFor` dispatcher signature every module implements) but
 * deliberately unused here: screen-flow.md's Screen 7 wireframe gives this flow no exit
 * affordance of its own — catch/look are inline-expanding sections on the *same*
 * `JarDetailScreen`, not a new screen navigation ("no separate screen per action"), and
 * `JarDetailScreen` already owns the one "back to the shelf" row that leaves this screen. Left
 * as a deliberate, documented decision rather than inventing a second, redundant back
 * affordance — worth a second look if that reading turns out wrong.
 *
 * v6 addition (task W2-1, gate-31): a fourth row, "catch from a photo or file"
 * (design/screen-flow.md's v6 "Receiving" section), opens the Android Photo Picker
 * (`ActivityResultContracts.PickVisualMedia`, image MIME types only -- no permission added,
 * INV-10) and routes the picked `Uri` through [IncomingPipeline.route] -- the exact same
 * routing `MainActivity.kt` uses for a share-sheet/open-with `Intent`, never duplicated here.
 * [onIncomingOutcome] hands the resulting [IncomingOutcome] back up to `MainActivity.kt` (via
 * `JarDetailScreen`/`catchFlowFor`) to navigate to `Screen.Incoming`.
 */
@Composable
fun jarCatchFlow(
    repository: FireflyRepository,
    trailStore: TrailStateStore,
    onExit: () -> Unit,
    onIncomingOutcome: (IncomingOutcome) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val controller = remember {
        ImageStegoController(
            carrierFactory = { cover -> ImageStegoCarrier(cover) },
            detector = ImageSteganalysis(),
        )
    }
    DisposableEffect(controller) { onDispose { controller.dispose() } }

    // v6 (task W2-1, gate-31): "catch from a photo or file" -- Photo Picker, image MIME types
    // only, matching the art jar's own carrier (this module never handles audio). A null Uri
    // means the operator backed out of the picker -- no-op, same as every other picker launcher
    // in this app.
    val catchFromFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                IncomingPipeline.route(context, uri, action = "PICKER")
            }
            onIncomingOutcome(outcome)
        }
    }

    // v6 (task W2-2, gate-33): "hide one in a photo" -- a second, independent Photo Picker
    // launch (this module's own carrier is always an image, so this reuses the exact same
    // ImageOnly contract [catchFromFileLauncher] does, just routed to a new encode flow instead
    // of the receive pipeline). Decodes off Dispatchers.IO (real file I/O + CPU work, matching
    // decodePickedCoverImage's own KDoc), then prepares the sturdy send-side cover once, at pick
    // time, off Dispatchers.Default -- HideInPhotoFlow reads that prep directly rather than
    // recomputing it per technique switch. A null Uri (operator backed out) or a failed decode
    // is a silent no-op, same as every other picker launcher in this app.
    var hidePhotoSelection by remember { mutableStateOf<HidePhotoSelection?>(null) }
    val hidePhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val decoded = withContext(Dispatchers.IO) { decodePickedCoverImage(context, uri) }
            if (decoded != null) {
                val prep = withContext(Dispatchers.Default) { prepareSturdyCover(decoded) }
                hidePhotoSelection = HidePhotoSelection(original = decoded, coverPrep = prep)
            }
        }
    }

    var coverChoice by remember { mutableStateOf(SampleCover.GRADIENT) }
    var payloadText by remember { mutableStateOf("") }
    var catchExpanded by remember { mutableStateOf(false) }
    var pendingCatchPreview by remember { mutableStateOf("") }

    // W2-3 riddle trail (design/riddle-trail.md § Step 1, gate-36): while this jar's step is the
    // active one, "look for fireflies" decodes the practice carrier PracticeFireflies wrote
    // instead of the normal working/cover bitmap. [pendingLookBitmap] carries that practice
    // bitmap from dispatch time through to the ExtractedSuccess handler below -- ImageStegoController
    // .extract() doesn't update [ImageStegoController.workingBitmap] itself (only [embed] does),
    // so this is the only way the persisted carrier and the "was this a practice catch" signal
    // both reach the insert path correctly. Reset on every dispatch (practice or not), never left
    // stale from a previous tap.
    val trailState by trailStore.state.collectAsState()
    val trailActive = trailState.currentStep == TrailStep.ART
    // v6 (task W2-2, owner direction 2026-09-22, design/riddle-trail.md § Welcome + game layer,
    // commit 6b9cedf): the SEND step's glow now lives on the "hide one in a photo" row itself,
    // not on any firefly's own send row -- see HideInPhotoFlow.kt's KDoc.
    val sendStepActive = trailState.currentStep == TrailStep.SEND
    var pendingLookBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Task #19 fix, layer 2: live testing showed encode()/decode() for this codec's tiny
    // sample images complete fast enough (sub-frame) that `status` can cycle all the way back
    // to idle-equivalent before a second, distinct click delivery lands — which means a
    // status-transition guard alone (above/below) can't distinguish "one slow double-click"
    // from "two fast legitimate clicks" if a second click genuinely arrives moments later.
    // This time-based debounce is mechanism-agnostic: it rejects any second catch/look
    // dispatch within 500ms of the last one, regardless of whether the duplicate delivery
    // originates from Compose recomposition churn, the touch-input transport, or anything
    // else — closing the gap the state-based guards can't.
    var lastCatchDispatchAtMillis by remember { mutableStateOf(0L) }
    fun debounceCatchDispatch(action: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastCatchDispatchAtMillis < 500L) return
        lastCatchDispatchAtMillis = now
        action()
    }

    val coverBitmap = remember(coverChoice) {
        BitmapFactory.decodeResource(context.resources, coverChoice.resId)
    }
    val maxPayloadBytes = remember(coverBitmap) { controller.maxPayloadBytesFor(coverBitmap) }

    LaunchedEffect(coverBitmap) { controller.selectCover(coverBitmap) }

    // Task #22 fix (root cause, after task #19's and #21's fixes both failed to resolve the
    // live-reproduced duplicate insert): `embed()` writes `status` twice in quick succession
    // (Embedding, then Embedded(N) ~10ms later from the background `Dispatchers.Default`
    // coroutine) — close enough together that Compose sometimes launches two *separate*
    // `LaunchedEffect(controller.status)` coroutine instances back-to-back (one keyed on the
    // Embedding transition, one on the following Embedded(N) transition) without the first
    // one's cancellation actually stopping its body in time. Because the old code re-read the
    // live `controller.status` property *inside* the effect body instead of using the value
    // that triggered that specific instance, both instances ended up reading the *same* final
    // Embedded(N) value — and raced on the shared `previousStatus` remembered state, both
    // reading it as not-yet-Embedded before either wrote back, so both independently passed
    // the transition guard and both called `repository.insert(...)`. Live-reproduced and confirmed via
    // temporary logging: two effect firings, identical status object identity, both reading
    // `previousStatus=Idle`, 31ms apart — the same signature as every prior duplicate-insert
    // report. AcousticModemScreen.kt's `jarCatchFlow` never exhibited this bug because it
    // captures `status` into a stable local *once* per recomposition and both keys the effect
    // on and reads the body from that local — never a live re-read of the mutable property.
    // Mirroring that exact pattern here (capture, then key + read from the capture) closes the
    // gap: an effect instance can now only ever observe the value it was actually launched for.
    val status = controller.status
    var previousStatus: StegoStatus by remember { mutableStateOf(StegoStatus.Idle) }

    // Task #17 (gate-17), Stage B: persist the carrier PNG alongside the FireflyRecord at both
    // catch sites below, so the firefly the operator caught keeps the actual image the message
    // was hidden in. Same `controller.workingBitmap ?: coverBitmap` source onLookForFireflies/
    // onPeekInside already read from. PNG encoding is real CPU work, so it's pushed off the
    // composition (Main) dispatcher -- this LaunchedEffect body otherwise runs on Main. Falls
    // back to the existing media-less insert() when there's no bitmap to attach (should not
    // happen in practice -- coverBitmap is always a decoded bundled resource here -- but keeps
    // this path crash-free and zero-byte-file-free either way). Called from inside the two
    // transition-guarded `when` arms below, never outside them.
    suspend fun insertFireflyWithCarrier(record: FireflyRecord, bitmapOverride: Bitmap? = null): Long {
        val bitmap = bitmapOverride ?: controller.workingBitmap ?: coverBitmap
        if (bitmap == null) {
            return repository.insert(record)
        }
        val pngBytes = withContext(Dispatchers.Default) { encodePngBytes(bitmap) }
        return repository.insertWithMedia(record.copy(carrierKind = "IMAGE"), pngBytes, "png")
    }

    LaunchedEffect(status) {
        when {
            status is StegoStatus.Embedded && previousStatus !is StegoStatus.Embedded -> insertFireflyWithCarrier(
                FireflyRecord(
                    moduleId = Module.IMAGE_STEGANOGRAPHY.name,
                    direction = "CREATED",
                    timestampMillis = System.currentTimeMillis(),
                    payloadSizeBytes = status.payloadBytes,
                    technique = null,
                    payloadPreview = pendingCatchPreview.take(40),
                ),
            )
            status is StegoStatus.ExtractedSuccess && previousStatus !is StegoStatus.ExtractedSuccess -> {
                val practiceBitmap = pendingLookBitmap
                val id = insertFireflyWithCarrier(
                    FireflyRecord(
                        moduleId = Module.IMAGE_STEGANOGRAPHY.name,
                        direction = "RECEIVED",
                        timestampMillis = System.currentTimeMillis(),
                        payloadSizeBytes = status.text.encodeToByteArray().size,
                        technique = null,
                        payloadPreview = status.text.take(40),
                    ),
                    bitmapOverride = practiceBitmap,
                )
                // W2-3 (gate-36): a practice catch marks itself and advances the trail the
                // instant its own decode succeeds -- see this file's jarCatchFlow KDoc and
                // pendingLookBitmap's own comment above for why practiceBitmap != null is exactly
                // "this ExtractedSuccess came from the trail's own practice dispatch."
                if (practiceBitmap != null) {
                    trailStore.markPractice(id)
                    trailStore.advance(TrailStep.ART)
                }
                pendingLookBitmap = null
            }
            else -> Unit // Idle/Embedding/Extracting/Analyzing/ExtractedFailure/Analyzed/repeat: no catch/spot event
        }
        previousStatus = status
    }

    // v6 (task W2-2): while a photo is picked for "hide one in a photo", that flow takes over
    // this slot entirely (its own "back" collapses back to the row list below) -- same "no
    // separate screen per action" shape every other jar-mode expansion in this app already uses,
    // just for a bigger inline flow than catch/look's own field expansion.
    val currentHidePhotoSelection = hidePhotoSelection
    if (currentHidePhotoSelection != null) {
        HideInPhotoFlow(
            selection = currentHidePhotoSelection,
            repository = repository,
            trailStore = trailStore,
            sendStepActive = sendStepActive,
            onCancel = { hidePhotoSelection = null },
            onSent = { hidePhotoSelection = null },
        )
        return
    }

    JarImageStegoContent(
        status = controller.status,
        catchExpanded = catchExpanded,
        onToggleCatchExpanded = { catchExpanded = !catchExpanded },
        coverChoice = coverChoice,
        onSelectCover = { coverChoice = it },
        payloadText = payloadText,
        onPayloadTextChange = { payloadText = it },
        maxPayloadBytes = maxPayloadBytes,
        onCatch = {
            debounceCatchDispatch {
                pendingCatchPreview = payloadText
                controller.embed(coverBitmap, payloadText.encodeToByteArray())
            }
        },
        onLookForFireflies = {
            debounceCatchDispatch {
                if (trailActive) {
                    val practiceFile = PracticeFireflies.practiceFile(context, PracticeFireflies.Jar.ART)
                    val practiceBitmap = if (practiceFile.exists()) {
                        BitmapFactory.decodeFile(practiceFile.path)
                    } else {
                        null
                    }
                    if (practiceBitmap != null) {
                        pendingLookBitmap = practiceBitmap
                        controller.extract(practiceBitmap)
                    } else {
                        // Practice file not generated yet (a race with PracticeFireflies
                        // .ensureGenerated's background job) -- degrade to the normal decode
                        // rather than doing nothing; the trail step simply doesn't complete yet.
                        pendingLookBitmap = null
                        controller.extract(controller.workingBitmap ?: coverBitmap)
                    }
                } else {
                    pendingLookBitmap = null
                    controller.extract(controller.workingBitmap ?: coverBitmap)
                }
            }
        },
        onPeekInside = { controller.analyze(controller.workingBitmap ?: coverBitmap) },
        lookForFirefliesHighlighted = trailActive,
        onCatchFromFile = {
            catchFromFileLauncher.launch(
                PickVisualMediaRequest(mediaType = ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onHideInPhoto = {
            hidePhotoLauncher.launch(
                PickVisualMediaRequest(mediaType = ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        hideInPhotoHighlighted = sendStepActive,
    )
}

/**
 * Pure UI for [jarCatchFlow]: no [FireflyDao], no `Context`/bitmap decoding, same
 * stateful-root/pure-content split every screen in this app uses. Jar palette and type only
 * ([JarType], [JarTextPrimary]/[JarTextSecondary]/[JarTextTertiary]/[FireflyCreated]/
 * [FireflyReceived]) — never [MaterialTheme.typography]/[TextPrimary]/[TextSecondary]/
 * [AccentSignal], which belong to the technical surface this jar disguises (design/
 * firefly-jar-identity.md § Palette). Design-refresh pass (Task #18): action rows and the
 * payload field now carry the tinted, rounded-corner card treatment DESIGN_SPEC.md §1/§3
 * defines for them (gold/cyan/purple by verb, 8dp rows, 12dp confirm button) — this file's
 * three rows previously rendered as bare text with no fill or border.
 */
@Composable
private fun JarImageStegoContent(
    status: StegoStatus,
    catchExpanded: Boolean,
    onToggleCatchExpanded: () -> Unit,
    coverChoice: SampleCover,
    onSelectCover: (SampleCover) -> Unit,
    payloadText: String,
    onPayloadTextChange: (String) -> Unit,
    maxPayloadBytes: Int,
    onCatch: () -> Unit,
    onLookForFireflies: () -> Unit,
    onPeekInside: () -> Unit,
    // W2-3 (gate-36/gate-38): true while this jar's trail step is the active one -- glows the
    // "look for fireflies" row. Default keeps every existing @Preview call site compiling
    // unchanged, same "pure/previewable" reasoning this file's other optional params follow.
    lookForFirefliesHighlighted: Boolean = false,
    onCatchFromFile: () -> Unit,
    onHideInPhoto: () -> Unit = {},
    // v6 (task W2-2, owner direction 2026-09-22): true while the trail's SEND step is active --
    // glows this row rather than any firefly's own "send this firefly" row (design/riddle-trail.md
    // § Welcome + game layer). Default keeps every existing @Preview call site compiling
    // unchanged, same reasoning [lookForFirefliesHighlighted] already follows.
    hideInPhotoHighlighted: Boolean = false,
) {
    val idleEquivalent = status is StegoStatus.Idle ||
        status is StegoStatus.Embedded ||
        status is StegoStatus.ExtractedSuccess ||
        status is StegoStatus.ExtractedFailure ||
        status is StegoStatus.Analyzed ||
        status is StegoStatus.Failed
    val payloadBytes = payloadText.encodeToByteArray().size
    val canCatch = idleEquivalent && payloadText.isNotEmpty() && payloadBytes <= maxPayloadBytes

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // DESIGN_SPEC.md §3: "6px between stacked action rows" — catch/look/peek are the
        // framed jar's three stacked rows (§5 1f); the status readout below is its own section.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column {
                JarActionRow(
                    label = "catch a firefly",
                    enabled = idleEquivalent,
                    fill = JarActionCatchFill,
                    border = JarActionCatchBorder,
                    onClick = onToggleCatchExpanded,
                )
                if (catchExpanded) {
                    Column(
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SampleCover.entries.forEach { cover ->
                            JarCoverRow(
                                label = cover.label,
                                selected = coverChoice == cover,
                                enabled = idleEquivalent,
                                onClick = { onSelectCover(cover) },
                            )
                        }
                        BasicTextField(
                            value = payloadText,
                            onValueChange = onPayloadTextChange,
                            singleLine = true,
                            textStyle = JarType.Body.copy(color = JarTextPrimary),
                            cursorBrush = SolidColor(JarTextPrimary),
                            enabled = idleEquivalent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(JarTileFill)
                                .border(width = 1.dp, color = JarActionCatchBorder, shape = RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            decorationBox = { innerTextField ->
                                if (payloadText.isEmpty()) {
                                    Text(text = "what to hide", style = JarType.Body, color = JarTextTertiary)
                                }
                                innerTextField()
                            },
                        )
                        Text(
                            // codec-H01: a jar too small to hold even an empty frame gets its own
                            // line rather than a bare `0 / 0 bytes` counter.
                            text = if (maxPayloadBytes <= 0) {
                                "too small to hide anything in this jar"
                            } else {
                                "$payloadBytes / $maxPayloadBytes bytes"
                            },
                            style = JarType.TileCaption,
                            color = JarTextTertiary,
                        )
                        // A primary confirm action, not another list row — 12dp per DESIGN_SPEC.md
                        // §3's "12px (primary buttons...)" radius tier, distinct from the 8dp rows above.
                        JarActionRow(
                            label = "catch",
                            enabled = canCatch,
                            fill = JarActionCatchFill,
                            border = JarActionCatchBorder,
                            onClick = onCatch,
                            radius = 12.dp,
                        )
                    }
                }
            }
            JarActionRow(
                label = "look for fireflies",
                enabled = idleEquivalent,
                fill = JarActionLookFill,
                border = JarActionLookBorder,
                onClick = onLookForFireflies,
                highlighted = lookForFirefliesHighlighted,
            )
            JarActionRow(
                label = "peek inside",
                enabled = idleEquivalent,
                fill = JarActionCheckFill,
                border = JarActionCheckBorder,
                onClick = onPeekInside,
            )
            // v6 (task W2-1, gate-31): "catch from a photo or file" -- last in the action group,
            // per design/screen-flow.md's v6 wireframe. Cyan "receiving" tint (identity.md: "cyan
            // = received"), same as "look for fireflies" -- this row can land a firefly in ANY
            // jar, not necessarily this one, so it shares that verb's tint rather than "catch"'s.
            JarActionRow(
                label = stringResource(R.string.receive_catch_from_file_row),
                enabled = idleEquivalent,
                fill = JarActionLookFill,
                border = JarActionLookBorder,
                onClick = onCatchFromFile,
            )
            // v6 (task W2-2, gate-33): "hide one in a photo" -- gold "creating" tint (this row
            // embeds a NEW message into the operator's own picked photo, same verb family as
            // "catch a firefly"), last in the action group per design/screen-flow.md's v6
            // wireframe. `highlighted` is the trail's SEND-step glow (owner direction
            // 2026-09-22) -- see this composable's own KDoc note on [hideInPhotoHighlighted].
            JarActionRow(
                label = stringResource(R.string.send_row_hide_in_photo),
                enabled = idleEquivalent,
                fill = JarActionCatchFill,
                border = JarActionCatchBorder,
                onClick = onHideInPhoto,
                highlighted = hideInPhotoHighlighted,
            )
        }

        JarStatusBlock(status = status)
    }
}

/** One tinted, rounded action row — gold for "catch", cyan for "look", purple for "peek/check"
 *  (DESIGN_SPEC.md §1's card/row tint table). [radius] defaults to the 8dp action-row tier;
 *  the inline "catch"/"send"-style confirm button passes 12dp, the primary-button tier. */
@Composable
private fun JarActionRow(
    label: String,
    enabled: Boolean,
    fill: Color,
    border: Color,
    onClick: () -> Unit,
    radius: Dp = 8.dp,
    // W2-3: glows this row as the trail's current target (trail/TrailHighlight.kt) -- a no-op
    // Modifier when false, so every existing call site keeps its default styling untouched.
    highlighted: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(fill)
            .border(width = 1.dp, color = border, shape = RoundedCornerShape(radius))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .trailHighlight(active = highlighted, description = label)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = JarType.TileTitle,
            color = if (enabled) JarTextPrimary else JarTextTertiary,
        )
    }
}

@Composable
private fun JarCoverRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            // DESIGN_SPEC.md §1/§5 1g: selected option text takes the gold "chosen" accent,
            // unselected drops to the tertiary tier — the same pairing the technique chips use.
            style = JarType.TileTitle,
            color = if (selected) FireflyCreated else JarTextTertiary,
        )
    }
}

@Composable
private fun JarStatusBlock(status: StegoStatus) {
    when (status) {
        is StegoStatus.Idle -> Unit // nothing running, nothing to report
        is StegoStatus.Embedding -> JarStatusWord("catching")
        is StegoStatus.Extracting -> JarStatusWord("looking")
        is StegoStatus.Analyzing -> JarStatusWord("peeking")
        is StegoStatus.Embedded -> {
            val plural = if (status.payloadBytes == 1) "" else "s"
            Text(
                text = "you caught one — ${status.payloadBytes} byte$plural",
                // DESIGN_SPEC.md §2's "Result label" role (8sp/0.5sp tracking) — a short
                // accented announcement, not the message body itself.
                style = JarType.SectionLabel,
                color = FireflyCreated,
            )
        }
        is StegoStatus.ExtractedSuccess -> Text(
            text = status.text,
            // DESIGN_SPEC.md §2's "Message/body text" role — cream, not the FireflyReceived
            // accent; only the short result label above a real message takes the accent color.
            style = JarType.Body,
            color = JarTextPrimary,
        )
        is StegoStatus.ExtractedFailure -> Text(
            text = jarExtractFailureMessage(status.reason),
            style = JarType.Body,
            color = JarTextSecondary,
        )
        is StegoStatus.Analyzed -> JarAnalyzedBlock(result = status.result)
        is StegoStatus.Failed -> Text(
            text = status.message,
            style = JarType.Body,
            color = JarTextSecondary,
        )
    }
}

@Composable
private fun JarStatusWord(word: String) {
    Text(text = word, style = JarType.Footer, color = JarWatchingDim)
}

/** "peek inside"'s readout — confidence number kept (same "real data, not smoothed" discipline
 *  every other status readout in this app follows), flagged/clear reuses screen-flow.md § Screen
 *  7's detector copy mapping verbatim (same underlying "is something hidden here" concept), and
 *  the raw chi-square [DetectionResult.detail] string is dropped rather than surfaced —
 *  window/p-value jargon doesn't fit this surface's softened voice the way a byte count does. */
@Composable
private fun JarAnalyzedBlock(result: DetectionResult) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "${(result.confidence * 100).roundToInt()}%",
            style = JarType.Numeral,
            color = JarTextPrimary,
        )
        Text(
            text = if (result.flagged) "something's out there" else "all quiet",
            style = JarType.TileTitle,
            // DESIGN_SPEC.md §7 item 1: the mockup hardcodes this label to cyan regardless of
            // value — a documented bug. The history-row convention (cyan when flagged, cream
            // otherwise) is the intended semantic; implemented here rather than reproduced.
            color = if (result.flagged) FireflyReceived else JarTextPrimary,
        )
        result.estimatedPayloadBytes?.let { bytes ->
            Text(
                text = "about $bytes bytes, near as we can tell",
                style = JarType.TileCaption,
                color = JarTextTertiary,
            )
        }
    }
}

/** screen-flow.md § Screen 7's copy-mapping table applied to [DecodeFailure] for "look for
 *  fireflies" — same categories [extractFailureMessage] maps for the technical screen, softened
 *  wording only, no new behavior. */
private fun jarExtractFailureMessage(reason: DecodeFailure): String = when (reason) {
    DecodeFailure.NO_PAYLOAD_FOUND -> "nothing's glowing in here right now."
    DecodeFailure.HEADER_INVALID -> "that light doesn't look right — probably not one of yours."
    DecodeFailure.PAYLOAD_TOO_LARGE -> "too big to fit in the jar."
    DecodeFailure.UNRECOVERABLE_FEC -> "the light faded before it got here."
    DecodeFailure.INTEGRITY_MISMATCH -> "it flickered wrong on the way — the message got scrambled."
}

/**
 * Pure UI: no side effects, no bitmap decoding, no permission logic (Photo Picker needs none
 * — see this file's top KDoc). Launching the Photo Picker itself is a side effect ([onBack]'s
 * sibling callbacks — [onPickFromDevice] here), so it stays owned by [ImageStegoScreen]
 * exactly like [onEmbed]/[onExtract]/[onCheck]; this composable only renders whatever
 * [coverSource]/[isCoverLoading]/[coverLoadError] it's handed. Dense single column, no cards,
 * no icons. As of Task #17,
 * [AccentSignal] appears in exactly one place on this screen — the `flagged` word in
 * `check for hidden data`'s readout, same as [dev.herakles.nightjar.modules.detector
 * .DetectorContent]'s treatment (both surfaces are reporting the same underlying
 * [dev.herakles.nightjar.CovertDetector] concept). Everything else — embed/extract
 * status, the cover picker, the payload field — stays TextPrimary/TextSecondary only.
 *
 * Task #28: a one-line caption sits above the three action rows explaining what each of
 * `embed`/`extract`/`check for hidden data` actually does — real feedback said a
 * first-time user had no way to guess what the three verb buttons meant before tapping
 * one. Placed once, above all three, rather than repeated per-row: the three actions
 * read as one related group (they all operate on the same working image), so one
 * shared caption reads as a sentence, not three fragments.
 *
 * Task #36: the action rows below the caption are now 2 clusters (embed/extract/check, then
 * save/share) separated by spacing alone, since Task #34 grew that list from 3 rows to 5.
 * [SaveStatus.Saving]/[SaveStatus.Sharing] are likewise now distinct — the status word under
 * the action rows names the action the operator actually tapped instead of always reading
 * "saving".
 */
@Composable
fun ImageStegoContent(
    status: StegoStatus,
    workingBitmap: Bitmap,
    coverSource: CoverSource,
    isCoverLoading: Boolean,
    coverLoadError: String?,
    // codec-M01: defaults to null so every existing call site (all six @Preview functions in
    // this file) keeps compiling unchanged -- only ImageStegoScreen's real launcher callback
    // ever has a non-null value to pass.
    coverImportNotice: String? = null,
    onSelectSample: (SampleCover) -> Unit,
    onPickFromDevice: () -> Unit,
    // v6 (task W2-4, gate-30): defaults keep every existing @Preview call site (all exact-technique,
    // predating this task) compiling unchanged -- same "codec-M01" precedent as coverImportNotice
    // above.
    technique: ImageTechnique = ImageTechnique.EXACT,
    onSelectTechnique: (ImageTechnique) -> Unit = {},
    sturdyCoverPrep: SturdyCoverPrep? = null,
    payloadText: String,
    onPayloadTextChange: (String) -> Unit,
    maxPayloadBytes: Int,
    onEmbed: () -> Unit,
    onExtract: () -> Unit,
    onCheck: () -> Unit,
    saveStatus: SaveStatus,
    hasEmbeddedPayload: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
) {
    val idleEquivalent = (
        status is StegoStatus.Idle ||
            status is StegoStatus.Embedded ||
            status is StegoStatus.ExtractedSuccess ||
            status is StegoStatus.ExtractedFailure ||
            status is StegoStatus.Analyzed ||
            status is StegoStatus.Failed
        ) && !isCoverLoading && saveStatus !is SaveStatus.Saving && saveStatus !is SaveStatus.Sharing
    val payloadBytes = payloadText.encodeToByteArray().size
    // v6 (task W2-7): sturdy's SLOT is a fixed size, but the real message length is variable, 1..
    // maxPayloadBytes (architecture.md "Sturdy image technique (v6)": the frame's own length field
    // carries the true count, zero-padding the rest of the slot) -- same non-empty-and-within-
    // capacity gate as exact, plus sturdy's own Ready-cover requirement.
    val canEmbed = idleEquivalent && when (technique) {
        ImageTechnique.EXACT -> payloadText.isNotEmpty() && payloadBytes <= maxPayloadBytes
        ImageTechnique.STURDY ->
            payloadBytes in 1..maxPayloadBytes && sturdyCoverPrep is SturdyCoverPrep.Ready
    }
    // Task #34: save/share only unlock once embed() has actually produced a stego image for
    // the currently selected cover (this file's top KDoc CRITICAL note + ImageStegoController
    // .hasEmbeddedPayload's own KDoc).
    val canSaveOrShare = idleEquivalent && hasEmbeddedPayload

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .height(48.dp)
                .clickable { onBack() }
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "back",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 56.dp, start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = "image steganography",
                style = MaterialTheme.typography.displayLarge,
                color = TextPrimary,
            )

            // v6 (task W2-4, gate-30): technique selector, same selector-row style ("cover image"
            // below) this screen already uses -- mirrors AudioStegoScreen's "technique" section.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.workshop_image_technique_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                )
                CoverRow(
                    label = stringResource(R.string.workshop_image_technique_exact),
                    selected = technique == ImageTechnique.EXACT,
                    enabled = idleEquivalent,
                    onClick = { onSelectTechnique(ImageTechnique.EXACT) },
                )
                CoverRow(
                    label = stringResource(R.string.workshop_image_technique_sturdy),
                    selected = technique == ImageTechnique.STURDY,
                    enabled = idleEquivalent,
                    onClick = { onSelectTechnique(ImageTechnique.STURDY) },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "cover image",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                )
                SampleCover.entries.forEach { cover ->
                    CoverRow(
                        label = cover.label,
                        selected = (coverSource as? CoverSource.Sample)?.cover == cover,
                        enabled = idleEquivalent,
                        onClick = { onSelectSample(cover) },
                    )
                }
                CoverRow(
                    label = if (isCoverLoading) "device photo (loading)" else "device photo",
                    selected = coverSource is CoverSource.Picked,
                    enabled = idleEquivalent,
                    onClick = onPickFromDevice,
                )
                coverLoadError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                coverImportNotice?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                // v6 (task W2-4): sturdy's ~640px robustness floor, shown plainly (this file's top
                // KDoc CRITICAL note pattern) rather than a silently disabled "embed" -- the
                // rejection detail itself is SturdyImageAndroidAdapter.kt's existing copy, not new
                // copy authored by this task.
                (sturdyCoverPrep as? SturdyCoverPrep.Rejected)?.let { rejected ->
                    Text(
                        text = rejected.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                // Owner request (v6 addition): tap the working image to view it fullscreen.
                // Keyed on workingBitmap (not firefly.id -- there's no firefly here) so the
                // fullscreen state resets if a fresh embed/extract swaps the displayed bitmap
                // out from under an open preview.
                var showFullscreenCover by remember(workingBitmap) { mutableStateOf(false) }
                val coverDescription = "${coverSource.previewLabel} cover image preview"
                Box {
                    Image(
                        bitmap = workingBitmap.asImageBitmap(),
                        contentDescription = coverDescription,
                        modifier = Modifier
                            .size(96.dp)
                            .border(width = 1.dp, color = BorderDefault)
                            .clickable(onClickLabel = "view fullscreen") { showFullscreenCover = true },
                    )
                    // Subtle tap affordance (owner request): a quiet corner glyph -- the image
                    // itself already carries the click target and its onClickLabel above.
                    ExpandGlyph(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp),
                        tint = TextSecondary,
                    )
                }
                if (showFullscreenCover) {
                    FullscreenImageViewer(
                        bitmap = workingBitmap,
                        contentDescription = coverDescription,
                        onDismiss = { showFullscreenCover = false },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "payload",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                )
                BasicTextField(
                    value = payloadText,
                    onValueChange = onPayloadTextChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
                    cursorBrush = SolidColor(TextPrimary),
                    enabled = idleEquivalent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(width = 1.dp, color = BorderDefault)
                        .padding(12.dp),
                    decorationBox = { innerTextField ->
                        if (payloadText.isEmpty()) {
                            Text(
                                text = "text to hide",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextSecondary,
                            )
                        }
                        innerTextField()
                    },
                )
                Text(
                    // S-03: over capacity previously just silently grayed out "embed" with no
                    // explanation -- mirrors AudioStegoContent's counter, which already said why.
                    // codec-H01: maxPayloadBytes == 0 can mean "this cover can't hold a frame at
                    // all" (ImageStegoCarrier.canEmbed == false), not just "trimmed to zero" --
                    // worth a distinct message rather than a bare `0 / 0 bytes` that reads as a
                    // typo. v6 (task W2-7): sturdy's gate is the same "1..maxPayloadBytes" shape as
                    // exact now that the carrier carries the real length in its own frame field
                    // (architecture.md), so it shares this live "n / max bytes" counter -- no more
                    // sturdy-only exact-match message.
                    text = when {
                        maxPayloadBytes <= 0 -> "this cover is too small to hide anything"
                        payloadBytes > maxPayloadBytes ->
                            "$payloadBytes / $maxPayloadBytes bytes — " +
                                "${payloadBytes - maxPayloadBytes} over, trim it"
                        else -> "$payloadBytes / $maxPayloadBytes bytes"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }

            Column {
                Text(
                    text = if (technique == ImageTechnique.STURDY) {
                        stringResource(R.string.workshop_image_actions_caption_sturdy)
                    } else {
                        "embed hides text in the image, extract reads it back, " +
                            "check scans for hidden data without extracting it. save/share unlock " +
                            "after a successful embed and always write PNG, so the hidden data survives"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                // gate-30: the check's own measured behavior against sturdy output -- see
                // strings_workshop_image.xml's KDoc-style comment and
                // SturdyImageSteganalysisRealPhotoTest.kt for the measurement this is written
                // from. Exact technique shows nothing extra here (its check copy is unchanged).
                if (technique == ImageTechnique.STURDY) {
                    Text(
                        text = stringResource(R.string.workshop_image_check_caption_sturdy),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                // Task #36: embed/extract/check (act on the working image directly) grouped
                // separately from save/share (export the working image elsewhere) via spacing
                // alone — 12dp between the two clusters, 0dp within one, matching
                // AcousticModemScreen's own action-row grouping. This list grew from 3 rows
                // (Task #13) to 5 (Task #34) and read as one undifferentiated stack.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        ActionRow(label = "embed", enabled = canEmbed, onClick = onEmbed)
                        ActionRow(label = "extract", enabled = idleEquivalent, onClick = onExtract)
                        ActionRow(label = "check for hidden data", enabled = idleEquivalent, onClick = onCheck)
                    }
                    Column {
                        // v6 (task W2-4): sturdy always saves/shares JPEG (encodeSturdyJpeg) --
                        // exact's own "save as PNG" label is unchanged.
                        ActionRow(
                            label = if (technique == ImageTechnique.STURDY) {
                                stringResource(R.string.workshop_image_save_action_jpeg)
                            } else {
                                "save as PNG"
                            },
                            enabled = canSaveOrShare,
                            onClick = onSave,
                        )
                        ActionRow(label = "share", enabled = canSaveOrShare, onClick = onShare)
                    }
                }
            }

            StatusBlock(status = status)
            SaveStatusBlock(status = saveStatus, technique = technique)
        }
    }
}

/** One selectable row in the cover-image list: a bundled [SampleCover] or the "device photo"
 * Photo Picker trigger (task #33) — both render identically, so this one composable serves
 * both. */
@Composable
private fun CoverRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) TextPrimary else TextSecondary,
        )
    }
}

@Composable
private fun ActionRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) TextPrimary else TextSecondary,
        )
    }
}

@Composable
private fun StatusBlock(status: StegoStatus) {
    when (status) {
        is StegoStatus.Idle -> Unit // nothing running, nothing to report
        is StegoStatus.Embedding -> StatusWord("embedding")
        is StegoStatus.Extracting -> StatusWord("extracting")
        is StegoStatus.Analyzing -> StatusWord("analyzing")
        is StegoStatus.Embedded -> {
            val plural = if (status.payloadBytes == 1) "" else "s"
            Text(
                text = "embedded ${status.payloadBytes} byte$plural into the working image",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
        }
        is StegoStatus.ExtractedSuccess -> Text(
            text = status.text,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
        )
        is StegoStatus.ExtractedFailure -> Text(
            text = extractFailureMessage(status.reason),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )
        is StegoStatus.Analyzed -> AnalyzedBlock(result = status.result, coverWasLossyContainer = status.coverWasLossyContainer)
        is StegoStatus.Failed -> Text(
            text = status.message,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )
    }
}

@Composable
private fun StatusWord(word: String) {
    Text(
        text = word,
        style = MaterialTheme.typography.labelLarge,
        color = TextSecondary,
    )
}

/**
 * Task #34's secondary readout, rendered independently of [StatusBlock] — see [SaveStatus]'s
 * KDoc for why the two stay separate. `labelSmall`/[TextSecondary] throughout, same
 * "informational, not destructive" voice [extractFailureMessage] uses for a failed extract.
 *
 * v6 (task W2-4): [technique] only changes the [SaveStatus.Saved] wording (PNG vs JPEG, matching
 * which format [ImageStegoScreen]'s `persistWorkingImage` actually wrote) -- defaults to
 * [ImageTechnique.EXACT] so this function's own behavior is unchanged unless a caller passes
 * STURDY.
 */
@Composable
private fun SaveStatusBlock(status: SaveStatus, technique: ImageTechnique = ImageTechnique.EXACT) {
    when (status) {
        is SaveStatus.Idle -> Unit
        is SaveStatus.Saving -> StatusWord("saving")
        is SaveStatus.Sharing -> StatusWord("sharing")
        is SaveStatus.Saved -> Text(
            text = if (technique == ImageTechnique.STURDY) {
                stringResource(R.string.workshop_image_saved_jpeg)
            } else {
                "saved to your photo gallery as PNG"
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        is SaveStatus.Failed -> Text(
            text = "couldn't save: ${status.message}",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
    }
}

/**
 * Live readout for `check for hidden data` — same shape as `DetectorScreen`'s `ReadoutBlock`:
 * confidence percentage (always TextPrimary), flagged/clear word ([AccentSignal] when flagged,
 * TextSecondary when clear — Task #17), and the detector's own [DetectionResult.detail]/
 * [DetectionResult.estimatedPayloadBytes] surfaced verbatim when present. That verbatim detail
 * line (real chi-square window/run output, e.g. "sustained PoV-equalization run: 5/32
 * windows...") is the one deliberate rough edge on this screen, the same move task #7/#10 made
 * with the modem's FEC count and the detector's tone-grid note.
 *
 * [coverWasLossyContainer] (task W2-6, gate-30 follow-up): true once the checked cover's real
 * container was sniffed as JPEG/WebP/HEIC. `ImageSteganalysisRealPhotoFalseFlagTest.kt` measured
 * this check's false-flag rate on clean, never-embedded real photos per container -- material
 * enough on JPEG that the verdict alone is misleading there, so this renders one honest caveat
 * line underneath it. The verdict itself (confidence/flagged/detail/estimate above) is always
 * shown unchanged -- this never hides or downgrades a flag, only explains it.
 */
@Composable
private fun AnalyzedBlock(result: DetectionResult, coverWasLossyContainer: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "${(result.confidence * 100).roundToInt()}%",
            style = MaterialTheme.typography.displayLarge,
            color = TextPrimary,
        )
        Text(
            text = if (result.flagged) "flagged" else "clear",
            style = MaterialTheme.typography.labelLarge,
            color = if (result.flagged) AccentSignal else TextSecondary,
        )
        result.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        result.estimatedPayloadBytes?.let { bytes ->
            Text(
                text = "~$bytes bytes estimated",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        if (coverWasLossyContainer) {
            Text(
                text = stringResource(R.string.workshop_image_check_jpeg_caveat),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}

/**
 * Maps [DecodeFailure] to on-voice copy for `extract`: what's wrong, what to do about it, two
 * sentences max (voice contract), no exclamation, no red danger color — a failed extract is
 * informational, not destructive. Phrased for the image carrier rather than reusing the
 * modem's acoustic-specific copy ("move the phones closer" doesn't apply here).
 */
private fun extractFailureMessage(reason: DecodeFailure): String = when (reason) {
    DecodeFailure.NO_PAYLOAD_FOUND -> "no hidden payload found in this image."
    DecodeFailure.HEADER_INVALID -> "header didn't check out. this image may not hold a nightjar payload."
    DecodeFailure.PAYLOAD_TOO_LARGE -> "declared payload is too large for this image. frame rejected."
    DecodeFailure.UNRECOVERABLE_FEC -> "too much data loss to recover."
    DecodeFailure.INTEGRITY_MISMATCH -> "checksum didn't match. the payload was altered or corrupted."
}

/**
 * Owns the [CovertCarrier]/[CovertDetector] round-trip work for this screen. Constructor
 * takes only the interfaces plus a factory for building a [CovertCarrier]<Bitmap> bound to
 * whichever cover image is active — no `ImageStegoCarrier`/`ImageSteganalysis` reference
 * anywhere in this file, the same discipline `AcousticModemController`/`DetectorController`
 * established for their respective interfaces.
 *
 * Not an `androidx.lifecycle.ViewModel` — that dependency isn't in this module's gradle file,
 * and a plain `mutableStateOf`-backed class disposed via `DisposableEffect` is the same
 * "Simple is better" pattern `AcousticModemController`/`DetectorController` already use.
 *
 * Runs on [Dispatchers.Default], not [Dispatchers.IO] like the modem/detector controllers —
 * embed/decode/analyze here are CPU-bound pixel-bit-twiddling and chi-square arithmetic over
 * an in-memory `Bitmap`, not blocking I/O (no speaker, no mic, no filesystem call in this file).
 */
class ImageStegoController(
    private val carrierFactory: (Bitmap) -> CovertCarrier<Bitmap>,
    private val detector: CovertDetector<Bitmap>,
) {
    var status: StegoStatus by mutableStateOf(StegoStatus.Idle)
        private set

    /**
     * The bitmap `extract`/`check for hidden data` operate on: the selected cover until a
     * successful `embed` replaces it with the produced stego image. Null only before the
     * screen's first [selectCover] call (the `LaunchedEffect(coverBitmap)` in
     * [ImageStegoScreen] fires before first render, so callers always fall back to the raw
     * cover bitmap regardless).
     */
    var workingBitmap: Bitmap? by mutableStateOf(null)
        private set

    /**
     * True once [embed] has produced a stego image for the currently selected cover — the gate
     * task #34's save/share actions use ("after a successful embed()"). Reset to false by
     * [selectCover] (a freshly selected cover, sample or picked, has no embedded payload yet)
     * and set true at the end of a successful [embed]. An explicit flag rather than comparing
     * [workingBitmap] against the cover by reference, so this doesn't quietly depend on
     * `ImageStegoCarrier.encode`'s current "always returns a fresh copy" implementation detail.
     */
    var hasEmbeddedPayload: Boolean by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val idleEquivalent: Boolean
        get() = status is StegoStatus.Idle ||
            status is StegoStatus.Embedded ||
            status is StegoStatus.ExtractedSuccess ||
            status is StegoStatus.ExtractedFailure ||
            status is StegoStatus.Analyzed ||
            status is StegoStatus.Failed

    /** Reset to a freshly selected cover image, discarding any prior embed/extract/check result. */
    fun selectCover(cover: Bitmap) {
        workingBitmap = cover
        hasEmbeddedPayload = false
        status = StegoStatus.Idle
    }

    /** Capacity of [cover] for this codec, read synchronously — cheap dimension arithmetic only. */
    fun maxPayloadBytesFor(cover: Bitmap): Int = carrierFactory(cover).maxPayloadBytes

    /**
     * Hide [payload] in [cover] and make the result the new working image. No-op while busy.
     * Always encodes into the pristine sample [cover] (never into an already-embedded working
     * image), so repeated taps stay predictable instead of stacking frames.
     *
     * Task #19 fix: the [idleEquivalent] gate check and the `status = StegoStatus.Embedding`
     * write that leaves idle-equivalent must happen on the SAME synchronous call — `status`
     * used to only flip inside `scope.launch { ... }`, which merely schedules the coroutine
     * rather than running it immediately. That left a real window where two `embed()` calls
     * arriving back-to-back (e.g. a double-fired tap) could both read `status` as still
     * idle-equivalent and both pass the gate, each independently running to completion and
     * each triggering jarCatchFlow's catch-logging effect — a duplicate [FireflyRecord] insert
     * task #18 live-reproduced from what looked like a single tap. Writing `status` here,
     * before `scope.launch`, closes that window: a second call arriving even a moment later
     * sees `status == Embedding` and is rejected by the gate.
     *
     * S-01 defense in depth: [ImageStegoCarrier.encode]'s mutable-bitmap copy is real allocation
     * pressure even after [downsampleFactor]'s fix bounds the source bitmap's dimensions (a
     * still-sizable device is still a real allocation on a memory-constrained device) — catching
     * [OutOfMemoryError] here turns that into a visible, idle-equivalent [StegoStatus.Failed]
     * instead of a crash.
     */
    fun embed(cover: Bitmap, payload: ByteArray) {
        if (!idleEquivalent) return
        status = StegoStatus.Embedding
        scope.launch {
            val carrier = carrierFactory(cover)
            val stego = try {
                carrier.encode(payload)
            } catch (oversized: IllegalArgumentException) {
                status = StegoStatus.Idle
                return@launch
            } catch (oom: OutOfMemoryError) {
                status = StegoStatus.Failed(OOM_ERROR_MESSAGE)
                return@launch
            }
            workingBitmap = stego
            hasEmbeddedPayload = true
            status = StegoStatus.Embedded(payload.size)
        }
    }

    /** Attempt to recover a payload from [sample]. No-op while busy. Same synchronous-gate
     *  discipline as [embed] — see its KDoc, including the S-01 [OutOfMemoryError] catch. */
    fun extract(sample: Bitmap) {
        if (!idleEquivalent) return
        status = StegoStatus.Extracting
        scope.launch {
            val carrier = carrierFactory(sample)
            status = try {
                when (val result = carrier.decode(sample)) {
                    is DecodeResult.Success ->
                        StegoStatus.ExtractedSuccess(result.payload.decodeToString())
                    is DecodeResult.Failure ->
                        StegoStatus.ExtractedFailure(result.reason, result.detail)
                }
            } catch (oom: OutOfMemoryError) {
                StegoStatus.Failed(OOM_ERROR_MESSAGE)
            }
        }
    }

    /** Score [sample] for the likelihood it holds an embedded payload. No-op while busy. Same
     *  synchronous-gate discipline as [embed] — see its KDoc, including the S-01
     *  [OutOfMemoryError] catch ([ImageSteganalysis]'s per-pixel channel-sample array is the
     *  largest single allocation in this file's whole embed/extract/check pipeline).
     *  [coverWasLossyContainer] (task W2-6) is a plain caller-supplied snapshot of whether the
     *  current cover's real container (sniffed by magic bytes) was JPEG/WebP/HEIC -- this
     *  controller does no sniffing itself, only carries the flag into [StegoStatus.Analyzed] so
     *  the honest caveat renders against the state that produced the verdict, not whatever cover
     *  happens to be selected by the time the result is drawn. */
    fun analyze(sample: Bitmap, coverWasLossyContainer: Boolean = false) {
        if (!idleEquivalent) return
        status = StegoStatus.Analyzing
        scope.launch {
            status = try {
                val result = detector.analyze(sample)
                DebugProbe.reportDetectorConfidence(ModuleId.IMAGE_STEGANALYSIS, result.confidence)
                StegoStatus.Analyzed(result, coverWasLossyContainer)
            } catch (oom: OutOfMemoryError) {
                StegoStatus.Failed(OOM_ERROR_MESSAGE)
            }
        }
    }

    /** Cancels any in-flight work. Call from `DisposableEffect.onDispose`. */
    fun dispose() {
        scope.cancel()
    }

    private companion object {
        /** Copy shown for [StegoStatus.Failed] — plain, lowercase, matches this screen's other
         *  short failure captions (e.g. `coverLoadError`'s "couldn't load that image..."). */
        const val OOM_ERROR_MESSAGE = "that image is too large to work with here. try a smaller one."
    }
}

/**
 * v6 (task W2-4, gate-30): [CovertCarrier]<Bitmap> adapter over [SturdyImageCarrier], so this
 * screen's existing Bitmap-shaped [ImageStegoController] can drive the sturdy technique through
 * the exact same embed/extract/encode-decode plumbing it already uses for the frozen exact-LSB
 * codec (INV-9: this wraps, never modifies, [SturdyImageCarrier]'s own frame format).
 * [BitmapPixelSurface]/[toBitmap] are `SturdyImageAndroidAdapter.kt`'s existing Bitmap<->
 * PixelSurface bridge (the same one [dev.herakles.nightjar.incoming.SturdyImageFireflyDecoder]
 * uses for the receive side) -- this class adds no new pixel-format logic of its own, only the
 * interface adaptation, and is instantiated directly here rather than threaded through
 * `MainActivity.kt`/`jarCatchFlow` as a second factory parameter, so neither of those needs to
 * change for this task (this task's own scope note: technical content only).
 */
private class SturdyImageBitmapCarrier(private val cover: Bitmap) : CovertCarrier<Bitmap> {
    private val inner = SturdyImageCarrier(BitmapPixelSurface(cover))

    override val descriptor = inner.descriptor
    override val maxPayloadBytes: Int = inner.maxPayloadBytes

    override fun encode(payload: ByteArray): Bitmap = inner.encode(payload).toBitmap()

    override fun decode(carrier: Bitmap): DecodeResult {
        val surface = BitmapPixelSurface(carrier)
        return SturdyImageCarrier(surface).decode(surface)
    }
}

// --- Task #33: Photo Picker cover decoding. Plain (non-composable) functions so they can run
// off the main thread via `withContext(Dispatchers.IO)` from ImageStegoScreen's launcher
// callback — decoding an arbitrary device photo is real file I/O plus real CPU work, unlike
// the sample covers' small bundled-resource decode. ---

/**
 * Longest edge, in pixels, a Photo-Picker-selected cover is downsampled to before it's fully
 * decoded. 4096 comfortably covers this screen's LSB-capacity math (more pixels only ever
 * means more payload capacity, never less correctness) while keeping the raw ARGB_8888
 * buffer's memory footprint bounded — an undownsampled 4096x4096 decode is ~64MB, whereas an
 * unbounded decode of, say, a 8000x6000 photo would be ~183MB for one `Bitmap`.
 */
private const val MAX_PICKED_COVER_DIMENSION = 4096

/**
 * Decodes [uri] (a content Uri handed back by [androidx.activity.result.contract
 * .ActivityResultContracts.PickVisualMedia]) into a fully in-memory, uncompressed [Bitmap].
 *
 * This is the one place the "never assume/preserve the original format" correctness
 * constraint (this file's top KDoc) actually gets enforced: regardless of whether the picked
 * file is a JPEG, WEBP, HEIF, or PNG on disk, [BitmapFactory.decodeStream] always hands back
 * plain decoded pixels, and this function returns only that [Bitmap] — never the [uri], never
 * a format tag. From the caller's next line onward there is nothing left pointing at the
 * original lossy file to accidentally round-trip through.
 *
 * Two-pass decode (bounds-only, then real decode with `inSampleSize`) because a
 * `ContentResolver` `InputStream` generally isn't seekable/resettable, so the bounds pass and
 * the real decode each need their own freshly opened stream — the standard pattern from
 * Android's "Loading Large Bitmaps Efficiently" guide. `inPreferredConfig` is pinned to
 * [Bitmap.Config.ARGB_8888] (never `HARDWARE`, which some decode paths default to on newer
 * API levels) because [dev.herakles.nightjar.ImageStegoCarrier]'s LSB embedding reads/writes
 * individual pixels via `getPixel`/`setPixel`, which a hardware bitmap does not support.
 *
 * Returns null if the Uri can't be opened, the bytes don't decode as an image (corrupt file,
 * revoked grant, I/O failure), or decoding runs the process out of memory — the caller shows
 * a short failure message and leaves the previously selected cover in place rather than
 * crashing. `BitmapFactory.decodeStream` always returns null itself when
 * `inJustDecodeBounds = true` (bounds land in `options.outWidth`/`outHeight` instead, never in
 * the return value), so the bounds pass tracks "did the stream even open" via the `use` block's
 * own return value rather than the decode call's.
 *
 * `internal` (task W2-2): reused as-is by `HideInPhotoFlow.kt`'s own Photo Picker launcher --
 * "hide one in a photo" needs the exact same bounded, `HARDWARE`-config-free decode this screen's
 * cover picker already established, not a second copy of it.
 */
internal fun decodePickedCoverImage(context: Context, uri: Uri): Bitmap? = try {
    val resolver = context.contentResolver

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStreamOpened = resolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
        true
    }
    if (boundsStreamOpened != true || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = downsampleFactor(bounds.outWidth, bounds.outHeight, MAX_PICKED_COVER_DIMENSION)
        }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    }
} catch (failure: Exception) {
    null
} catch (oom: OutOfMemoryError) {
    null
}

/**
 * Sniffs whether [uri]'s real container (by magic bytes, via [FileSniffer.sniff] — the same
 * receive-path sniffer `IncomingPipeline` uses, never a declared MIME/extension) is JPEG, WebP or
 * HEIC (task W2-6, gate-30 follow-up: the "check for hidden data" honest caveat). Reads only a
 * handful of header bytes off their own short-lived stream — this does not weaken
 * [decodePickedCoverImage]'s "nothing left pointing at the original lossy file" contract just
 * above: the Uri/bytes themselves are never kept past this call, only the single boolean this
 * returns, for *display* only (whether to show a caveat under a later check verdict), never to
 * reconstruct or re-embed against the original file.
 */
internal fun sniffPickedCoverIsLossy(context: Context, uri: Uri): Boolean {
    val head = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(SNIFF_HEADER_BYTES)
            val read = stream.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
    } catch (failure: Exception) {
        null
    } ?: ByteArray(0)
    return when (FileSniffer.sniff(head)) {
        SniffedType.JPEG, SniffedType.WEBP, SniffedType.HEIF -> true
        else -> false
    }
}

/** Comfortably covers every magic-byte pattern [FileSniffer.sniff] checks (the longest is the
 *  12-byte RIFF/ISO-BMFF brand check). */
private const val SNIFF_HEADER_BYTES = 32

/**
 * The smallest power-of-two `inSampleSize` (1, 2, 4, 8, ...) that brings the post-scale long
 * edge of a [width]x[height] image to [maxDimension] or under, per `BitmapFactory.Options
 * .inSampleSize`'s own contract (power-of-two values decode fastest/cleanest — non-power-of-two
 * values get rounded down to the nearest power of two internally anyway). An edge that lands
 * exactly on [maxDimension] stays at sample size 1 — it's already within budget, not over it.
 *
 * S-01 fix (CRITICAL): the previous condition compared the *already-halved* `w`/`h` against
 * [maxDimension] (`while (w / 2 >= maxDimension ...)`) instead of the current, not-yet-halved
 * value. That off-by-one-power-of-two meant any cover up to ~2x [maxDimension] on its long edge
 * (e.g. a real 24-50MP photo, 8000x6000 or 5712x4284) evaluated the loop condition as false on
 * its very first check and skipped downsampling entirely — `inSampleSize` stayed 1, and
 * `BitmapFactory` decoded the image at full, undownsampled resolution (~183MB ARGB_8888 for
 * 8000x6000 against this function's own KDoc promise of a ~64MB ceiling), risking an OOM crash
 * in this decode or the very next `copy()`/pixel-array allocation downstream (`ImageStegoCarrier
 * .encode`, `ImageSteganalysis`). `internal` (not `private`) purely for `ImageStegoScreenTest`'s
 * visibility, matching [encodePngBytes]'s precedent in this same file.
 */
internal fun downsampleFactor(width: Int, height: Int, maxDimension: Int): Int {
    var sampleSize = 1
    var w = width
    var h = height
    while (w > maxDimension || h > maxDimension) {
        w /= 2
        h /= 2
        sampleSize *= 2
    }
    return sampleSize
}

// --- codec-M01: Android stores an ARGB_8888 Bitmap's pixels premultiplied by alpha, so
// setPixel/getPixel's R/G/B LSBs for any pixel with alpha < 255 don't survive the store/read
// round trip intact on a real device (Robolectric's ShadowBitmap stores ARGB ints verbatim and
// doesn't reproduce this, which is why it wasn't caught by CI). Bundled sample covers are opaque
// PNGs, so this only bites a Photo-Picker-selected cover with real transparency -- flattening it
// onto an opaque background at import time, once, is simpler and more robust than trying to
// disable premultiplication on every subsequent setPixel/getPixel call in ImageStegoCarrier. ---

/**
 * True if any pixel in [bitmap] has an alpha channel below fully opaque (255). Checked via one
 * bulk [Bitmap.getPixels] call (fast: a single JNI round trip) rather than per-pixel
 * `getPixel()`, since this runs on every Photo-Picker import regardless of size.
 * `Bitmap.hasAlpha()` alone isn't enough here — it's a format-level flag (an ARGB_8888 bitmap
 * decoded from a PNG with an alpha channel can report `hasAlpha() == true` even when every pixel
 * happens to be opaque), and [flattenToOpaque] only wants to flatten — and tell the operator
 * about — covers that are *actually* transparent somewhere.
 */
internal fun hasTransparency(bitmap: Bitmap): Boolean {
    if (!bitmap.hasAlpha()) return false
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return pixels.any { (it ushr 24) and 0xFF != 0xFF }
}

/**
 * Flattens any non-opaque pixels in [bitmap] onto a solid black background and returns a new,
 * fully-opaque [Bitmap] — or [bitmap] itself, unchanged, when [hasTransparency] is already false
 * (the common case: both bundled sample covers, and most real photos, have no alpha channel to
 * begin with).
 *
 * Deliberately plain per-pixel arithmetic (standard "composite over black" alpha blend:
 * `resultChannel = srcChannel * srcAlpha / 255`, since the background channel is 0) via bulk
 * [Bitmap.getPixels]/[Bitmap.setPixels] rather than a [Canvas]/`drawBitmap` composite — the
 * platform compositor would do the same math, but it also depends on `Canvas`'s own blend-mode
 * behavior being faithfully reproduced by whatever's running the code (a real device, or a test
 * environment), and this fix's whole point is to stop depending on unverified platform behavior
 * around alpha (`M-01`'s premultiplied-alpha bug was exactly that kind of gap — real, but
 * invisible to Robolectric). Doing the blend explicitly means [ImageStegoScreenTest] can verify
 * the *actual* arithmetic, not just that some `Canvas` call was made. `internal` (not `private`)
 * for that test's visibility, matching [downsampleFactor]/[encodePngBytes]'s precedent in this
 * file.
 */
internal fun flattenToOpaque(bitmap: Bitmap): Bitmap {
    if (!hasTransparency(bitmap)) return bitmap
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) {
        val pixel = pixels[i]
        val alpha = (pixel ushr 24) and 0xFF
        if (alpha == 0xFF) continue // already opaque -- leave the RGB bits exactly as they are
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        // Composite over solid black (0,0,0): resultChannel = srcChannel * srcAlpha / 255.
        // Correct even for alpha == 0 (fully transparent -> pure black, matching the visible
        // background this bitmap would actually have shown), same as a real compositor.
        val blendedR = (r * alpha) / 0xFF
        val blendedG = (g * alpha) / 0xFF
        val blendedB = (b * alpha) / 0xFF
        pixels[i] = (0xFF shl 24) or (blendedR shl 16) or (blendedG shl 8) or blendedB
    }
    val flattened = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    flattened.setPixels(pixels, 0, width, 0, 0, width, height)
    return flattened
}

// --- Task #34: save the working stego image as a PNG via MediaStore, and share it through
// Android's share sheet. ALWAYS PNG regardless of whether the cover was a bundled sample or a
// Photo-Picker-selected device photo (this file's top KDoc CRITICAL note) — PNG is lossless,
// which the LSB-embedded payload requires to survive. MediaStore.Images (not a raw filesystem
// path) is scoped storage: on minSdk 31 an app can insert its own new media without
// WRITE_EXTERNAL_STORAGE/READ_MEDIA_IMAGES, so no manifest change accompanies this section,
// same "no extra permission" posture task #33's Photo Picker cover source already established. ---

/**
 * Encodes [bitmap] as PNG bytes — the exact call [saveBitmapAsPngToMediaStore] writes to disk.
 * Factored out (rather than inlined) so a unit test can exercise "does the exact byte sequence
 * this app's save/share path produces, decoded back into a fresh [Bitmap], recover the original
 * payload through [dev.herakles.nightjar.ImageStegoCarrier.decode]" without needing a real
 * `ContentResolver`/`MediaStore` in the loop — task #34's correctness bar. `internal` (not
 * `private`) purely for that test visibility, matching `ImageSteganalysis`'s
 * `chiSquarePValueForWindow`/`chiSquareUpperTailP` and `AcousticCarrier`'s `ReedSolomon`/`GF256`
 * precedent elsewhere in this codebase.
 */
internal fun encodePngBytes(bitmap: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
        "Bitmap.compress(..., PNG, ...) reported failure"
    }
    return out.toByteArray()
}

/**
 * Writes [bitmap] as a PNG into the device's `MediaStore.Images` collection
 * (`Pictures/Nightjar/`) and returns the resulting `content://` [Uri]. Two-phase write
 * (`IS_PENDING = 1` while [encodePngBytes]'s bytes are streamed out, cleared to `0` only once
 * that succeeds) so a half-written file is never visible to the gallery or a share target
 * mid-write — the pattern Android's own scoped-storage docs recommend for API 29+ writers. On
 * any failure the half-inserted row is deleted rather than left behind as a broken/empty entry.
 *
 * Throws [IOException] on failure (insert rejected, stream open failure, write failure) so the
 * caller's `runCatching` in [ImageStegoScreen] surfaces a real reason instead of a silent no-op.
 */
private fun saveBitmapAsPngToMediaStore(context: Context, bitmap: Bitmap): Uri {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "nightjar_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Nightjar")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values)
        ?: throw IOException("MediaStore rejected the insert (collection unavailable)")

    try {
        val opened = resolver.openOutputStream(uri)?.use { stream -> stream.write(encodePngBytes(bitmap)) }
        checkNotNull(opened) { "couldn't open an output stream for $uri" }
    } catch (failure: Exception) {
        resolver.delete(uri, null, null) // don't leave a pending/broken row behind
        throw IOException("failed writing PNG bytes to MediaStore for $uri", failure)
    }

    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return uri
}

/**
 * Builds the `ACTION_SEND` share-sheet intent for a just-saved stego PNG: `image/png` MIME,
 * the MediaStore `content://` [uri] as `EXTRA_STREAM`, plus `FLAG_GRANT_READ_URI_PERMISSION` so
 * whichever app the operator picks from the chooser can read a `content://` Uri it doesn't own
 * — no `FileProvider` needed here since `MediaStore` is itself a system content provider,
 * unlike a raw `file://` path this app happened to write.
 */
private fun buildPngShareIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

// --- v6 (task W2-4, gate-30): sturdy save/share writes JPEG instead of PNG -- JPEG survival is
// the whole point of the technique (architecture.md "Sturdy image technique (v6)"; a PNG
// export would never even exercise it). Mirrors saveBitmapAsPngToMediaStore/buildPngShareIntent
// exactly, only the encode call ([encodeSturdyJpeg], not [encodePngBytes]) and MIME/extension
// differ. ---

/**
 * Writes [bitmap] as a JPEG (via [encodeSturdyJpeg], this app's `STURDY_JPEG_QUALITY`) into the
 * device's `MediaStore.Images` collection (`Pictures/Nightjar/`) and returns the resulting
 * `content://` [Uri]. Same two-phase-write/cleanup-on-failure shape as
 * [saveBitmapAsPngToMediaStore] -- see that function's KDoc.
 */
private fun saveBitmapAsJpegToMediaStore(context: Context, bitmap: Bitmap): Uri {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "nightjar_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Nightjar")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values)
        ?: throw IOException("MediaStore rejected the insert (collection unavailable)")

    try {
        val opened = resolver.openOutputStream(uri)?.use { stream -> stream.write(encodeSturdyJpeg(bitmap)) }
        checkNotNull(opened) { "couldn't open an output stream for $uri" }
    } catch (failure: Exception) {
        resolver.delete(uri, null, null) // don't leave a pending/broken row behind
        throw IOException("failed writing JPEG bytes to MediaStore for $uri", failure)
    }

    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return uri
}

/**
 * Builds the `ACTION_SEND` share-sheet intent for a just-saved sturdy JPEG: `image/jpeg` MIME,
 * otherwise identical to [buildPngShareIntent] -- see its KDoc.
 */
private fun buildJpegShareIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

// --- Previews: ImageStegoContent is pure, so these need no CovertCarrier/CovertDetector fake,
// only a small synthetic Bitmap (no resource decode / Context needed). ---

private fun previewBitmap(): Bitmap =
    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF30363D.toInt()) }

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewIdle() {
    PreviewSurface {
        ImageStegoContent(
            status = StegoStatus.Idle,
            workingBitmap = previewBitmap(),
            coverSource = CoverSource.Sample(SampleCover.GRADIENT),
            isCoverLoading = false,
            coverLoadError = null,
            onSelectSample = {},
            onPickFromDevice = {},
            payloadText = "wet-snacks-design",
            onPayloadTextChange = {},
            maxPayloadBytes = 3739,
            onEmbed = {},
            onExtract = {},
            onCheck = {},
            saveStatus = SaveStatus.Idle,
            hasEmbeddedPayload = false,
            onSave = {},
            onShare = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewEmbedded() {
    PreviewSurface {
        ImageStegoContent(
            status = StegoStatus.Embedded(payloadBytes = 18),
            workingBitmap = previewBitmap(),
            coverSource = CoverSource.Sample(SampleCover.MOSAIC),
            isCoverLoading = false,
            coverLoadError = null,
            onSelectSample = {},
            onPickFromDevice = {},
            payloadText = "wet-snacks-design",
            onPayloadTextChange = {},
            maxPayloadBytes = 3739,
            onEmbed = {},
            onExtract = {},
            onCheck = {},
            saveStatus = SaveStatus.Idle,
            hasEmbeddedPayload = true,
            onSave = {},
            onShare = {},
            onBack = {},
        )
    }
}

/**
 * Task #36: the transient "saving"/"sharing" split — before this task both actions rendered
 * "saving" here, even when the operator tapped "share".
 */
@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewSaving() {
    PreviewSurface {
        ImageStegoContent(
            status = StegoStatus.Embedded(payloadBytes = 18),
            workingBitmap = previewBitmap(),
            coverSource = CoverSource.Sample(SampleCover.GRADIENT),
            isCoverLoading = false,
            coverLoadError = null,
            onSelectSample = {},
            onPickFromDevice = {},
            payloadText = "",
            onPayloadTextChange = {},
            maxPayloadBytes = 3739,
            onEmbed = {},
            onExtract = {},
            onCheck = {},
            saveStatus = SaveStatus.Saving,
            hasEmbeddedPayload = true,
            onSave = {},
            onShare = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewSharing() {
    PreviewSurface {
        ImageStegoContent(
            status = StegoStatus.Embedded(payloadBytes = 18),
            workingBitmap = previewBitmap(),
            coverSource = CoverSource.Sample(SampleCover.GRADIENT),
            isCoverLoading = false,
            coverLoadError = null,
            onSelectSample = {},
            onPickFromDevice = {},
            payloadText = "",
            onPayloadTextChange = {},
            maxPayloadBytes = 3739,
            onEmbed = {},
            onExtract = {},
            onCheck = {},
            saveStatus = SaveStatus.Sharing,
            hasEmbeddedPayload = true,
            onSave = {},
            onShare = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewExtractedSuccess() {
    PreviewSurface {
        ImageStegoContent(
            status = StegoStatus.ExtractedSuccess(text = "the ravens have landed"),
            workingBitmap = previewBitmap(),
            coverSource = CoverSource.Sample(SampleCover.GRADIENT),
            isCoverLoading = false,
            coverLoadError = null,
            onSelectSample = {},
            onPickFromDevice = {},
            payloadText = "",
            onPayloadTextChange = {},
            maxPayloadBytes = 3739,
            onEmbed = {},
            onExtract = {},
            onCheck = {},
            saveStatus = SaveStatus.Idle,
            hasEmbeddedPayload = true,
            onSave = {},
            onShare = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewAnalyzedFlagged() {
    PreviewSurface {
        ImageStegoContent(
            status = StegoStatus.Analyzed(
                result = DetectionResult(
                    confidence = 0.98f,
                    flagged = true,
                    estimatedPayloadBytes = 18,
                    detail = "sustained PoV-equalization run: 5/32 windows (~1600 of 30000 channel samples), mean p=0.981",
                ),
            ),
            workingBitmap = previewBitmap(),
            coverSource = CoverSource.Sample(SampleCover.GRADIENT),
            isCoverLoading = false,
            coverLoadError = null,
            onSelectSample = {},
            onPickFromDevice = {},
            payloadText = "",
            onPayloadTextChange = {},
            maxPayloadBytes = 3739,
            onEmbed = {},
            onExtract = {},
            onCheck = {},
            saveStatus = SaveStatus.Idle,
            hasEmbeddedPayload = true,
            onSave = {},
            onShare = {},
            onBack = {},
        )
    }
}

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(BgBase)) {
        content()
    }
}
