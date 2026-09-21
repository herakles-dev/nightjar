package dev.herakles.nightjar.modules.acoustic

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.herakles.nightjar.AcousticCarrier
import dev.herakles.nightjar.CovertCarrier
import dev.herakles.nightjar.DebugProbe
import dev.herakles.nightjar.DecodeFailure
import dev.herakles.nightjar.DecodeResult
import dev.herakles.nightjar.MicCapture
import dev.herakles.nightjar.ModuleId
import dev.herakles.nightjar.NightjarAcoustics
import dev.herakles.nightjar.PcmAudio
import dev.herakles.nightjar.R
import dev.herakles.nightjar.WavFile
import dev.herakles.nightjar.incoming.IncomingOutcome
import dev.herakles.nightjar.incoming.IncomingPipeline
import dev.herakles.nightjar.modules.fireflyjar.FireflyRepository
import dev.herakles.nightjar.modules.fireflyjar.FireflyRecord
import dev.herakles.nightjar.modules.fireflyjar.FireflyVisual
import dev.herakles.nightjar.modules.fireflyjar.JarGlyph
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.trail.PracticeFireflies
import dev.herakles.nightjar.trail.TrailStateStore
import dev.herakles.nightjar.trail.TrailStep
import dev.herakles.nightjar.trail.trailHighlight
import dev.herakles.nightjar.ui.theme.AccentSignal
import dev.herakles.nightjar.ui.theme.BgBase
import dev.herakles.nightjar.ui.theme.BorderDefault
import dev.herakles.nightjar.ui.theme.FireflyCreated
import dev.herakles.nightjar.ui.theme.FireflyReceived
import dev.herakles.nightjar.ui.theme.JarActionCatchBorder
import dev.herakles.nightjar.ui.theme.JarActionCatchFill
import dev.herakles.nightjar.ui.theme.JarActionLookBorder
import dev.herakles.nightjar.ui.theme.JarActionLookFill
import dev.herakles.nightjar.ui.theme.JarCardFill
import dev.herakles.nightjar.ui.theme.JarMeterTrack
import dev.herakles.nightjar.ui.theme.JarTextPrimary
import dev.herakles.nightjar.ui.theme.JarTextSecondary
import dev.herakles.nightjar.ui.theme.JarTextTertiary
import dev.herakles.nightjar.ui.theme.JarType
import dev.herakles.nightjar.ui.theme.JarWatchingDim
import dev.herakles.nightjar.ui.theme.TextPrimary
import dev.herakles.nightjar.ui.theme.TextSecondary
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Task #7 — Module 3 (acoustic modem) real UI, replacing `ModuleStubScreen` for
 * [dev.herakles.nightjar.picker.Module.ACOUSTIC_MODEM].
 *
 * Written against the [CovertCarrier] interface only. Tasks #5/#6 build the concrete
 * `AcousticCarrier : CovertCarrier<PcmAudio>`. This file has zero references to that
 * class.
 *
 * Task #30 update: this screen now exposes a protocol/symbol-rate selector (real
 * two-phone testing surfaced that a settings mismatch between the two phones looks
 * exactly like an ordinary decode failure, with no indication of the actual cause).
 * `AcousticCarrier`'s `protocol`/`symbolRate` are constructor-only vals (no setter), so
 * changing the selection has to rebuild the carrier rather than mutate one in place.
 * Following the same adaptation [dev.herakles.nightjar.modules.imagestego.ImageStegoScreen]
 * already made for its per-cover-image carrier (see that file's KDoc), this screen takes
 * a factory function — `(Protocol, SymbolRate) -> CovertCarrier<PcmAudio>` — instead of a
 * single fixed carrier instance. Still zero references to the concrete `AcousticCarrier`
 * class; [NightjarAcoustics.Protocol]/[NightjarAcoustics.SymbolRate] are the shared,
 * carrier-agnostic DSP-parameter types, not the codec itself.
 *
 * Wiring note: `MainActivity.NightjarApp()`'s `Screen.AcousticModem` branch calls
 * `AcousticModemScreen(carrierFactory = { protocol, symbolRate -> AcousticCarrier(protocol
 * = protocol, symbolRate = symbolRate) }, onBack = ...)`.
 *
 * Scope boundary (architecture.md "Module Interface" §4): [CovertCarrier] is a pure
 * codec over in-memory buffers, not the audio transport. This screen owns that
 * transport — `AudioTrack` playback for transmit, `AudioRecord` capture for listen —
 * since architecture.md draws that line at the UI/transport layer.
 *
 * Task #31 update: "save" and "share" actions sit next to "transmit". Both run the same
 * `carrier.encode(...)` step transmit does (independently — neither requires transmit to have
 * run first), then hand the resulting [PcmAudio] to [dev.herakles.nightjar.WavFile] to build a
 * standard RIFF/WAVE PCM16 file, then write it via `MediaStore.Audio` (scoped storage, no legacy
 * `WRITE_EXTERNAL_STORAGE` permission — this app's minSdk is already 31). "share" additionally
 * opens Android's share sheet (`Intent.ACTION_SEND`) on the resulting MediaStore `content://` Uri.
 *
 * Task #32 update: an "import" action sits next to "listen" — the two ways to feed
 * `carrier.decode(...)` a captured signal, live mic capture vs. an existing audio file already on
 * the device (`ActivityResultContracts.OpenDocument`, any `audio/`-prefixed mime type). WAV files are parsed
 * directly via [dev.herakles.nightjar.WavFile.decodePcm16] (the inverse of task #31's encoder);
 * anything else is decoded to PCM via `MediaExtractor`/`MediaCodec`. Either path can hand back
 * audio at a sample rate/channel count other than [NightjarAcoustics.SAMPLE_RATE_HZ] mono, which
 * `decode()` assumes — rather than resample (real DSP work, out of scope here), a mismatch is
 * rejected up front with a message naming both the actual and required format
 * ([ModemStatus.ImportFailed]), before ever reaching `carrier.decode(...)`.
 */

/**
 * The 7 states the task asked for, one-to-one.
 *
 * Task #27: [Listening] now carries the two pieces of live feedback a real two-phone test
 * showed were missing — [Listening.levelDb] (proof the mic is actually capturing something)
 * and [Listening.remainingSeconds] (how long the bounded capture window has left). Both are
 * nullable/present-from-the-start rather than added as separate screen-level state, matching
 * how [DecodedSuccess]/[DecodedFailure] already carry exactly what they need to render.
 * [DecodedFailure.timedOut] similarly threads through whether the capture window ran to
 * [MAX_LISTEN_SECONDS] with nothing decoded, vs. an early manual stop or a corrupted-but-present
 * signal — see [failureMessage].
 */
sealed interface ModemStatus {
    data object Idle : ModemStatus
    data object Encoding : ModemStatus
    data object Transmitting : ModemStatus
    data class Listening(val levelDb: Double?, val remainingSeconds: Double) : ModemStatus

    /** Task #32: reading/parsing a picked audio file, before the recovered PCM reaches `decode()`. */
    data object Importing : ModemStatus
    data object Decoding : ModemStatus
    data class DecodedSuccess(val text: String, val correctedByteErrors: Int) : ModemStatus
    data class DecodedFailure(
        val reason: DecodeFailure,
        val detail: String?,
        val timedOut: Boolean = false,
    ) : ModemStatus

    /**
     * Task #32: the picked file couldn't be turned into `carrier.decode()`'s expected input —
     * unreadable/unparseable file, or a real sample-rate/channel-count mismatch (message names
     * both the actual and required format). Distinct from [DecodedFailure], which is specifically
     * `carrier.decode()` rejecting a well-formed 48kHz-mono PCM buffer; this is a transport/format
     * problem that never reaches `decode()` at all.
     */
    data class ImportFailed(val message: String) : ModemStatus

    /**
     * mic-2 fix: [AcousticModemController.startListening]'s AudioRecord failed to initialize
     * (every [MicCapture.openBestAudioRecord] tier busy/unavailable), or `startRecording()` threw
     * once initialized — surfaced as a plain "mic busy" message instead of the uncaught
     * IllegalStateException this used to crash on. Distinct from [ImportFailed] (a transport/
     * format problem with a *picked file*, never touching the mic) and from [DecodedFailure] (a
     * well-formed capture that simply didn't decode).
     */
    data class ListenFailed(val message: String) : ModemStatus
}

/**
 * Longest a "listen" capture window runs before it auto-stops and attempts a decode.
 * `decode(carrier: PcmAudio)` is one-shot over a complete buffer (not streaming), so the
 * transport has to capture a bounded window first. 20s covers roughly 3x the ~7s a
 * 64-byte payload needs at the architecture-recommended NORMAL symbol rate (architecture.md
 * §3, §5 "Recommended gate-2 demo payload ≤ 64 bytes"), with margin for the sender not
 * starting immediately. The user can also stop early by tapping "stop".
 */
private const val MAX_LISTEN_SECONDS = 20.0

/**
 * Task #27: floor for the live input-level readout, in dBFS relative to full-scale PCM16
 * (0 dB = digital clipping). Real room noise floors on phone mics rarely read below this, and
 * clamping avoids the readout swinging to a meaningless -140 dB during true digital silence
 * (e.g. a `chunk` of literal zeros, which log10 would otherwise send to -Infinity).
 */
private const val SILENCE_FLOOR_DB = -60.0

/**
 * Task #27: read chunk size for the listen-capture loop, in units of [NightjarAcoustics
 * .FRAME_SAMPLES]. Matches [dev.herakles.nightjar.modules.detector.DetectorController]'s own
 * ~85ms throttle exactly (4 frames @ 48kHz/1024 samples ≈ 85ms) — the level readout and
 * countdown only need to feel live, not track every ~21ms analysis frame, and reusing the
 * detector's established cadence keeps the two listening-loop implementations consistent
 * rather than picking a new number.
 */
private const val LEVEL_UPDATE_CHUNK_FRAMES = 4

/**
 * Stateful root: owns the [AcousticModemController], the payload text field, and the
 * RECORD_AUDIO permission flow. [AcousticModemContent] below is the pure/previewable UI.
 */
@Composable
fun AcousticModemScreen(
    carrierFactory: (NightjarAcoustics.Protocol, NightjarAcoustics.SymbolRate) -> CovertCarrier<PcmAudio>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    var protocol by remember { mutableStateOf(NightjarAcoustics.Protocol.AUDIBLE) }
    var symbolRate by remember { mutableStateOf(NightjarAcoustics.SymbolRate.NORMAL) }

    // Task #30: AcousticCarrier's protocol/symbolRate are constructor-only vals with no
    // setter (AcousticCarrier.kt), so a selection change rebuilds the carrier via
    // carrierFactory rather than mutating one in place. Keying this `remember` on
    // protocol/symbolRate (not just carrierFactory, which never itself changes identity)
    // is what makes a selector change actually take effect — omitting those keys would
    // run this block once and every later selection change would silently keep
    // encoding/decoding against the original carrier's settings.
    val carrier = remember(carrierFactory, protocol, symbolRate) { carrierFactory(protocol, symbolRate) }
    // Keyed on `carrier` (which changes identity whenever protocol/symbolRate change), so
    // a selection change also rebuilds the controller and its status resets to Idle.
    val controller = remember(carrier) { AcousticModemController(carrier, context.applicationContext) }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    // mic-5: stop in-flight listen capture / transmit playback when the app is backgrounded,
    // rather than leaving the mic capturing or the speaker playing behind a closed/minimized app.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        controller.stopForBackground()
    }

    var payloadText by remember { mutableStateOf("") }
    var micPermissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            micPermissionDenied = false
            controller.startListening()
        } else {
            micPermissionDenied = true
        }
    }

    // Task #32: "import" picks an existing audio file (mime audio/*) via Storage Access
    // Framework and runs it through the same decode() pipeline "listen" uses. No RECORD_AUDIO (or
    // any other runtime) permission needed — SAF grants read access to whatever the operator
    // selects in the system picker.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            controller.importAndDecode(uri)
        }
    }

    AcousticModemContent(
        status = controller.status,
        payloadText = payloadText,
        onPayloadTextChange = { payloadText = it },
        maxPayloadBytes = carrier.maxPayloadBytes,
        micPermissionDenied = micPermissionDenied,
        protocol = protocol,
        onProtocolChange = { protocol = it },
        symbolRate = symbolRate,
        onSymbolRateChange = { symbolRate = it },
        onTransmit = { controller.transmit(payloadText) },
        onSave = { controller.saveToDevice(payloadText) },
        onShare = { controller.shareFromDevice(payloadText) },
        fileActionMessage = controller.fileActionMessage,
        fileActionBusyLabel = controller.fileActionBusyLabel,
        onImport = { importLauncher.launch(arrayOf("audio/*")) },
        onToggleListen = {
            if (controller.status is ModemStatus.Listening) {
                controller.stopListening()
            } else {
                val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) {
                    micPermissionDenied = false
                    controller.startListening()
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        },
        onBack = onBack,
    )
}

