package dev.herakles.nightjar

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.incoming.IncomingOutcome
import dev.herakles.nightjar.incoming.IncomingPipeline
import dev.herakles.nightjar.incoming.SqueezedContainer
import dev.herakles.nightjar.incoming.probeOutcomeName
import dev.herakles.nightjar.modules.ModuleStubScreen
import dev.herakles.nightjar.modules.acoustic.AcousticModemScreen
import dev.herakles.nightjar.modules.audiostego.AudioStegoScreen
import dev.herakles.nightjar.modules.detector.DetectorScreen
import dev.herakles.nightjar.modules.fireflyjar.FireflyDatabase
import dev.herakles.nightjar.modules.fireflyjar.FireflyMediaStore
import dev.herakles.nightjar.modules.fireflyjar.FireflyRepository
import dev.herakles.nightjar.modules.fireflyjar.JarDetailScreen
import dev.herakles.nightjar.modules.fireflyjar.JarShelfScreen
import dev.herakles.nightjar.modules.imagestego.ImageStegoScreen
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.picker.ModulePicker
import dev.herakles.nightjar.ui.theme.BgBase
import dev.herakles.nightjar.ui.theme.NightjarTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home screen entry point: module-picker → one of the real module screens. Task #4 built
 * this navigation shell against `ModuleStubScreen`; task #24 wired in the first 3 real
 * screens (`AcousticModemScreen`, `DetectorScreen`, `ImageStegoScreen`, tasks #7/#10/#13
 * against concrete carriers/detectors from tasks #5/#6/#9/#11/#12); a later task added
 * `AudioStegoScreen` (Module 2, `AudioStegoCarrier`). `ModuleStubScreen` stays imported and
 * reachable via [Screen.ModuleStub] for any future module that lands without a real screen
 * yet — no androidx.navigation dependency, a plain sealed-state switch is enough, matching
 * whisper-voice-app's MainActivity pattern.
 *
 * `launchMode="singleTask"` (v6 receive plumbing, AndroidManifest.xml, gate-31): a share-sheet
 * or "open with" `Intent` can arrive while nightjar isn't running at all (ordinary `onCreate`),
 * OR while it's already running -- foregrounded, backgrounded, or mid-session on some other
 * screen. With the default `standard` launch mode, that second case would stack a BRAND NEW
 * `MainActivity` instance on top of the existing one (fresh in-memory `Screen` state, a second
 * live `FireflyRepository`/Room connection, and a duplicate entry left behind in the back stack
 * once the operator backs out of the incoming-file screen). `singleTask` guarantees at most one
 * instance ever exists: a second incoming `Intent` is delivered to that SAME instance via
 * [onNewIntent] instead, preserving whatever `Screen` the operator was already on. This app has
 * exactly one `Activity` total, so `singleTask`'s usual caveat (it clears every activity ABOVE
 * itself in the task when re-brought to front) never has anything to clear here.
 */
class MainActivity : ComponentActivity() {
    /** The most recent `Intent` this Activity should route through [IncomingPipeline] --
     *  [onCreate]'s own launch Intent, or whatever [onNewIntent] hands in later. Read by
     *  [NightjarApp] via a `LaunchedEffect` keyed on this value; see [IncomingIntentGuard] for
     *  how re-processing the same Intent object (e.g. after an Activity recreation that isn't a
     *  new share) is avoided. */
    private val incomingIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incomingIntent.value = intent
        setContent {
            NightjarTheme {
                NightjarApp(incomingIntent = incomingIntent.value)
            }
        }
    }

    /** `singleTask` (see class KDoc) routes every subsequent share/open-with `Intent` here
     *  instead of a fresh [onCreate]. `setIntent` keeps `getIntent()` consistent with what's
     *  actually being handled, matching the platform's own documented contract for this
     *  override. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntent.value = intent
    }
}

/**
 * Process-scoped "last `Intent` object routed through [IncomingPipeline]" latch (v6 receive
 * plumbing, mirrors [OrphanSweepGuard]'s pattern below). An Activity recreation that ISN'T a new
 * share/open-with (e.g. a locale or font-scale change `configChanges="uiMode"` doesn't cover)
 * re-runs `onCreate` with the exact same `Intent` OBJECT REFERENCE `getIntent()` already held --
 * comparing by `===` distinguishes that case (skip -- already routed) from a genuinely new
 * incoming `Intent` (always a distinct object), without needing any Parcelable/id-based identity
 * scheme for `Intent` itself.
 */