/**
 * Task #11 — the real jar-framed flow for [dev.herakles.nightjar.picker.Module.ACOUSTIC_MODEM]
 * ("the singing jar" — [dev.herakles.nightjar.picker.JarRole.CREATION]), landed on task #9's
 * stub. A themed skin over the exact same [AcousticCarrier]/[AcousticModemController] round-trip
 * [AcousticModemScreen] drives above (design/screen-flow.md § Screen 7) — this does not
 * reimplement encode/transmit/listen/decode, it re-presents it with softened copy and this
 * flow's own jar-palette UI ([JarModemFlowContent] and its private helpers below). "catch a
 * firefly" is [AcousticModemController.transmit]; "look for fireflies" is
 * [AcousticModemController.startListening]/[AcousticModemController.stopListening] — the same
 * two verbs [AcousticModemContent] exposes as "transmit"/"listen", gate-13's softened framing.
 *
 * Unlike [AcousticModemScreen], [jarCatchFlow]'s signature (fixed by task #9's dispatcher,
 * `JarCatchFlows.kt`) takes no `carrierFactory` — this is the one place in this file that
 * constructs [AcousticCarrier] directly, since there's no outer caller to hand one in.
 *
 * [onExit] is accepted (to match the dispatcher's uniform per-module signature) but never
 * invoked from in here: [dev.herakles.nightjar.modules.fireflyjar.JarDetailContent], the shell
 * that hosts this flow inline below its own title/swarm, already renders a persistent
 * "back to the shelf" row above all of that wired to the identical callback — a second exit
 * affordance in here would just duplicate it.
 *
 * Logs a [FireflyRecord] the moment a catch/look round-trip actually completes, by watching
 * [AcousticModemController.status] transitions rather than adding a completion-callback hook to
 * that class: `Transmitting -> Idle` only happens via a completed `playPcm()` call (an
 * oversized-payload rejection returns to `Idle` straight from `Encoding`, never passing through
 * `Transmitting` — moot here anyway since [JarModemFlowContent]'s `canCatch` gate matches
 * [AcousticModemContent]'s `canTransmit` and disables the row over budget), and
 * [ModemStatus.DecodedSuccess] is itself a terminal, one-shot state.
 *
 * Task #17 (design refresh) re-skins everything below against
 * `sessions/nightjar/artifacts/design-refresh/DESIGN_SPEC.md` §5 screens 1d/1e — the catch flow
 * gets its own small jar preview, gold input box, and gradient "send" button; the listen flow
 * gets a cyan listening card with a real level meter and a "you spotted one" result card. This
 * function's own state/controller wiring is untouched; only [JarModemFlowContent] and its
 * private helpers below changed.
 *
 * v6 addition (task W2-1, gate-31): a third row, "catch from a photo or file"
 * (design/screen-flow.md's v6 "Receiving" section), opens the system document picker
 * (`ActivityResultContracts.OpenDocument`, any audio MIME type -- this jar's own carrier is
 * audio; no permission added, INV-10) and routes the picked `Uri` through
 * [IncomingPipeline.route] -- the same routing `MainActivity.kt` uses for a share-sheet/
 * open-with `Intent`, never duplicated here. [onIncomingOutcome] hands the resulting
 * [IncomingOutcome] back up to `MainActivity.kt` (via `JarDetailScreen`/`catchFlowFor`) to
 * navigate to `Screen.Incoming`.
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

    // v6 (task W2-1, gate-31): "catch from a photo or file". A null Uri means the operator
    // backed out of the picker -- no-op, same as every other picker launcher in this app.
    val catchFromFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                IncomingPipeline.route(context, uri, action = "PICKER")
            }
            onIncomingOutcome(outcome)
        }
    }

    var protocol by remember { mutableStateOf(NightjarAcoustics.Protocol.AUDIBLE) }
    var symbolRate by remember { mutableStateOf(NightjarAcoustics.SymbolRate.NORMAL) }
    val carrier = remember(protocol, symbolRate) {
        AcousticCarrier(protocol = protocol, symbolRate = symbolRate)
    }
    val controller = remember(carrier) { AcousticModemController(carrier, context.applicationContext) }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    // mic-5: stop in-flight listen capture / transmit playback when the app is backgrounded,
    // rather than leaving the mic capturing or the speaker playing behind a closed/minimized app.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        controller.stopForBackground()
    }

    var payloadText by remember { mutableStateOf("") }
    var micPermissionDenied by remember { mutableStateOf(false) }
    var catchExpanded by remember { mutableStateOf(false) }
    var catchResultMessage: String? by remember { mutableStateOf(null) }

    // W2-3 riddle trail (design/riddle-trail.md § Step 3, gate-36): while this jar's step is the
    // active one, "look for fireflies" decodes the bundled practice WAV via
    // [AcousticModemController.importAndDecode] -- the same "feed an existing signal to decode()"
    // path the technical screen's own "import" action already establishes -- instead of a live
    // speaker/mic round trip. [pendingPracticeCatch] doubles as the "was this a practice catch"
    // signal for the DecodedSuccess handler below; reset on every look/listen dispatch.
    val trailState by trailStore.state.collectAsState()
    val trailActive = trailState.currentStep == TrailStep.SINGING
    var pendingPracticeCatch by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            micPermissionDenied = false
            controller.startListening()
        } else {
            micPermissionDenied = true
        }
    }

    val status = controller.status
    var previousStatus by remember { mutableStateOf<ModemStatus>(ModemStatus.Idle) }

    // Task #19 (gate-17), Stage B: persist the carrier WAV alongside the FireflyRecord at both
    // catch sites below. Mirrors ImageStegoScreen.kt's/AudioStegoScreen.kt's `jarCatchFlow` shape
    // (tasks #17/#18): a local suspend helper called from inside the existing transition-guarded
    // arms, WAV encode pushed off the composition (Main) dispatcher via
    // `withContext(Dispatchers.Default)` (encoding a full 20s capture -- ~1.9 MB, the largest
    // media this app writes -- inline in the LaunchedEffect body would block composition). Unlike
    // the other two screens, this one has no single controller-held "current media" property that
    // both directions can share: CREATED wants [AcousticModemController.lastTransmittedPcm] and
    // RECEIVED wants [AcousticModemController.lastDecodedPcm], so [pcm] is a parameter rather than
    // read from a fixed controller field. Falls back to the existing media-less insert() when
    // there's no PCM (nothing transmitted/decoded yet) or it's empty -- never writes a zero-byte
    // file.
    suspend fun insertFireflyWithCarrier(record: FireflyRecord, pcm: PcmAudio?): Long {
        if (pcm == null || pcm.isEmpty()) {
            return repository.insert(record)
        }
        val wavBytes = withContext(Dispatchers.Default) {
            WavFile.encodePcm16Mono(pcm, NightjarAcoustics.SAMPLE_RATE_HZ)
        }
        return repository.insertWithMedia(record.copy(carrierKind = "AUDIO"), wavBytes, "wav")
    }

    // Keyed on the status's class rather than the full value: ModemStatus.Listening carries a
    // levelDb/remainingSeconds pair that changes on every ~85ms captured chunk (200+ instances
    // over a full listen window), and neither branch below needs to observe that churn — only
    // the *kind* of status actually changing.
    LaunchedEffect(status::class) {
        if (status is ModemStatus.Idle && previousStatus is ModemStatus.Transmitting) {
            val bytes = payloadText.encodeToByteArray().size
            insertFireflyWithCarrier(
                FireflyRecord(
                    moduleId = Module.ACOUSTIC_MODEM.name,
                    direction = "CREATED",
                    timestampMillis = System.currentTimeMillis(),
                    payloadSizeBytes = bytes,
                    technique = null,
                    payloadPreview = payloadText.take(40),
                ),
                controller.lastTransmittedPcm,
            )
            catchResultMessage = "you caught one — $bytes bytes"
        } else if (status is ModemStatus.DecodedSuccess) {
            // W2-3 (gate-36): AcousticModemController.importAndDecode (the practice-decode path
            // below) sets lastDecodedPcm to the same PCM it actually decoded before this status
            // is ever reached, whether the decode came from a live listen or a practice import --
            // no override needed here, unlike the art/humming jars' own working-bitmap/-audio
            // ambiguity.
            val bytes = status.text.encodeToByteArray().size
            val wasPractice = pendingPracticeCatch
            val id = insertFireflyWithCarrier(
                FireflyRecord(
                    moduleId = Module.ACOUSTIC_MODEM.name,
                    direction = "RECEIVED",
                    timestampMillis = System.currentTimeMillis(),
                    payloadSizeBytes = bytes,
                    technique = null,
                    payloadPreview = status.text.take(40),
                ),
                controller.lastDecodedPcm,
            )
            if (wasPractice) {
                trailStore.markPractice(id)
                trailStore.advance(TrailStep.SINGING)
            }
            pendingPracticeCatch = false
        }
        previousStatus = status
    }

    JarModemFlowContent(
        status = status,
        catchExpanded = catchExpanded,
        onToggleCatch = { catchExpanded = !catchExpanded },
        payloadText = payloadText,
        onPayloadTextChange = { payloadText = it },
        maxPayloadBytes = carrier.maxPayloadBytes,
        protocol = protocol,
        onProtocolChange = { protocol = it },
        symbolRate = symbolRate,
        onSymbolRateChange = { symbolRate = it },
        onCatch = { controller.transmit(payloadText) },
        catchResultMessage = catchResultMessage,
        micPermissionDenied = micPermissionDenied,
        onToggleLook = {
            val practiceFile = PracticeFireflies.practiceFile(context, PracticeFireflies.Jar.SINGING)
            if (trailActive && practiceFile.exists()) {
                pendingPracticeCatch = true
                controller.importAndDecode(Uri.fromFile(practiceFile))
            } else if (controller.status is ModemStatus.Listening) {
                pendingPracticeCatch = false
                controller.stopListening()
            } else {
                // Either the trail isn't pointing at this jar right now, or it is but the
                // practice file hasn't finished generating yet -- either way this is an ordinary
                // live listen, same as before the trail existed.
                pendingPracticeCatch = false
                val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) {
                    micPermissionDenied = false
                    controller.startListening()
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        },
        lookForFirefliesHighlighted = trailActive,
        onCatchFromFile = { catchFromFileLauncher.launch(arrayOf("audio/*")) },
    )
}

/**
 * Pure UI for [jarCatchFlow]: no [FireflyDao], no carrier, no permission logic — same
 * stateful-root/pure-content split every screen in this app uses. Two clusters: "catch a
 * firefly" expands inline into a small jar preview, payload field, protocol/symbol-rate
 * selector, and a "send" button when tapped (the same fields [AcousticModemContent] defines
 * for this module, per gate-13 — nothing here is a second implementation of them); "look for
 * fireflies" swaps for a cyan listening card the moment [status] actually is
 * [ModemStatus.Listening], per DESIGN_SPEC.md §5 screens 1d/1e. Jar palette throughout
 * ([JarTextPrimary]/[JarTextTertiary]/[FireflyCreated]/[FireflyReceived]) rather than this
 * file's technical [TextPrimary]/[TextSecondary]/[AccentSignal] — this content sits inside
 * [dev.herakles.nightjar.modules.fireflyjar.JarDetailContent]'s dusk/horizon gradient shell, not
 * this screen's own [BgBase].
 */
@Composable
private fun JarModemFlowContent(
    status: ModemStatus,
    catchExpanded: Boolean,
    onToggleCatch: () -> Unit,
    payloadText: String,
    onPayloadTextChange: (String) -> Unit,
    maxPayloadBytes: Int,
    protocol: NightjarAcoustics.Protocol,
    onProtocolChange: (NightjarAcoustics.Protocol) -> Unit,
    symbolRate: NightjarAcoustics.SymbolRate,
    onSymbolRateChange: (NightjarAcoustics.SymbolRate) -> Unit,
    onCatch: () -> Unit,
    catchResultMessage: String?,
    micPermissionDenied: Boolean,
    onToggleLook: () -> Unit,
    // W2-3 (gate-36/gate-38): true while this jar's trail step is the active one -- glows the
    // "look for fireflies" row. Default keeps every existing @Preview call site compiling
    // unchanged, same "pure/previewable" reasoning this file's other optional params follow.
    lookForFirefliesHighlighted: Boolean = false,
    onCatchFromFile: () -> Unit,
) {
    val idleEquivalent = status is ModemStatus.Idle ||
        status is ModemStatus.DecodedSuccess ||
        status is ModemStatus.DecodedFailure ||
        status is ModemStatus.ListenFailed
    val payloadBytes = payloadText.encodeToByteArray().size
    val canCatch = idleEquivalent && payloadText.isNotEmpty() && payloadBytes <= maxPayloadBytes
    val canToggleLook = idleEquivalent || status is ModemStatus.Listening

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JarFlowRow(
                label = "catch a firefly",
                accent = FireflyCreated,
                fill = JarActionCatchFill,
                border = JarActionCatchBorder,
                enabled = idleEquivalent,
                onClick = onToggleCatch,
            )
            if (catchExpanded) {
                JarCatchExpanded(
                    payloadText = payloadText,
                    onPayloadTextChange = onPayloadTextChange,
                    payloadBytes = payloadBytes,
                    maxPayloadBytes = maxPayloadBytes,
                    protocol = protocol,
                    onProtocolChange = onProtocolChange,
                    symbolRate = symbolRate,
                    onSymbolRateChange = onSymbolRateChange,
                    enabled = idleEquivalent,
                    canCatch = canCatch,
                    onCatch = onCatch,
                )
            }
        }

        if (status is ModemStatus.Listening) {
            JarListeningCard(status = status, onStop = onToggleLook)
        } else {
            JarFlowRow(
                label = "look for fireflies",
                accent = FireflyReceived,
                fill = JarActionLookFill,
                border = JarActionLookBorder,
                enabled = canToggleLook,
                onClick = onToggleLook,
                highlighted = lookForFirefliesHighlighted,
            )
        }

        // v6 (task W2-1, gate-31): "catch from a photo or file" -- last in the action group, per
        // design/screen-flow.md's v6 wireframe. Cyan "receiving" tint, same as "look for
        // fireflies" -- this row can land a firefly in ANY jar, not necessarily this one.
        JarFlowRow(
            label = stringResource(R.string.receive_catch_from_file_row),
            accent = FireflyReceived,
            fill = JarActionLookFill,
            border = JarActionLookBorder,
            enabled = idleEquivalent,
            onClick = onCatchFromFile,
        )

        if (micPermissionDenied) {
            Text(
                text = "needs the microphone to look for fireflies.",
                style = JarType.Body,
                color = JarTextSecondary,
            )
        }

        JarModemStatusBlock(status = status, catchResultMessage = catchResultMessage)
    }
}

/** One tappable jar-flow row (DESIGN_SPEC.md §5 1d/1e screen titles doubling as this app's
 *  single-screen entry point — no separate navigation exists, so the row IS the title). [fill]/
 *  [border] are the module's own accent-tinted tokens ([JarActionCatchFill]/[JarActionLookFill]
 *  and their border twins) — never a new inline color. */
@Composable
private fun JarFlowRow(
    label: String,
    accent: Color,
    fill: Color,
    border: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    // W2-3: glows this row as the trail's current target (trail/TrailHighlight.kt) -- a no-op
    // Modifier when false, so every existing call site keeps its default styling untouched.
    highlighted: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .border(width = 1.dp, color = border, shape = RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .trailHighlight(active = highlighted, description = label)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = JarType.ActionTitle,
            color = if (enabled) accent else JarTextTertiary,
        )
    }
}

/**
 * DESIGN_SPEC.md §5 1d — the expanded "catch a firefly" flow: a small jar preview, the payload
 * field, the (preserved, restyled) protocol/symbol-rate selector, and the "send" button. Same
 * fields [AcousticModemContent] defines for this module, per gate-13 — nothing here is a second
 * implementation of them.
 */
@Composable
private fun JarCatchExpanded(
    payloadText: String,
    onPayloadTextChange: (String) -> Unit,
    payloadBytes: Int,
    maxPayloadBytes: Int,
    protocol: NightjarAcoustics.Protocol,
    onProtocolChange: (NightjarAcoustics.Protocol) -> Unit,
    symbolRate: NightjarAcoustics.SymbolRate,
    onSymbolRateChange: (NightjarAcoustics.SymbolRate) -> Unit,
    enabled: Boolean,
    canCatch: Boolean,
    onCatch: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            JarGlyph(
                module = Module.ACOUSTIC_MODEM,
                fireflies = listOf(FireflyVisual(id = 10, color = FireflyCreated)),
                modifier = Modifier.size(width = 80.dp, height = 96.dp),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "your message", style = JarType.SectionLabel, color = JarTextTertiary)
            BasicTextField(
                value = payloadText,
                onValueChange = onPayloadTextChange,
                singleLine = true,
                enabled = enabled,
                textStyle = JarType.Body.copy(color = JarTextPrimary),
                cursorBrush = SolidColor(JarTextPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(JarCardFill)
                    .border(width = 1.dp, color = JarActionCatchBorder, shape = RoundedCornerShape(8.dp))
                    .padding(12.dp),
                decorationBox = { innerTextField ->
                    if (payloadText.isEmpty()) {
                        Text(text = "what do you want to send?", style = JarType.Body, color = JarTextTertiary)
                    }
                    innerTextField()
                },
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Text(
                    text = "$payloadBytes / $maxPayloadBytes bytes",
                    style = JarType.MetaLabel,
                    color = JarTextTertiary,
                )
            }
        }

        JarProtocolAndRateSelector(
            protocol = protocol,
            onProtocolChange = onProtocolChange,
            symbolRate = symbolRate,
            onSymbolRateChange = onSymbolRateChange,
            enabled = enabled,
        )

        JarSendButton(enabled = canCatch, onClick = onCatch)
    }
}

/** DESIGN_SPEC.md §5 1d's gold gradient "send" button — the gradient/border alphas are derived
 *  from [FireflyCreated] via `.copy(alpha = …)`, never a new named color. */
@Composable
private fun JarSendButton(enabled: Boolean, onClick: () -> Unit) {
    val strength = if (enabled) 1f else 0.4f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        FireflyCreated.copy(alpha = 0.15f * strength),
                        FireflyCreated.copy(alpha = 0.08f * strength),
                    ),
                ),
            )
            .border(width = 1.dp, color = FireflyCreated.copy(alpha = 0.25f * strength), shape = RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = "send", style = JarType.ButtonLabel, color = FireflyCreated.copy(alpha = strength))
        Text(text = "plays sound from speaker", style = JarType.Footer, color = JarTextSecondary)
    }
}

/**
 * Jar-palette equivalent of this file's own technical [ChannelSettingsBlock] — same
 * protocol/symbol-rate fields and [protocolLabel]/[symbolRateLabel] copy, just re-colored for the
 * jar shell and with its mismatch note softened into jar voice. Not part of DESIGN_SPEC.md's 1d
 * mockup (which has no home for this control) — preserved per this task's own instruction to keep
 * existing controls the spec's layout doesn't show, restyled rather than deleted.
 */
@Composable
private fun JarProtocolAndRateSelector(
    protocol: NightjarAcoustics.Protocol,
    onProtocolChange: (NightjarAcoustics.Protocol) -> Unit,
    symbolRate: NightjarAcoustics.SymbolRate,
    onSymbolRateChange: (NightjarAcoustics.SymbolRate) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "protocol", style = JarType.SectionLabel, color = JarTextTertiary)
            NightjarAcoustics.Protocol.entries.forEach { option ->
                JarOptionRow(
                    label = protocolLabel(option),
                    selected = option == protocol,
                    enabled = enabled,
                    onClick = { onProtocolChange(option) },
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "symbol rate", style = JarType.SectionLabel, color = JarTextTertiary)
            NightjarAcoustics.SymbolRate.entries.forEach { option ->
                JarOptionRow(
                    label = symbolRateLabel(option),
                    selected = option == symbolRate,
                    enabled = enabled,
                    onClick = { onSymbolRateChange(option) },
                )
            }
        }
        Text(
            text = "both phones need to match here, or the other one won't see your light.",
            style = JarType.Footer,
            color = JarTextSecondary,
        )
    }
}

/** One selectable protocol/symbol-rate option row, jar-palette twin of this file's [SettingOptionRow]. */
@Composable
private fun JarOptionRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = JarType.Body,
            color = if (selected) JarTextPrimary else JarTextTertiary,
        )
    }
}

/**
 * Jar-palette, jar-copy twin of this file's own technical [StatusBlock]. [catchResultMessage]
 * (set by [jarCatchFlow] the moment a catch round-trip completes — [ModemStatus] itself has no
 * persisted "encode succeeded" state, unlike e.g. `ImageStegoScreen`'s `Embedded`) renders gold —
 * firefly-jar-identity.md's doctrine reversal explicitly allows a success color here, unlike this
 * file's own technical screen. [ModemStatus.Listening] is handled by [JarListeningCard] one level
 * up, not here, so it's `Unit` in this `when`. [ModemStatus.Importing]/[ModemStatus.ImportFailed]
 * used to never occur in this flow at all (no import affordance — "look for fireflies" was
 * listen-only); W2-3's riddle trail now briefly passes through [ModemStatus.Importing] (and, on
 * a read failure, [ModemStatus.ImportFailed]) while the singing jar's step is active and "look
 * for fireflies" decodes the bundled practice WAV via [AcousticModemController.importAndDecode]
 * instead of a live listen (design/riddle-trail.md § Step 3). Both stay `Unit`/near-silent here
 * on purpose — the practice decode is near-instant for a few-second clip, and [ModemStatus
 * .Decoding] right after it already shows "reading the light" — so no new copy was added for
 * either state.
 */
@Composable
private fun JarModemStatusBlock(status: ModemStatus, catchResultMessage: String?) {
    when (status) {
        is ModemStatus.Idle -> if (catchResultMessage != null) {
            Text(text = catchResultMessage, style = JarType.SectionLabel, color = FireflyCreated)
        } else {
            Text(text = "ready to catch", style = JarType.Footer, color = JarWatchingDim)
        }
        is ModemStatus.Encoding -> JarStatusWord("warming up the light")
        is ModemStatus.Transmitting -> JarStatusWord("sending the glow", color = FireflyCreated)
        is ModemStatus.Listening -> Unit
        is ModemStatus.Importing -> Unit
        is ModemStatus.Decoding -> JarStatusWord("reading the light")
        is ModemStatus.DecodedSuccess -> JarCatchResultCard(status)
        is ModemStatus.DecodedFailure -> Text(
            text = jarFailureMessage(status.reason),
            style = JarType.Body,
            color = JarTextSecondary,
        )
        is ModemStatus.ImportFailed -> Unit
        is ModemStatus.ListenFailed -> Text(
            text = status.message,
            style = JarType.Body,
            color = JarTextSecondary,
        )
    }
}