private object IncomingIntentGuard {
    @Volatile var lastRouted: Intent? = null
}

private sealed interface Screen {
    /** App root (task #3, "Firefly Jar" v3). Long-press reveal (later task) reaches [Picker]. */
    data object JarShelf : Screen
    data object Picker : Screen
    data object AcousticModem : Screen
    data object ImageSteganography : Screen
    data object Detector : Screen
    data object AudioSteganography : Screen

    /** Firefly detail view for a jar on [JarShelf] (task #3 routing; real screen landed task
     *  #10 — see `JarDetailScreen.kt`). */
    data class JarDetail(val module: Module) : Screen

    /** Reachable only for a future module without a real screen yet (see MainActivity.kt KDoc). */
    data class ModuleStub(val module: Module) : Screen

    /**
     * Landed after routing a share/open-with/"catch from a photo or file" `Intent` through
     * [IncomingPipeline] (v6 receive plumbing, gate-31/32). [IncomingPlaceholderScreen] renders
     * [outcome] as plain text for now -- task W2-1 replaces this with the real jar-voice copy.
     */
    data class Incoming(val outcome: IncomingOutcome) : Screen
}

/**
 * Process-scoped latch for the orphan sweep (task #5, gate-20, INV-6): `LaunchedEffect(Unit)`
 * re-runs on activity recreation (rotation, config change) even though the process survives, and
 * the sweep is destructive against the media directory -- a re-run mid-catch (file written, row
 * not yet inserted) would delete that firefly's just-caught media. `@Volatile` because the sweep
 * itself hops onto [Dispatchers.IO], a different thread than the composition reads this from.
 */
private object OrphanSweepGuard {
    @Volatile var hasRun = false
}

/** Reads [repository]'s stored-media snapshot and forwards it to [DebugProbe] (gate-20 v4 probe
 *  contract) -- the one place both [NightjarApp] call sites route through so they can't drift
 *  into reporting different fields. */
private suspend fun reportStoredMediaProbe(repository: FireflyRepository) {
    val snapshot = repository.probeSnapshot()
    DebugProbe.reportStoredMedia(
        recordCount = snapshot.recordCount,
        recordsWithMediaCount = snapshot.recordsWithMediaCount,
        totalMediaBytes = snapshot.totalMediaBytes,
        orphanFileCount = snapshot.orphanFileCount,
    )
}

/**
 * Encodes/decodes [Screen] for [rememberSaveable] (F-03 fix, M-07 dup): `Screen` isn't directly
 * Parcelable/primitive, so a plain `rememberSaveable { mutableStateOf(...) }` can't hold it
 * without this. A plain `String` bundle value is enough since every case either has no payload
 * or carries just a [Module] -- `Module.name`/[Module.valueOf] round-trips that exactly, the same
 * way `debugLabel` below already encodes a [Screen] as a string for the probe.
 *
 * Without this, navigation position (and an open firefly-detail popup, see JarDetailScreen.kt's
 * own `rememberSaveable` fix) was silently lost on any Activity recreation that wasn't a
 * rotation -- e.g. toggling system dark mode mid-catch dumped the user back on the jar shelf even
 * though the process and the Room DB survived intact (OrphanSweepGuard's own KDoc already
 * documents that survival for the sweep guard; this closes the same gap for navigation).
 *
 * [Screen.Incoming] deliberately does NOT round-trip its [IncomingOutcome] through this
 * String-keyed scheme (it carries raw payload/carrier [ByteArray]s with no compact string
 * encoding worth inventing for a transient, one-shot receive event) -- `save` encodes it as the
 * bare marker `"Incoming"`, and `restore` resolves that marker straight to [Screen.JarShelf],
 * the same fail-safe fallback the `else` branch below already uses for an unrecognized encoding.
 * Nothing is lost by this: a [Screen.Incoming.outcome] of `Caught` was already persisted via
 * [FireflyRepository] before navigation, so surviving process death only means the transient
 * outcome message itself doesn't reappear, not that the catch itself is undone.
 */