@Composable
private fun JarStatusWord(word: String, color: Color = JarTextSecondary) {
    Text(text = word, style = JarType.SectionLabel, color = color)
}

/** DESIGN_SPEC.md §5 1e's "you spotted one" result card — reuses [JarActionLookFill]/
 *  [JarActionLookBorder] rather than a new token, same cyan the listening card itself uses. The
 *  byte count and correction count both always render (the mockup's own example, "22 bytes · 0
 *  corrected", shows the zero case rather than hiding it). */
@Composable
private fun JarCatchResultCard(status: ModemStatus.DecodedSuccess) {
    val bytes = status.text.encodeToByteArray().size
    val plural = if (status.correctedByteErrors == 1) "" else "s"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(JarActionLookFill)
            .border(width = 1.dp, color = JarActionLookBorder, shape = RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = "you spotted one", style = JarType.SectionLabel, color = FireflyReceived)
        Text(text = status.text, style = JarType.Body, color = JarTextPrimary)
        Text(
            text = "$bytes bytes · ${status.correctedByteErrors} byte$plural corrected",
            style = JarType.MetaLabel,
            color = JarTextTertiary,
        )
    }
}

/**
 * DESIGN_SPEC.md §5 1e — the cyan listening card: a small jar preview, the "listening"/countdown
 * row, a real level meter (never the mockup's sine-wave placeholder, §4.8 — [status.levelDb] is
 * the actual captured level), and the "stop" button. Jar-palette twin of this file's own
 * technical [ListeningBlock]; `levelDb` is remapped from dBFS ([SILENCE_FLOOR_DB]..0) onto the
 * same plain 0-100 "strength" convention the watching jar (detector) already uses for its
 * confidence readout, rather than surfacing a raw dB number.
 */
@Composable
private fun JarListeningCard(status: ModemStatus.Listening, onStop: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(JarActionLookFill)
            .border(width = 1.dp, color = JarActionLookBorder, shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            JarGlyph(
                module = Module.ACOUSTIC_MODEM,
                fireflies = listOf(FireflyVisual(id = 11, color = FireflyReceived)),
                modifier = Modifier.size(width = 80.dp, height = 96.dp),
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "listening", style = JarType.SectionLabel, color = FireflyReceived)
            val remaining = status.remainingSeconds.roundToInt().coerceAtLeast(0)
            Text(text = "${remaining}s left", style = JarType.MetaLabel, color = JarTextSecondary)
        }

        val glowStrength = status.levelDb?.let { db ->
            (((db - SILENCE_FLOOR_DB) / -SILENCE_FLOOR_DB) * 100).roundToInt().coerceIn(0, 100)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "glow strength", style = JarType.MetaLabel, color = JarTextTertiary)
                Text(
                    text = glowStrength?.toString() ?: "waiting",
                    style = JarType.MetaLabel,
                    color = JarTextTertiary,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(JarMeterTrack),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = (glowStrength ?: 0) / 100f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(FireflyReceived.copy(alpha = 0.25f), FireflyReceived),
                            ),
                        ),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(FireflyReceived.copy(alpha = 0.10f))
                .border(width = 1.dp, color = FireflyReceived.copy(alpha = 0.20f), shape = RoundedCornerShape(12.dp))
                .clickable(onClick = onStop)
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "stop", style = JarType.ButtonLabel, color = FireflyReceived)
        }
    }
}

/** Softened [DecodeFailure] copy, verbatim from screen-flow.md § Screen 7's copy-mapping table. */
private fun jarFailureMessage(reason: DecodeFailure): String = when (reason) {
    DecodeFailure.NO_PAYLOAD_FOUND -> "nothing's glowing in here right now."
    DecodeFailure.HEADER_INVALID -> "that light doesn't look right — probably not one of yours."
    DecodeFailure.PAYLOAD_TOO_LARGE -> "too big to fit in the jar."
    DecodeFailure.UNRECOVERABLE_FEC -> "the light faded before it got here."
    DecodeFailure.INTEGRITY_MISMATCH -> "it flickered wrong on the way — the message got scrambled."
}

/**
 * Pure UI: no side effects, no audio, no permission logic. Dense single column, no
 * cards, no icons. As of Task #17, [AccentSignal] appears on exactly one thing on this
 * screen — the `transmitting`/`listening` status word, the moment the speaker or mic is
 * physically carrying the covert signal. Everything else (payload field, buttons,
 * decoded text) stays TextPrimary/TextSecondary only.
 *
 * Task #28: the title now carries a persistent one-line note ("works best within 1m,
 * in a quiet room.") — real feedback said the app gave no idea what distance to use.
 * The number is the architecture.md §7 round-trip envelope for the AUDIBLE protocol
 * (the default, see [dev.herakles.nightjar.NightjarAcoustics.Protocol]): speaker→mic
 * distance ≤1.0m, ambient noise <45 dBA. Deliberately a static line under the title,
 * not folded into [ListeningBlock] — it's setup guidance true before, during, and
 * after a listen window, not a live reading, so it stays put rather than only
 * appearing once "listen" is tapped.
 *
 * Task #30: [ChannelSettingsBlock] sits directly above the transmit/listen action rows —
 * the protocol/symbol-rate selection is setup guidance for those two specific controls
 * (not a live reading, so it doesn't belong in [StatusBlock]), and real two-phone testing
 * found a settings mismatch between phones otherwise looks exactly like an ordinary decode
 * failure with no visible cause. Disabled together with the payload field whenever
 * [idleEquivalent] is false, so a transmit/listen/decode cycle can't have its carrier
 * settings changed out from under it mid-flight.
 *
 * Task #31: "save"/"share" sit directly under "transmit", sharing its `canTransmit` gate —
 * both write the encoded payload to a `.wav` file (MediaStore), "share" additionally opening
 * the system share sheet on it. [fileActionMessage] renders their outcome underneath the
 * action rows, same TextSecondary/labelSmall vocabulary as everything else on this screen.
 *
 * Task #32: "import" sits directly above "listen" — both feed `carrier.decode(...)` a captured
 * signal, live mic capture vs. an existing audio file already on the device. Gated on
 * [idleEquivalent] alone (unlike "listen", which also stays enabled mid-capture so it can
 * double as "stop") — an import in flight has no equivalent early-cancel action.
 *
 * Task #36: the five action rows (transmit/save/share/import/listen, up from transmit/listen's
 * original two) are grouped into 3 clusters — transmit alone, save+share, import+listen — via
 * spacing alone (12dp between clusters, 0dp within one, no dividers/cards), so the list reads as
 * three related actions rather than one undifferentiated stack. [fileActionBusyLabel] fills the
 * same gap for save/share that "encoding"/"importing" already covered for transmit/import: a
 * plain status word ("saving"/"sharing") shown for the duration of the async write, where
 * previously save/share gave no feedback at all until they finished.
 */
@Composable
fun AcousticModemContent(
    status: ModemStatus,
    payloadText: String,
    onPayloadTextChange: (String) -> Unit,
    maxPayloadBytes: Int,
    micPermissionDenied: Boolean,
    protocol: NightjarAcoustics.Protocol,
    onProtocolChange: (NightjarAcoustics.Protocol) -> Unit,
    symbolRate: NightjarAcoustics.SymbolRate,
    onSymbolRateChange: (NightjarAcoustics.SymbolRate) -> Unit,
    onTransmit: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    fileActionMessage: String?,
    fileActionBusyLabel: String?,
    onImport: () -> Unit,
    onToggleListen: () -> Unit,
    onBack: () -> Unit,
) {
    val idleEquivalent = status is ModemStatus.Idle ||
        status is ModemStatus.DecodedSuccess ||
        status is ModemStatus.DecodedFailure ||
        status is ModemStatus.ImportFailed ||
        status is ModemStatus.ListenFailed
    val payloadBytes = payloadText.encodeToByteArray().size
    val canTransmit = idleEquivalent && payloadText.isNotEmpty() && payloadBytes <= maxPayloadBytes
    val canImport = idleEquivalent
    val canToggleListen = idleEquivalent || status is ModemStatus.Listening
    val listenLabel = if (status is ModemStatus.Listening) "stop" else "listen"

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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "acoustic modem",
                    style = MaterialTheme.typography.displayLarge,
                    color = TextPrimary,
                )
                Text(
                    text = "works best within 1m, in a quiet room.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
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
                                text = "text to send",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextSecondary,
                            )
                        }
                        innerTextField()
                    },
                )
                Text(
                    text = "$payloadBytes / $maxPayloadBytes bytes",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }

            ChannelSettingsBlock(
                protocol = protocol,
                onProtocolChange = onProtocolChange,
                symbolRate = symbolRate,
                onSymbolRateChange = onSymbolRateChange,
                enabled = idleEquivalent,
            )

            // Task #36: 3 clusters via spacing alone (12dp between, 0dp within) — transmit is its
            // own cluster; save/share (both write the encode() output to a file) are one;
            // import/listen (both feed decode() a captured signal) are the third.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionRow(label = "transmit", enabled = canTransmit, onClick = onTransmit)
                Column {
                    // Task #31: "save"/"share" run the same encode() step "transmit" does, gated
                    // on the identical canTransmit condition (idle-equivalent + non-empty +
                    // in-budget payload) — there's nothing extra required to write a WAV file
                    // that isn't already required to play the tones.
                    ActionRow(label = "save", enabled = canTransmit, onClick = onSave)
                    ActionRow(label = "share", enabled = canTransmit, onClick = onShare)
                }
                Column {
                    // Task #32: "import" picks an existing audio file as an alternative to
                    // "listen"'s live mic capture — both feed the same decode() pipeline.
                    ActionRow(label = "import", enabled = canImport, onClick = onImport)
                    ActionRow(label = listenLabel, enabled = canToggleListen, onClick = onToggleListen)
                }
            }

            if (micPermissionDenied) {
                Text(
                    text = "microphone permission needed to listen.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                )
            }

            // Task #31: last save/share outcome ("saved to Music/Nightjar", "save failed", ...),
            // null until the first save/share action runs. Task #36: while a save/share write is
            // actually in flight, [fileActionBusyLabel] ("saving"/"sharing") takes this same spot
            // instead — same labelSmall/TextSecondary text, no spinner, matching the
            // encoding/importing/decoding status-word vocabulary used elsewhere on this screen.
            val fileActionStatusText = fileActionBusyLabel ?: fileActionMessage
            if (fileActionStatusText != null) {
                Text(
                    text = fileActionStatusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }

            StatusBlock(status = status)
        }
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

/**
 * Task #30. Protocol/symbol-rate selector, placed directly above the transmit/listen
 * action rows. Each setting renders as its own labeled group of option rows (same
 * per-option-row list style [dev.herakles.nightjar.modules.imagestego.ImageStegoScreen]'s
 * `CoverRow` sample-cover picker already established), plus a one-line note stating the
 * consequence of a mismatch — real two-phone testing found no other way to learn that a
 * settings mismatch, not a range/noise problem, was why decode kept failing.
 */
@Composable
private fun ChannelSettingsBlock(
    protocol: NightjarAcoustics.Protocol,
    onProtocolChange: (NightjarAcoustics.Protocol) -> Unit,
    symbolRate: NightjarAcoustics.SymbolRate,
    onSymbolRateChange: (NightjarAcoustics.SymbolRate) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "protocol",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
            )
            NightjarAcoustics.Protocol.entries.forEach { option ->
                SettingOptionRow(
                    label = protocolLabel(option),
                    selected = option == protocol,
                    enabled = enabled,
                    onClick = { onProtocolChange(option) },
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "symbol rate",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
            )
            NightjarAcoustics.SymbolRate.entries.forEach { option ->
                SettingOptionRow(
                    label = symbolRateLabel(option),
                    selected = option == symbolRate,
                    enabled = enabled,
                    onClick = { onSymbolRateChange(option) },
                )
            }
        }
        Text(
            text = "both phones must use the same protocol and symbol rate to decode — " +
                "a mismatch looks like an ordinary decode failure with no obvious cause.",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
    }
}

/** One selectable option row, matching `ImageStegoScreen.kt`'s `CoverRow` styling exactly. */
@Composable
private fun SettingOptionRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
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

/** Display copy for [NightjarAcoustics.Protocol]. Kept local to this screen — not codec state. */
private fun protocolLabel(protocol: NightjarAcoustics.Protocol): String = when (protocol) {
    NightjarAcoustics.Protocol.AUDIBLE -> "audible"
    NightjarAcoustics.Protocol.NEAR_ULTRASONIC -> "near-ultrasonic"
}

/** Display copy for [NightjarAcoustics.SymbolRate]. Kept local to this screen — not codec state. */
private fun symbolRateLabel(symbolRate: NightjarAcoustics.SymbolRate): String = when (symbolRate) {
    NightjarAcoustics.SymbolRate.NORMAL -> "normal"
    NightjarAcoustics.SymbolRate.FAST -> "fast"
}

@Composable
private fun StatusBlock(status: ModemStatus) {
    when (status) {
        is ModemStatus.Idle -> Unit // nothing running, nothing to report
        is ModemStatus.Encoding -> StatusWord("encoding")
        // Transmitting/Listening are the two moments the speaker or mic is physically
        // carrying the covert signal — the one accent this app defines (Task #17,
        // see identity.md + Color.kt's AccentSignal doc). Encoding/Decoding are
        // CPU-only, no hardware I/O, so they stay TextSecondary.
        is ModemStatus.Transmitting -> StatusWord("transmitting", color = AccentSignal)
        is ModemStatus.Listening -> ListeningBlock(status)
        // Task #32: CPU/file-I/O only, no hardware I/O, so TextSecondary like Encoding/Decoding
        // rather than the AccentSignal treatment Transmitting/Listening get.
        is ModemStatus.Importing -> StatusWord("importing")
        is ModemStatus.Decoding -> StatusWord("decoding")
        is ModemStatus.DecodedSuccess -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = status.text,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            if (status.correctedByteErrors > 0) {
                val plural = if (status.correctedByteErrors == 1) "" else "s"
                Text(
                    text = "${status.correctedByteErrors} byte$plural corrected",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
        }
        is ModemStatus.DecodedFailure -> Text(
            text = failureMessage(status),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )
        // Task #32: pre-decode transport/format problem — message already names what's wrong,
        // same bodyLarge/TextSecondary informational (not destructive-red) treatment as DecodedFailure.
        is ModemStatus.ImportFailed -> Text(
            text = status.message,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )
        // mic-2: mic failed to initialize or startRecording() threw — same informational
        // treatment as ImportFailed/DecodedFailure, not destructive-red.
        is ModemStatus.ListenFailed -> Text(
            text = status.message,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )
    }
}

@Composable
private fun StatusWord(word: String, color: Color = TextSecondary) {
    Text(
        text = word,
        style = MaterialTheme.typography.labelLarge,
        color = color,
    )
}

/**
 * Task #27. Real two-phone testing showed the plain "listening" word left the person running
 * the test with no idea whether the mic was picking anything up, how long the window would
 * run, or why it eventually failed — the screen just sat there. This adds the two facts that
 * answer those questions, in the same dense text-and-numbers vocabulary the detector screen's
 * `ReadoutBlock` already established (labelSmall/TextSecondary rows under the status word) —
 * no meter, no gauge, no animated bar. `levelDb` is null for the brief instant between tapping
 * "listen" and the first `AudioRecord.read()` chunk landing (~85ms), so it renders a quiet
 * placeholder rather than a misleading number, matching the detector's "confidence appears
 * once you start listening" precedent.
 */
@Composable
private fun ListeningBlock(status: ModemStatus.Listening) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusWord("listening", color = AccentSignal)
        Text(
            text = status.levelDb?.let { "input level ${it.roundToInt()} dB" } ?: "input level appears once capture starts",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        val remaining = status.remainingSeconds.roundToInt().coerceAtLeast(0)
        Text(
            text = "${remaining}s left in listen window",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
    }
}

/**
 * Maps [DecodeFailure] to on-voice copy: what's wrong, what to do about it, two
 * sentences max (voice contract). No exclamation, no "Oops", no red danger color —
 * a decode failure is informational, not destructive (Danger is reserved for real
 * destructive actions only).
 *
 * Task #27: extends this per-case map, doesn't replace it — every reason keeps the message
 * task #7 established, with one addition. [DecodeFailure.NO_PAYLOAD_FOUND] now branches on
 * [ModemStatus.DecodedFailure.timedOut]: real two-phone testing found the generic "no signal
 * found, try again" left the person running the test with no idea *why* it failed after
 * sitting there the whole window — was it too quiet, too far, or did the other phone just
 * never transmit? A full-window timeout with nothing found names the elapsed duration and
 * the two concrete things to check, instead of a bare "try again." An early manual stop (the
 * user tapped "stop" before the window elapsed) keeps the original, shorter message — they
 * already know why nothing was captured, since they're the one who cut it short.
 */
private fun failureMessage(failure: ModemStatus.DecodedFailure): String = when (failure.reason) {
    DecodeFailure.NO_PAYLOAD_FOUND -> if (failure.timedOut) {
        "no signal detected in ${MAX_LISTEN_SECONDS.toInt()}s. move phones closer and confirm the other phone actually transmitted."
    } else {
        "no signal found. move the phones closer and try again."
    }
    DecodeFailure.HEADER_INVALID -> "header didn't check out. try again."
    DecodeFailure.PAYLOAD_TOO_LARGE -> "declared payload is too large. frame rejected."
    DecodeFailure.UNRECOVERABLE_FEC -> "too much signal loss to correct. move closer or cut ambient noise."
    DecodeFailure.INTEGRITY_MISMATCH -> "checksum didn't match. the payload arrived corrupted."
}

/**
 * Task #27. RMS level of the first [n] samples of [chunk], in dBFS relative to 16-bit PCM
 * full scale (0 dB = clipping, negative = quieter). This is a plain level meter, not a
 * demodulator — it says nothing about whether the audio contains this modem's FSK/PSK tones,
 * only that the mic is picking up *something*, which is exactly the gap the real two-phone
 * test surfaced ("no idea whether listen was actually picking anything up"). Clamped to
 * [SILENCE_FLOOR_DB] so true digital silence renders a real floor number instead of -Infinity.
 */
private fun dbfsLevel(chunk: ShortArray, n: Int): Double {
    if (n <= 0) return SILENCE_FLOOR_DB
    var sumSquares = 0.0
    for (i in 0 until n) {
        val sample = chunk[i].toDouble()
        sumSquares += sample * sample
    }
    val rms = sqrt(sumSquares / n)
    if (rms < 1.0) return SILENCE_FLOOR_DB
    return (20.0 * log10(rms / 32768.0)).coerceAtLeast(SILENCE_FLOOR_DB)
}

/**
 * Owns the [CovertCarrier] round-trip plus the `AudioTrack`/`AudioRecord` transport
 * that drives it (architecture.md draws the codec/transport line at the UI layer, not
 * inside the interface). Constructor takes only the interface — no `AcousticCarrier`
 * reference anywhere in this file.
 *
 * Not an `androidx.lifecycle.ViewModel` — that dependency isn't in this module's
 * gradle file yet, and a plain `mutableStateOf`-backed class disposed via
 * `DisposableEffect` is the same "Simple is better" pattern `MainActivity.kt` already
 * uses for its own screen-level state.
 */
class AcousticModemController(
    private val carrier: CovertCarrier<PcmAudio>,
    private val appContext: Context,
) {

    var status: ModemStatus by mutableStateOf(ModemStatus.Idle)
        private set

    /** Task #31: last [saveToDevice]/[shareFromDevice] outcome, shown under the action rows. */
    var fileActionMessage: String? by mutableStateOf(null)
        private set

    /**
     * Task #36: transient "saving"/"sharing" word shown while [saveToDevice]/[shareFromDevice]'s
     * async encode + MediaStore-write is actually running — matches the bare-present-participle
     * status-word vocabulary the rest of this screen already uses (encoding/transmitting/
     * listening/importing/decoding) instead of a spinner or progress bar. Distinct from
     * [fileActionMessage], which reports the *outcome* once the operation finishes; this reports
     * that one is currently in flight. Real gap before this task: transmit/import both show a
     * status word the instant they start, but save/share showed nothing at all until they
     * finished.
     */
    var fileActionBusyLabel: String? by mutableStateOf(null)
        private set

    /**
     * Task #19 (gate-17), Stage B: the last successfully-transmitted clip. [ModemStatus] carries
     * no PCM of its own, and the `pcm` [transmit] encodes is a coroutine-local that's gone by the
     * time `jarCatchFlow`'s separate `LaunchedEffect(status::class)` reacts to the
     * Transmitting→Idle edge and wants to persist it alongside the CREATED [FireflyRecord]. One
     * clip, overwritten on every transmit -- not a history buffer.
     */
    var lastTransmittedPcm: PcmAudio? by mutableStateOf(null)
        private set

    /**
     * Task #19 (gate-17), Stage B: the audio [carrier.decode] most recently ran against -- from
     * either a live [capturePcm] mic window ([startListening]) or an [importAndDecode] file pick,
     * whichever path most recently produced the [ModemStatus.DecodedSuccess] that
     * `jarCatchFlow`'s effect reacts to. Same retain-because-the-local-goes-out-of-scope need as
     * [lastTransmittedPcm]; kept path-agnostic (updated by both callers) so a
     * decode-success-via-import never gets tagged with a stale clip left over from an earlier
     * live listen. One clip, overwritten on every decode attempt -- not a history buffer.
     */
    var lastDecodedPcm: PcmAudio? by mutableStateOf(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listening = AtomicBoolean(false)

    /** [transmit]'s currently in-flight job, if any — tracked so [stopForBackground] can cancel
     *  just the transmit playback without tearing down [scope] itself (mic-5). */
    private var transmitJob: Job? = null

    /** True while idle, or holding a terminal decode result — the shared "not busy" gate for
     * [transmit]/[saveToDevice]/[shareFromDevice], all of which run their own one-shot [encode]. */
    private fun isIdleEquivalent(): Boolean =
        status is ModemStatus.Idle || status is ModemStatus.DecodedSuccess || status is ModemStatus.DecodedFailure

    /** Encode [payloadText] and play it over the speaker. No-op while busy. */
    fun transmit(payloadText: String) {
        if (!isIdleEquivalent()) return
        transmitJob = scope.launch {
            status = ModemStatus.Encoding
            val pcm = try {
                carrier.encode(payloadText.encodeToByteArray())
            } catch (oversized: IllegalArgumentException) {
                DebugProbe.reportEncodeDecodeResult(ModuleId.ACOUSTIC_MODEM, DebugProbe.Operation.ENCODE, success = false)
                status = ModemStatus.Idle
                return@launch
            }
            DebugProbe.reportEncodeDecodeResult(ModuleId.ACOUSTIC_MODEM, DebugProbe.Operation.ENCODE, success = true)
            status = ModemStatus.Transmitting
            lastTransmittedPcm = pcm
            playPcm(pcm)
            status = ModemStatus.Idle
        }
    }

    /**
     * Task #31. Encodes [payloadText] (independently of [transmit] — this runs its own
     * [CovertCarrier.encode] call rather than requiring a prior transmit) into a standard WAV
     * file and writes it to the device via `MediaStore.Audio` under `Music/Nightjar/`. Scoped
     * storage — no legacy `WRITE_EXTERNAL_STORAGE` permission needed (this app's minSdk is 31,
     * well past the API 29 scoped-storage cutover). No-op while busy; sets [fileActionMessage]
     * with the outcome. Task #36: [fileActionBusyLabel] carries "saving" for the duration of the
     * write itself, so the screen shows something the instant the row is tapped instead of only
     * once it finishes.
     */
    fun saveToDevice(payloadText: String) {
        if (!isIdleEquivalent()) return
        scope.launch {
            fileActionBusyLabel = "saving"
            val uri = encodeAndWriteWav(payloadText)
            fileActionBusyLabel = null
            if (uri != null) {
                fileActionMessage = "saved to Music/Nightjar"
            }
        }
    }

    /**
     * Task #31. Same encode-and-write path as [saveToDevice], then opens Android's share sheet
     * (`Intent.ACTION_SEND`) on the resulting MediaStore `content://` Uri so the WAV file can be
     * sent through any installed app. No-op while busy; sets [fileActionMessage] on failure (a
     * successful share hands off to the share sheet itself, which is its own confirmation).
     * Task #36: [fileActionBusyLabel] carries "sharing" (not "saving") for the duration of the
     * write, so the status word matches the action the operator actually tapped.
     */
    fun shareFromDevice(payloadText: String) {
        if (!isIdleEquivalent()) return
        scope.launch {
            fileActionBusyLabel = "sharing"
            val uri = encodeAndWriteWav(payloadText)
            fileActionBusyLabel = null
            if (uri == null) return@launch
            withContext(Dispatchers.Main) {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/wav"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(sendIntent, "share nightjar audio")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // appContext is not an Activity
                appContext.startActivity(chooser)
            }
        }
    }

    /**
     * Shared encode + MediaStore-write path for [saveToDevice]/[shareFromDevice]: encodes
     * [payloadText] via [carrier], wraps the resulting [PcmAudio] in a standard WAV container via
     * [WavFile], and writes it to a new `MediaStore.Audio` row. Returns the new row's
     * `content://` Uri, or `null` on any failure (oversized payload, MediaStore insert/write
     * failure) — either way [fileActionMessage] is set so the failure is visible, matching how
     * [transmit]'s oversized-payload path already reports through [DebugProbe] rather than
     * failing silently.
     */
    private suspend fun encodeAndWriteWav(payloadText: String): Uri? = withContext(Dispatchers.IO) {
        val pcm = try {
            carrier.encode(payloadText.encodeToByteArray())
        } catch (oversized: IllegalArgumentException) {
            fileActionMessage = "payload too large to save"
            return@withContext null
        }
        val wavBytes = WavFile.encodePcm16Mono(pcm, NightjarAcoustics.SAMPLE_RATE_HZ)
        val uri = writeWavToMediaStore(wavBytes)
        if (uri == null) {
            fileActionMessage = "save failed"
        }
        uri
    }

    /**
     * Inserts a new row into `MediaStore.Audio` (`Music/Nightjar/<timestamp>.wav`) and writes
     * [wavBytes] into it. Scoped storage (API 29+; this app's minSdk is 31) means no
     * `WRITE_EXTERNAL_STORAGE` permission is needed to write into an app-created MediaStore row.
     * Returns the new row's Uri, or `null` on any insert/write failure — cleans up (deletes) a
     * successfully-inserted row if the write itself then fails, so a failed save never leaves a
     * zero-byte/partial file behind.
     */
    private fun writeWavToMediaStore(wavBytes: ByteArray): Uri? {
        val resolver = appContext.contentResolver
        val displayName = "nightjar_${System.currentTimeMillis()}.wav"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Nightjar")
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            val stream = resolver.openOutputStream(uri)
            if (stream == null) {
                resolver.delete(uri, null, null)
                return null
            }
            stream.use { it.write(wavBytes) }
            uri
        } catch (writeFailed: IOException) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * Task #32. Reads the audio file at [uri] (picked via `ActivityResultContracts.OpenDocument`,
     * any `audio/`-prefixed mime type) and runs the recovered PCM16 mono/48kHz samples through the same
     * [CovertCarrier.decode] pipeline [startListening]'s live `AudioRecord` capture uses — import
     * is an alternative signal source, not a different decode path. No-op while busy (mirrors
     * [startListening]'s [isIdleEquivalent] guard).
     *
     * Two container paths, chosen by sniffing the file's magic bytes rather than trusting its
     * MIME type or extension (both are caller-supplied and unreliable):
     *  - **WAV** (`RIFF....WAVE`): parsed directly via [WavFile.decodePcm16], the inverse of this
     *    app's own [WavFile.encodePcm16Mono] — no platform decoder needed, and this is exactly
     *    the format this app's own "save"/"share" actions (task #31) produce, so a
     *    nightjar-to-nightjar file transfer never leaves this path.
     *  - **Everything else** (MP3/AAC/OGG/etc.): decoded to PCM via `MediaExtractor` +
     *    `MediaCodec` ([decodeCompressedAudioToPcm]), Android's standard container-demux +
     *    codec-decode path.
     *
     * `decode()` assumes exactly [NightjarAcoustics.SAMPLE_RATE_HZ] mono (architecture.md §1).
     * Rather than resample an arbitrary picked file to match — a correct, general-ratio
     * resampler is real DSP work, out of this task's scope — a mismatch is rejected up front
     * with a message naming both the actual and required format ([ModemStatus.ImportFailed]),
     * before ever reaching [CovertCarrier.decode].
     */
    fun importAndDecode(uri: Uri) {
        if (!isIdleEquivalent()) return
        scope.launch {
            status = ModemStatus.Importing
            val imported = readAudioFile(appContext, uri)
            if (imported == null) {
                status = ModemStatus.ImportFailed("couldn't read that file as audio. pick a different one.")
                return@launch
            }
            if (imported.sampleRateHz != NightjarAcoustics.SAMPLE_RATE_HZ || imported.numChannels != 1) {
                status = ModemStatus.ImportFailed(formatMismatchMessage(imported.sampleRateHz, imported.numChannels))
                return@launch
            }
            status = ModemStatus.Decoding
            // decode() rejects any carrier whose length isn't a whole number of FRAME_SAMPLES as
            // its very first check (AcousticCarrier.kt) — encode()'s own output always satisfies
            // that by construction, but a real imported file's sample count essentially never
            // does by chance. Padding with trailing silence is safe: decode() locates every
            // window from the header's own declared length, never from the buffer's total size.
            val pcm = padToFrameBoundary(imported.samples)
            lastDecodedPcm = pcm
            status = when (val result = decodeCancellable(carrier, pcm)) {
                is DecodeResult.Success -> {
                    DebugProbe.reportEncodeDecodeResult(ModuleId.ACOUSTIC_MODEM, DebugProbe.Operation.DECODE, success = true)
                    ModemStatus.DecodedSuccess(result.payload.decodeToString(), result.correctedByteErrors)
                }
                is DecodeResult.Failure -> {
                    DebugProbe.reportEncodeDecodeResult(ModuleId.ACOUSTIC_MODEM, DebugProbe.Operation.DECODE, success = false)
                    ModemStatus.DecodedFailure(result.reason, result.detail, timedOut = false)
                }
            }
        }
    }

    /** Start a bounded mic capture; auto-stops at [MAX_LISTEN_SECONDS] or an earlier [stopListening]. */
    fun startListening() {
        if (status is ModemStatus.Listening) return
        listening.set(true)
        // mic-6 fix: status flips to Listening synchronously, before scope.launch, instead of as
        // the first line inside the launched coroutine. Previously there was a window between the
        // `listening` flag going true and `status` actually reading Listening where a double-tap
        // could slip past this method's own `if (status is Listening) return` guard above and open
        // a second concurrent AudioRecord. Mirrors DetectorController.startListening()'s
        // `isListening` flag, which already does this correctly. levelDb starts null (nothing
        // captured yet); remainingSeconds starts at the full window so the countdown is visible
        // the instant "listen" is tapped, before the first AudioRecord.read() chunk has even
        // returned (Task #27).
        status = ModemStatus.Listening(levelDb = null, remainingSeconds = MAX_LISTEN_SECONDS)
        scope.launch {
            val captureResult = try {
                capturePcm()
            } catch (permissionRevoked: SecurityException) {
                status = ModemStatus.Idle
                return@launch
            } catch (micBusy: IllegalStateException) {
                // mic-2 fix: MicCapture.openBestAudioRecord() returned null, or startRecording()
                // itself threw once initialized — either way, surface it instead of crashing (this
                // used to be an uncaught IllegalStateException from openBestAudioRecord()'s last
                // resort `error(...)` call).
                status = ModemStatus.ListenFailed("the microphone is busy — a call or another app is using it.")
                return@launch
            }
            lastDecodedPcm = captureResult.pcm
            status = ModemStatus.Decoding
            status = when (val result = decodeCancellable(carrier, captureResult.pcm)) {
                is DecodeResult.Success -> {
                    DebugProbe.reportEncodeDecodeResult(ModuleId.ACOUSTIC_MODEM, DebugProbe.Operation.DECODE, success = true)
                    ModemStatus.DecodedSuccess(result.payload.decodeToString(), result.correctedByteErrors)
                }
                is DecodeResult.Failure -> {
                    DebugProbe.reportEncodeDecodeResult(ModuleId.ACOUSTIC_MODEM, DebugProbe.Operation.DECODE, success = false)
                    ModemStatus.DecodedFailure(result.reason, result.detail, timedOut = captureResult.timedOut)
                }
            }
        }
    }

    /** Ends the capture window early; the in-flight coroutine finishes the decode itself. */
    fun stopListening() {
        listening.set(false)
    }

    /**
     * mic-5 fix: stops in-flight listen capture and/or transmit playback when the app is
     * backgrounded (`LifecycleEventEffect(Lifecycle.Event.ON_STOP)` in every composable that owns
     * this controller). Distinct from [dispose]: this leaves [scope] alive, so the controller is
     * still usable once the app returns to the foreground — it only tears down whichever op is
     * currently running. [stopListening]'s existing graceful stop (`listening.set(false)`, letting
     * the in-flight coroutine exit its own read loop and finish the decode) already covers the
     * listen case; transmit has no equivalent cooperative flag — [playPcm] is a single blocking
     * `delay()` for the tone's playback duration — so cancelling [transmitJob] is what actually
     * interrupts it (cancellation propagates through that `delay()` call, running `playPcm`'s own
     * `finally { audioTrack.stop(); audioTrack.release() }`).
     */
    fun stopForBackground() {
        stopListening()
        val job = transmitJob
        if (job != null && job.isActive) {
            job.cancel()
            status = ModemStatus.Idle
        }
    }

    /** Cancels any in-flight transmit/listen work. Call from `DisposableEffect.onDispose`. */
    fun dispose() {
        listening.set(false)
        scope.cancel()
    }

    private suspend fun playPcm(pcm: PcmAudio) = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) return@withContext
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(NightjarAcoustics.SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            audioTrack.write(pcm, 0, pcm.size)
            audioTrack.play()
            val durationMs = pcm.size.toLong() * 1000L / NightjarAcoustics.SAMPLE_RATE_HZ
            delay(durationMs + 200)
        } finally {
            audioTrack.stop()
            audioTrack.release()
        }
    }

    /**
     * Task #27. `pcm` is the captured buffer (unchanged from before); `timedOut` is true when
     * the loop exited because it filled [maxSamples] — i.e. the full [MAX_LISTEN_SECONDS]
     * window elapsed with the user never tapping "stop" — vs. false for an early manual
     * [stopListening]. [failureMessage] uses this to tell "sat through the whole window and
     * heard nothing" apart from "the user cut it short," which call for different copy.
     */
    private class CaptureResult(val pcm: PcmAudio, val timedOut: Boolean)

    private suspend fun capturePcm(): CaptureResult = withContext(Dispatchers.IO) {
        val minBufBytes = AudioRecord.getMinBufferSize(
            NightjarAcoustics.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val recordBufferBytes = if (minBufBytes > 0) minBufBytes * 4 else NightjarAcoustics.FRAME_SAMPLES * 8

        // mic-2 fix: MicCapture.openBestAudioRecord() returns null (never throws) when every
        // AudioSource tier fails to initialize (mic held by a call or another app). That used to
        // be an uncaught IllegalStateException thrown straight out of a private `error(...)` last
        // resort; now it's thrown once, here, at a boundary startListening()'s own try/catch
        // already has a handler for (ModemStatus.ListenFailed) instead of crashing the app.
        val audioRecord = MicCapture.openBestAudioRecord(appContext, recordBufferBytes)
            ?: throw IllegalStateException("AudioRecord failed to initialize — mic busy or unavailable")

        val maxSamples = (MAX_LISTEN_SECONDS * NightjarAcoustics.SAMPLE_RATE_HZ).toInt()
        val out = ShortArray(maxSamples)
        var written = 0
        // Read LEVEL_UPDATE_CHUNK_FRAMES frames (~85ms) at a time rather than one FRAME_SAMPLES
        // chunk (~21ms) per read — same throttle DetectorController uses for its live confidence
        // readout, applied here so the input-level/countdown UI recomposes a few times a second
        // instead of on every ~21ms analysis frame (Task #27).
        val chunk = ShortArray(NightjarAcoustics.FRAME_SAMPLES * LEVEL_UPDATE_CHUNK_FRAMES)
        // mic-8 fix: disablePlatformAudioEffects() now runs from inside this try — right after the
        // AudioRecord it operates on is already in hand — instead of before it. A throwing OEM
        // effect factory used to be able to skip straight past audioRecord.release() below and
        // leak the record; disabledEffects simply stays empty (nothing to release) if the disable
        // call itself throws.
        var disabledEffects: List<AudioEffect> = emptyList()
        try {
            disabledEffects = MicCapture.disablePlatformAudioEffects(audioRecord.audioSessionId)
            audioRecord.startRecording()
            while (listening.get() && written < maxSamples) {
                val n = audioRecord.read(chunk, 0, chunk.size)
                if (n <= 0) break
                val toCopy = minOf(n, maxSamples - written)
                System.arraycopy(chunk, 0, out, written, toCopy)
                written += toCopy
                publishListeningReadout(chunk, n, written, maxSamples)
            }
        } finally {
            // mic-1 precedent applied here too: only call stop() if the record actually reached
            // RECORDSTATE_RECORDING — calling it otherwise (e.g. startRecording() itself threw)
            // throws its own IllegalStateException and would mask whatever failure got us here.
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
            audioRecord.release()
            disabledEffects.forEach { it.release() }
        }
        CaptureResult(pcm = out.copyOf(written), timedOut = written >= maxSamples)
    }

    /**
     * Publishes the live input-level (dBFS over [chunk]'s first [n] samples) and remaining-time
     * countdown as the current [ModemStatus.Listening] value, so the UI reflects both without
     * any separate polling/animation loop (Task #27). Guarded on `status is Listening` in case
     * a stray read lands after the state already moved on (shouldn't happen given this is only
     * called from inside [capturePcm]'s own loop, but cheap to guard against a stale write).
     */
    private fun publishListeningReadout(chunk: ShortArray, n: Int, written: Int, maxSamples: Int) {
        if (status !is ModemStatus.Listening) return
        val remainingSeconds = ((maxSamples - written).toDouble() / NightjarAcoustics.SAMPLE_RATE_HZ)
            .coerceAtLeast(0.0)
        status = ModemStatus.Listening(levelDb = dbfsLevel(chunk, n), remainingSeconds = remainingSeconds)
    }

    // mic-1/mic-2/mic-3/mic-8: AudioRecord source-fallback + platform-DSP-effect suppression
    // moved out to the shared dev.herakles.nightjar.MicCapture helper (used by DetectorController
    // too) — see capturePcm() above and MicCapture.kt's own KDoc.
}

/**
 * Runs [carrier].decode([pcm]) with cooperative cancellation when possible, in place of a bare
 * `carrier.decode(pcm)` call. [AcousticCarrier.decode]'s cost is linear in `pcm`'s length (that
 * class's own KDoc "Cost" note) — cheap for a bounded live-listen capture, but on a large imported
 * file (up to [MAX_IMPORT_FILE_BYTES], ~11 minutes at 48kHz mono) a transmission-free buffer used
 * to scan the *whole* thing with no way to actually cancel: `decode()` is a single synchronous,
 * non-`suspend` call, so backing out of the screen (cancelling [AcousticModemController]'s `scope`)
 * had no effect on a search already in flight, and `startListening()`/`importAndDecode()`'s own
 * busy-gate left transmit/save/share/import/listen all disabled for however long it took.
 *
 * [AcousticCarrier] exposes a `decode(carrier, checkCancelled)` overload for exactly this, but it
 * isn't part of [CovertCarrier] — this screen's controllers are otherwise deliberately
 * carrier-agnostic (see [AcousticModemController]'s own KDoc), and widening the shared interface
 * would also require updating every other [CovertCarrier] implementation (`AudioStegoCarrier`,
 * `ImageStegoCarrier`) for a change only this carrier's search actually needs. This function is
 * the one narrow, documented exception: a runtime type check reaches the cancellable overload when
 * [carrier] is actually the concrete [AcousticCarrier] this screen always constructs (via
 * `carrierFactory`/[jarCatchFlow]'s own direct construction); any other [CovertCarrier] — e.g. a
 * test fake — just runs the plain, uncancellable [CovertCarrier.decode].
 */
private suspend fun decodeCancellable(carrier: CovertCarrier<PcmAudio>, pcm: PcmAudio): DecodeResult {
    val ctx = coroutineContext
    return if (carrier is AcousticCarrier) {
        carrier.decode(pcm) { ctx.ensureActive() }
    } else {
        carrier.decode(pcm)
    }
}

// --- Task #32: "import" file reading — plain (non-composable, non-member) functions, matching
// how ImageStegoScreen.kt's Photo-Picker cover decoding is likewise kept outside its controller
// class. All of it runs off the main thread from inside AcousticModemController.importAndDecode's
// `scope.launch` (already Dispatchers.IO), same as that file's `decodePickedCoverImage`. ---

/**
 * Outcome of [readAudioFile]: one PCM16 buffer plus the format it was recorded at.
 *
 * `internal`, not `private` (v6 receive-plumbing, task W1-2): read directly by
 * `dev.herakles.nightjar.incoming.IncomingAndroidAdapters.decodeCompressedAudioForModem`, which
 * reuses [decodeCompressedAudioToPcm] as-is rather than reimplementing its MediaExtractor/
 * MediaCodec path. Visibility-only change -- no behavior here is different.
 */
internal class ImportedAudio(val sampleRateHz: Int, val numChannels: Int, val samples: ShortArray)

/**
 * Size cap on a picked WAV file read fully into memory. This app's own longest export (20s @
 * 48kHz mono PCM16, [MAX_LISTEN_SECONDS]) is ~1.9MB; this gives generous headroom for a real
 * device recording while still bounding worst-case memory use for an accidentally-huge pick.
 */
private const val MAX_IMPORT_FILE_BYTES = 64 * 1024 * 1024

/**
 * Bound on [decodeCompressedAudioToPcm]'s MediaCodec drain loop wall-clock time, so a
 * malformed/stalled decoder on a bad imported file can never hang the import indefinitely.
 */
private const val IMPORT_DECODE_TIMEOUT_MS = 30_000L

/** MediaCodec dequeue timeout per poll — the standard value used in Android's own samples. */
private const val CODEC_TIMEOUT_US = 10_000L

/**
 * Reads [uri] as either a WAV file (parsed directly) or a compressed container
 * (`MediaExtractor`/`MediaCodec`). Format is chosen by sniffing the file's own magic bytes, not
 * its MIME type or extension — both are caller-supplied and unreliable. Returns `null` on any
 * read/parse failure: unreadable Uri, corrupt/unsupported container, no audio track, or a WAV
 * whose `fmt ` isn't PCM16 ([WavFile.decodePcm16]'s own failure contract).
 */
private suspend fun readAudioFile(context: Context, uri: Uri): ImportedAudio? =
    if (sniffIsWav(context, uri)) {
        val bytes = readBoundedBytes(context, uri, MAX_IMPORT_FILE_BYTES) ?: return null
        val parsed = WavFile.decodePcm16(bytes) ?: return null
        ImportedAudio(parsed.sampleRateHz, parsed.numChannels, parsed.samples)
    } else {
        decodeCompressedAudioToPcm(context, uri)
    }

/** True if [uri]'s first 12 bytes are a RIFF/WAVE magic. Never throws — an I/O failure just reads as "not WAV". */
private fun sniffIsWav(context: Context, uri: Uri): Boolean = try {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val header = ByteArray(12)
        var read = 0
        while (read < header.size) {
            val n = stream.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
        read == 12 &&
            String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
    } ?: false
} catch (ioFailure: IOException) {
    false
} catch (denied: SecurityException) {
    false
}

/**
 * Reads all of [uri]'s bytes, bounded to [maxBytes]. Guards against an accidentally-picked huge
 * file exhausting memory. Returns `null` on any I/O failure or if the file exceeds the bound.
 */
private fun readBoundedBytes(context: Context, uri: Uri, maxBytes: Int): ByteArray? = try {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(chunk, 0, n)
        }
        out.toByteArray()
    }
} catch (ioFailure: IOException) {
    null
} catch (denied: SecurityException) {
    null
}