private val ScreenSaver: Saver<Screen, String> = Saver(
    save = { screen ->
        when (screen) {
            is Screen.JarShelf -> "JarShelf"
            is Screen.Picker -> "Picker"
            is Screen.AcousticModem -> "AcousticModem"
            is Screen.ImageSteganography -> "ImageSteganography"
            is Screen.Detector -> "Detector"
            is Screen.AudioSteganography -> "AudioSteganography"
            is Screen.JarDetail -> "JarDetail:${screen.module.name}"
            is Screen.ModuleStub -> "ModuleStub:${screen.module.name}"
            is Screen.Incoming -> "Incoming"
        }
    },
    restore = { encoded ->
        when {
            encoded == "JarShelf" -> Screen.JarShelf
            encoded == "Picker" -> Screen.Picker
            encoded == "AcousticModem" -> Screen.AcousticModem
            encoded == "ImageSteganography" -> Screen.ImageSteganography
            encoded == "Detector" -> Screen.Detector
            encoded == "AudioSteganography" -> Screen.AudioSteganography
            encoded.startsWith("JarDetail:") ->
                Screen.JarDetail(Module.valueOf(encoded.removePrefix("JarDetail:")))
            encoded.startsWith("ModuleStub:") ->
                Screen.ModuleStub(Module.valueOf(encoded.removePrefix("ModuleStub:")))
            // Unreachable from this Saver's own `save` above -- present anyway (this codebase's
            // unreachable-but-present discipline, AudioStegoCarrier.kt's DecodeFailure precedent)
            // so a future saved-state format change fails safe (back to the root) rather than
            // crashing restore. Also where the deliberate "Incoming" -> JarShelf fallback (this
            // KDoc's own paragraph above) actually resolves, since it's never a distinct branch.
            else -> Screen.JarShelf
        }
    },
)

/** Label the `COVERT_DEBUG` probe (task #18) reports for each [Screen] value. */
private val Screen.debugLabel: String
    get() = when (this) {
        is Screen.JarShelf -> "jar_shelf"
        is Screen.Picker -> "picker"
        is Screen.AcousticModem -> "module:${Module.ACOUSTIC_MODEM.name}"
        is Screen.ImageSteganography -> "module:${Module.IMAGE_STEGANOGRAPHY.name}"
        is Screen.Detector -> "module:${Module.DETECTOR.name}"
        is Screen.AudioSteganography -> "module:${Module.AUDIO_STEGANOGRAPHY.name}"
        is Screen.JarDetail -> "jar_detail:${module.name}"
        is Screen.ModuleStub -> "module_stub:${module.name}"
        is Screen.Incoming -> "incoming:${outcome.probeOutcomeName}"
    }