/**
 * Decodes a compressed audio container (MP3/AAC/OGG/etc.) at [uri] to PCM16 via `MediaExtractor`
 * (container demux) + `MediaCodec` (the platform's own decoder for whatever codec the track
 * uses) — Android's standard decode-to-PCM path, the one this app has no business reimplementing.
 *
 * Reads the first audio track's declared sample rate/channel count from the *container* metadata
 * (available immediately after [MediaExtractor.selectTrack], before any decoding) and, if that
 * already doesn't match [NightjarAcoustics.SAMPLE_RATE_HZ] mono, returns immediately with an
 * empty sample buffer — [AcousticModemController.importAndDecode]'s format check still gets the
 * real numbers to report, without this function burning battery decoding audio `decode()` could
 * never accept anyway.
 *
 * Returns `null` on any failure to open/demux/decode the file, or if it has no audio track.
 *
 * `internal`, not `private` (v6 receive-plumbing, task W1-2): this is the exact MediaExtractor/
 * MediaCodec decode path `dev.herakles.nightjar.incoming.IncomingAndroidAdapters
 * .decodeCompressedAudioForModem` reuses for a shared/opened compressed-audio file's acoustic-
 * modem detection, rather than reimplementing container demux + codec decode a second time.
 * Visibility-only change -- no behavior here is different.
 */
internal suspend fun decodeCompressedAudioToPcm(context: Context, uri: Uri): ImportedAudio? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(context, uri, null)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            val mime = candidate.getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) {
                trackIndex = i
                format = candidate
                break
            }
        }
        if (trackIndex < 0 || format == null) return null
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val containerSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0)
        val containerChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
        if (containerSampleRate != NightjarAcoustics.SAMPLE_RATE_HZ || containerChannels != 1) {
            // Container metadata alone already proves this file can't feed decode() — skip the
            // real decode work and let the caller's format check report these actual numbers.
            return ImportedAudio(containerSampleRate, containerChannels, ShortArray(0))
        }

        decodeSelectedTrack(extractor, format, mime, containerSampleRate, containerChannels)
    } catch (unreadable: IOException) {
        null
    } catch (invalid: IllegalArgumentException) {
        null
    } catch (cancelled: CancellationException) {
        // mic-9: decodeSelectedTrack()'s drain loop now checks coroutineContext.ensureActive()
        // per iteration, so backing out of the app mid-import throws this here. It must NOT be
        // swallowed by the broad `catch (codecFailure: Exception)` below (CancellationException
        // is a RuntimeException) — that would break structured cancellation and leave the
        // coroutine thinking the import "completed" with a null result instead of actually
        // stopping.
        throw cancelled
    } catch (codecFailure: Exception) {
        // MediaCodec/OEM decoder implementations are documented to throw a wide variety of
        // unchecked exceptions (IllegalStateException, MediaCodec.CodecException, ...) on a
        // malformed/unsupported real-world file picked by the operator — this boundary must
        // never crash the app over a bad import, so it fails closed to null instead.
        null
    } finally {
        extractor.release()
    }
}