@Composable
fun NightjarApp(incomingIntent: Intent? = null) {
    var screen: Screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.JarShelf) }

    val context = LocalContext.current
    val fireflyDao = remember { FireflyDatabase.getInstance(context).fireflyDao() }
    // Task #4 (gate-20, INV-6): rows and files inseparable. The jar UI (shelf, detail, and the
    // three catch flows) now threads FireflyRepository throughout -- see FireflyRepository.kt's
    // file KDoc for the write-then-insert path.
    val fireflyRepository = remember { FireflyRepository(fireflyDao, FireflyMediaStore(context)) }

    // Task #5 (gate-20, INV-6): reclaim orphaned media files once per process, at start-up,
    // before the jar UI is reachable. Guarded by OrphanSweepGuard so activity recreation
    // (rotation/config change) can't trigger a second, destructive sweep mid-catch.
    LaunchedEffect(Unit) {
        if (!OrphanSweepGuard.hasRun) {
            OrphanSweepGuard.hasRun = true
            withContext(Dispatchers.IO) { fireflyRepository.sweepOrphans() }
            // Reports the freshest possible orphan_file_count right after the sweep -- the sweep
            // is the one stored-media mutation that doesn't touch a row, so it's the one case the
            // LaunchedEffect(allFireflies) below (keyed on the reactive record list) wouldn't
            // otherwise catch.
            reportStoredMediaProbe(fireflyRepository)
        }
    }

    // Gate-20 v4 probe contract (spec.md Runtime Verification Surface): stored-media state is
    // queryable via COVERT_DEBUG whenever it changes. Keyed on the reactive all-fireflies list, so
    // this re-reports after every row mutation -- a catch in any of the 3 creation jars, clear-all,
    // or a per-firefly delete -- without this file needing an explicit call at each of those sites
    // (several of which live in modules this file's owner doesn't touch).
    val allFireflies by fireflyRepository.observeAll().collectAsState(initial = null)
    LaunchedEffect(allFireflies) {
        if (allFireflies != null) {
            reportStoredMediaProbe(fireflyRepository)
        }
    }

    // Task #18 probe wiring: every screen change (including the initial composition,
    // since LaunchedEffect runs immediately too) is reported to DebugProbe, so
    // `hek logcat --filter COVERT_DEBUG` shows the current module-picker screen without
    // falling back to a screenshot. No-op in release builds (see DebugProbe.kt).
    LaunchedEffect(screen) {
        DebugProbe.reportScreen(screen.debugLabel)
    }

    // v6 receive plumbing (spec.md gate-31/32): route a share-sheet/open-with Intent through
    // IncomingPipeline and land on Screen.Incoming with its outcome. Keyed on incomingIntent so
    // MainActivity's onNewIntent (singleTask, see its class KDoc) re-triggers this for a second
    // share while nightjar is already running. IncomingIntentGuard (top of file) skips
    // reprocessing the SAME Intent object reference across an Activity recreation that wasn't a
    // new share (see its own KDoc for why reference equality is the right check here).
    LaunchedEffect(incomingIntent) {
        val current = incomingIntent ?: return@LaunchedEffect
        if (current === IncomingIntentGuard.lastRouted) return@LaunchedEffect
        val action = when (current.action) {
            Intent.ACTION_SEND -> "SEND"
            Intent.ACTION_VIEW -> "VIEW"
            else -> null // e.g. the LAUNCHER's ACTION_MAIN -- nothing to route
        } ?: return@LaunchedEffect
        val uri = incomingStreamUri(current) ?: return@LaunchedEffect
        IncomingIntentGuard.lastRouted = current
        val outcome = withContext(Dispatchers.IO) { IncomingPipeline.route(context, uri, action) }
        screen = Screen.Incoming(outcome)
    }

    // System back / predictive-back gesture returns to JarShelf (the new root) from
    // Picker, a jar detail view, or the incoming-outcome screen, or to Picker from any
    // module/stub screen reached through it. Disabled on JarShelf itself so back there
    // falls through to the default (exit app) behavior.
    BackHandler(enabled = screen !is Screen.JarShelf) {
        screen = when (screen) {
            is Screen.Picker, is Screen.JarDetail, is Screen.Incoming -> Screen.JarShelf
            else -> Screen.Picker
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        when (val current = screen) {
            is Screen.JarShelf -> JarShelfScreen(
                repository = fireflyRepository,
                onSelectModule = { module -> screen = Screen.JarDetail(module) },
                onRevealTechnicalMode = { screen = Screen.Picker },
            )
            is Screen.JarDetail -> JarDetailScreen(
                module = current.module,
                repository = fireflyRepository,
                onBack = { screen = Screen.JarShelf },
            )
            is Screen.Picker -> ModulePicker(
                onSelect = { module ->
                    screen = when (module) {
                        Module.ACOUSTIC_MODEM -> Screen.AcousticModem
                        Module.IMAGE_STEGANOGRAPHY -> Screen.ImageSteganography
                        Module.DETECTOR -> Screen.Detector
                        Module.AUDIO_STEGANOGRAPHY -> Screen.AudioSteganography
                    }
                },
                // U-01 (gate-12): the picker's own visible "back to the jar" link -- same
                // destination the BackHandler above already sends Screen.Picker to.
                onBack = { screen = Screen.JarShelf },
            )
            is Screen.AcousticModem -> AcousticModemScreen(
                // Task #30: protocol/symbol-rate selector on the screen rebuilds the carrier
                // through this factory rather than routing to one fixed AcousticCarrier()
                // instance — see AcousticModemScreen.kt's file KDoc.
                carrierFactory = { protocol, symbolRate ->
                    AcousticCarrier(protocol = protocol, symbolRate = symbolRate)
                },
                onBack = { screen = Screen.Picker },
            )
            is Screen.ImageSteganography -> ImageStegoScreen(
                carrierFactory = { bitmap -> ImageStegoCarrier(bitmap) },
                detector = ImageSteganalysis(),
                onBack = { screen = Screen.Picker },
            )
            is Screen.Detector -> DetectorScreen(
                detector = AcousticDetector(),
                onBack = { screen = Screen.Picker },
            )
            is Screen.AudioSteganography -> AudioStegoScreen(
                carrierFactory = { cover, technique -> AudioStegoCarrier(cover, technique) },
                detector = AudioStegDetector(),
                onBack = { screen = Screen.Picker },
            )
            is Screen.ModuleStub -> ModuleStubScreen(
                module = current.module,
                onBack = { screen = Screen.Picker },
            )
            is Screen.Incoming -> IncomingPlaceholderScreen(
                outcome = current.outcome,
                onBack = { screen = Screen.JarShelf },
            )
        }
    }
}