/**
 * The actual MediaCodec drain loop, split out of [decodeCompressedAudioToPcm] once the track is
 * already known to match [NightjarAcoustics.SAMPLE_RATE_HZ] mono. Standard
 * dequeue-input/feed-extractor, dequeue-output/collect-bytes MediaCodec loop, bounded by
 * [IMPORT_DECODE_TIMEOUT_MS] wall-clock time so a stalled decoder can't hang forever. Output
 * bytes are the platform decoder's native PCM16 little-endian samples (Android's audio decoders
 * emit `ENCODING_PCM_16BIT` by default).
 *
 * mic-9: also checks [coroutineContext] for cancellation once per iteration — before this fix,
 * backing out of the import screen mid-decode (which cancels [AcousticModemController]'s `scope`,
 * e.g. via [AcousticModemController.dispose]) had no effect on this loop; it would keep polling
 * the codec for up to [IMPORT_DECODE_TIMEOUT_MS] (30s) after the operator had already left.
 */
private suspend fun decodeSelectedTrack(
    extractor: MediaExtractor,
    format: MediaFormat,
    mime: String,
    sampleRateHz: Int,
    numChannels: Int,
): ImportedAudio? {
    val codec = MediaCodec.createDecoderByType(mime)
    var started = false
    return try {
        codec.configure(format, null, null, 0)
        codec.start()
        started = true

        val pcmOut = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        val deadlineMs = System.currentTimeMillis() + IMPORT_DECODE_TIMEOUT_MS

        while (!sawOutputEos) {
            coroutineContext.ensureActive()
            if (System.currentTimeMillis() > deadlineMs) return null

            if (!sawInputEos) {
                val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    val sampleSize = inputBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
            if (outputIndex >= 0) {
                if (bufferInfo.size > 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        pcmOut.write(chunk)
                    }
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    sawOutputEos = true
                }
            }
        }

        val bytes = pcmOut.toByteArray()
        val samples = ShortArray(bytes.size / 2)
        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in samples.indices) {
            samples[i] = byteBuffer.short
        }
        ImportedAudio(sampleRateHz, numChannels, samples)
    } finally {
        try {
            if (started) codec.stop()
        } catch (alreadyStopped: IllegalStateException) {
            // best-effort cleanup only
        }
        codec.release()
    }
}

/**
 * Zero-pads [samples] up to the next whole multiple of [NightjarAcoustics.FRAME_SAMPLES] —
 * `decode()`'s very first check rejects any carrier whose length isn't frame-aligned
 * (AcousticCarrier.kt), and unlike `encode()`'s own output (always exactly frame-aligned by
 * construction), a real imported file's sample count is essentially never a multiple of 1024 by
 * chance. Padding with trailing silence is safe: `decode()` locates every window (START marker,
 * header, payload, END marker) from the header's own declared length, never from the buffer's
 * total size, so extra trailing zero samples after the real content never shift anything it reads.
 *
 * `internal`, not `private` (v6 receive-plumbing, task W1-2): reused by
 * `dev.herakles.nightjar.incoming.IncomingAndroidAdapters.decodeCompressedAudioForModem` for the
 * same reason -- a compressed-audio import's sample count is no more likely to be frame-aligned
 * than this screen's own WAV import. Visibility-only change -- no behavior here is different.
 */
internal fun padToFrameBoundary(samples: ShortArray): ShortArray {
    val frameSamples = NightjarAcoustics.FRAME_SAMPLES
    val remainder = samples.size % frameSamples
    if (remainder == 0) return samples
    return samples.copyOf(samples.size + (frameSamples - remainder))
}

/**
 * "file is 44100Hz stereo, need 48000Hz mono." Names both the actual and required format, since
 * a bare "wrong format" leaves the operator no way to know what to fix — the same on-voice-copy
 * standard [failureMessage] already applies to decode failures.
 */
private fun formatMismatchMessage(actualSampleRateHz: Int, actualChannels: Int): String {
    val channelWord = when {
        actualChannels == 1 -> "mono"
        actualChannels == 2 -> "stereo"
        actualChannels > 2 -> "$actualChannels-channel"
        else -> "unknown-channel"
    }
    return "file is ${actualSampleRateHz}Hz $channelWord, need ${NightjarAcoustics.SAMPLE_RATE_HZ}Hz mono."
}

// --- Previews: AcousticModemContent is pure, so these need no CovertCarrier fake. ---

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewIdle() {
    PreviewSurface {
        AcousticModemContent(
            status = ModemStatus.Idle,
            payloadText = "wet-snacks-design",
            onPayloadTextChange = {},
            maxPayloadBytes = 1024,
            micPermissionDenied = false,
            protocol = NightjarAcoustics.Protocol.AUDIBLE,
            onProtocolChange = {},
            symbolRate = NightjarAcoustics.SymbolRate.NORMAL,
            onSymbolRateChange = {},
            onTransmit = {},
            onSave = {},
            onShare = {},
            fileActionMessage = null,
            fileActionBusyLabel = null,
            onImport = {},
            onToggleListen = {},
            onBack = {},
        )
    }
}

/**
 * Task #36: the transient "saving" word — [ModemStatus] itself stays [ModemStatus.Idle] the
 * whole time a save write runs (it's a file write, not a transmit/decode step), so this state
 * only shows up through [fileActionBusyLabel], not through [status].
 */
@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewSaving() {
    PreviewSurface {
        AcousticModemContent(
            status = ModemStatus.Idle,
            payloadText = "wet-snacks-design",
            onPayloadTextChange = {},
            maxPayloadBytes = 1024,
            micPermissionDenied = false,
            protocol = NightjarAcoustics.Protocol.AUDIBLE,
            onProtocolChange = {},
            symbolRate = NightjarAcoustics.SymbolRate.NORMAL,
            onSymbolRateChange = {},
            onTransmit = {},
            onSave = {},
            onShare = {},
            fileActionMessage = null,
            fileActionBusyLabel = "saving",
            onImport = {},
            onToggleListen = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewTransmitting() {
    PreviewSurface {
        AcousticModemContent(
            status = ModemStatus.Transmitting,
            payloadText = "wet-snacks-design",
            onPayloadTextChange = {},
            maxPayloadBytes = 1024,
            micPermissionDenied = false,
            protocol = NightjarAcoustics.Protocol.AUDIBLE,
            onProtocolChange = {},
            symbolRate = NightjarAcoustics.SymbolRate.NORMAL,
            onSymbolRateChange = {},
            onTransmit = {},
            onSave = {},
            onShare = {},
            fileActionMessage = null,
            fileActionBusyLabel = null,
            onImport = {},
            onToggleListen = {},
            onBack = {},
        )
    }
}

/** Task #27: the brief window before the first AudioRecord chunk lands — level still null. */
@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewListeningStarting() {
    PreviewSurface {
        AcousticModemContent(
            status = ModemStatus.Listening(levelDb = null, remainingSeconds = 20.0),
            payloadText = "",
            onPayloadTextChange = {},
            maxPayloadBytes = 1024,
            micPermissionDenied = false,
            protocol = NightjarAcoustics.Protocol.AUDIBLE,
            onProtocolChange = {},
            symbolRate = NightjarAcoustics.SymbolRate.NORMAL,
            onSymbolRateChange = {},
            onTransmit = {},
            onSave = {},
            onShare = {},
            fileActionMessage = null,
            fileActionBusyLabel = null,
            onImport = {},
            onToggleListen = {},
            onBack = {},
        )
    }
}

/** Task #27: mid-window, with a live level reading and a partly-elapsed countdown. */
@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewListening() {
    PreviewSurface {
        AcousticModemContent(
            status = ModemStatus.Listening(levelDb = -38.0, remainingSeconds = 12.0),
            payloadText = "",
            onPayloadTextChange = {},
            maxPayloadBytes = 1024,
            micPermissionDenied = false,
            protocol = NightjarAcoustics.Protocol.AUDIBLE,
            onProtocolChange = {},
            symbolRate = NightjarAcoustics.SymbolRate.NORMAL,
            onSymbolRateChange = {},
            onTransmit = {},
            onSave = {},
            onShare = {},
            fileActionMessage = null,
            fileActionBusyLabel = null,
            onImport = {},
            onToggleListen = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewDecodedSuccess() {
    PreviewSurface {
        AcousticModemContent(
            status = ModemStatus.DecodedSuccess(text = "the ravens have landed", correctedByteErrors = 3),
            payloadText = "",
            onPayloadTextChange = {},
            maxPayloadBytes = 1024,
            micPermissionDenied = false,
            protocol = NightjarAcoustics.Protocol.AUDIBLE,
            onProtocolChange = {},
            symbolRate = NightjarAcoustics.SymbolRate.NORMAL,
            onSymbolRateChange = {},
            onTransmit = {},
            onSave = {},
            onShare = {},
            fileActionMessage = null,
            fileActionBusyLabel = null,
            onImport = {},
            onToggleListen = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewDecodedFailure() {
    PreviewSurface {
        AcousticModemContent(
            status = ModemStatus.DecodedFailure(reason = DecodeFailure.UNRECOVERABLE_FEC, detail = null),
            payloadText = "",
            onPayloadTextChange = {},
            maxPayloadBytes = 1024,
            micPermissionDenied = false,
            protocol = NightjarAcoustics.Protocol.AUDIBLE,
            onProtocolChange = {},
            symbolRate = NightjarAcoustics.SymbolRate.NORMAL,
            onSymbolRateChange = {},
            onTransmit = {},
            onSave = {},
            onShare = {},
            fileActionMessage = null,
            fileActionBusyLabel = null,
            onImport = {},
            onToggleListen = {},
            onBack = {},
        )
    }
}

/** Task #27: the full-window-timeout, nothing-found case — the copy the real test needed. */
@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewDecodedFailureTimedOut() {
    PreviewSurface {
        AcousticModemContent(
            status = ModemStatus.DecodedFailure(
                reason = DecodeFailure.NO_PAYLOAD_FOUND,
                detail = null,
                timedOut = true,
            ),
            payloadText = "",
            onPayloadTextChange = {},
            maxPayloadBytes = 1024,
            micPermissionDenied = false,
            protocol = NightjarAcoustics.Protocol.AUDIBLE,
            onProtocolChange = {},
            symbolRate = NightjarAcoustics.SymbolRate.NORMAL,
            onSymbolRateChange = {},
            onTransmit = {},
            onSave = {},
            onShare = {},
            fileActionMessage = null,
            fileActionBusyLabel = null,
            onImport = {},
            onToggleListen = {},
            onBack = {},
        )
    }
}

/** Task #32: a picked file that parsed fine but at the wrong sample rate/channel count. */
@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewImportFailed() {
    PreviewSurface {
        AcousticModemContent(
            status = ModemStatus.ImportFailed("file is 44100Hz stereo, need 48000Hz mono."),
            payloadText = "",
            onPayloadTextChange = {},
            maxPayloadBytes = 1024,
            micPermissionDenied = false,
            protocol = NightjarAcoustics.Protocol.AUDIBLE,
            onProtocolChange = {},
            symbolRate = NightjarAcoustics.SymbolRate.NORMAL,
            onSymbolRateChange = {},
            onTransmit = {},
            onSave = {},
            onShare = {},
            fileActionMessage = null,
            fileActionBusyLabel = null,
            onImport = {},
            onToggleListen = {},
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