/**
 * Reads the `Uri` a share-sheet/open-with [intent] actually carries -- `EXTRA_STREAM` for
 * `ACTION_SEND`, `data` for `ACTION_VIEW` -- or `null` for anything else (including a
 * malformed/missing extra, which this app treats as "nothing to route" rather than a crash).
 *
 * `getParcelableExtra(String, Class<T>)`'s typed overload only exists from API 33 (Tiramisu)
 * onward; this app's `minSdk` is 31, so the deprecated single-arg overload is still needed below
 * API 33 -- exactly the same `Build.VERSION.SDK_INT` branch pattern Android's own docs recommend
 * for this gap.
 */
@Suppress("DEPRECATION")
private fun incomingStreamUri(intent: Intent): Uri? = when (intent.action) {
    Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }
    Intent.ACTION_VIEW -> intent.data
    else -> null
}

/**
 * MINIMAL placeholder for the v6 receive outcome screen (spec.md v6 receive-plumbing task W1-2:
 * "Render it with a MINIMAL placeholder composable (plain text of the outcome plus 'back to the
 * jar')"). Task W2-1 replaces this with the real jar-voice copy from
 * design/screen-flow.md's "five outcomes" table -- deliberately no styling work happens here.
 */
@Composable
private fun IncomingPlaceholderScreen(outcome: IncomingOutcome, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = incomingPlaceholderText(outcome))
        TextButton(onClick = onBack) {
            Text(text = "back to the jar")
        }
    }
}

/** Plain-text rendering of [outcome] for [IncomingPlaceholderScreen] -- not jar-voice copy
 *  (task W2-1's job), just enough to verify each of INV-12's five outcomes on screen/logcat. */
private fun incomingPlaceholderText(outcome: IncomingOutcome): String = when (outcome) {
    is IncomingOutcome.Caught ->
        "caught: ${outcome.technique} in ${outcome.module.jarName} -- ${outcome.payload.size} bytes"
    is IncomingOutcome.Squeezed -> when (outcome.container) {
        SqueezedContainer.LOSSY_IMAGE -> "squeezed: lossy image container, nothing decoded"
        SqueezedContainer.COMPRESSED_AUDIO -> "squeezed: compressed audio container, nothing decoded"
    }
    is IncomingOutcome.Damaged -> "damaged" + (outcome.detail?.let { ": $it" } ?: "")
    is IncomingOutcome.NoFirefly -> "no firefly"
    is IncomingOutcome.Unsupported -> "unsupported" + (outcome.reason?.let { ": $it" } ?: "")
}
