package dev.herakles.nightjar.modules.detector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AudioEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.herakles.nightjar.AcousticDetector
import dev.herakles.nightjar.CovertDetector
import dev.herakles.nightjar.DebugProbe
import dev.herakles.nightjar.DetectionResult
import dev.herakles.nightjar.MicCapture
import dev.herakles.nightjar.ModuleId
import dev.herakles.nightjar.NightjarAcoustics
import dev.herakles.nightjar.PcmAudio
import dev.herakles.nightjar.R
import dev.herakles.nightjar.WavFile
import dev.herakles.nightjar.modules.fireflyjar.FireflyPlayer
import dev.herakles.nightjar.modules.fireflyjar.FireflyRepository
import androidx.compose.material3.HorizontalDivider
import dev.herakles.nightjar.trail.PracticeFireflies
import dev.herakles.nightjar.trail.TrailStateStore
import dev.herakles.nightjar.trail.TrailStep
import dev.herakles.nightjar.trail.trailHighlight
import dev.herakles.nightjar.ui.theme.AccentSignal
import dev.herakles.nightjar.ui.theme.BgBase
import dev.herakles.nightjar.ui.theme.BorderDefault
import dev.herakles.nightjar.ui.theme.FireflyReceived
import dev.herakles.nightjar.ui.theme.JarActionLookBorder
import dev.herakles.nightjar.ui.theme.JarActionLookFill
import dev.herakles.nightjar.ui.theme.JarHistoryRowFill
import dev.herakles.nightjar.ui.theme.JarTextPrimary
import dev.herakles.nightjar.ui.theme.JarTextSecondary
import dev.herakles.nightjar.ui.theme.JarTextTertiary
import dev.herakles.nightjar.ui.theme.JarType
import dev.herakles.nightjar.ui.theme.JarWatchingDim
import dev.herakles.nightjar.ui.theme.TextPrimary
import dev.herakles.nightjar.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Task #10 — Module 5 (detector) real UI, replacing `ModuleStubScreen` for
 * [dev.herakles.nightjar.picker.Module.DETECTOR].
 *
 * Written against the [CovertDetector] interface only, same discipline task #7 used for
 * `AcousticModemScreen`: no reference to the concrete `AcousticDetector` (task #9)
 * anywhere in this file. It takes the detector as a constructor/parameter dependency, so
 * whoever wires `Module.DETECTOR` in `MainActivity.kt`'s `when` branch just swaps that one
 * case to call `DetectorScreen(detector = AcousticDetector(), onBack = ...)` — a one-line
 * change, same wiring note task #7 left for the modem screen.
 *
 * This is the defensive counterpart to the acoustic modem, not an offensive tool
 * (covert-data/CLAUDE.md §1 — authorized personal AI security research lab, Track 4): it
 * passively scores the mic stream for the app's own Module 3 tone-grid signature (spec
 * INV-4) and never demodulates or recovers a payload — see [CovertDetector]'s KDoc for
 * the one-way-analyzer contract that enforces that split at the type level.
 *
 * Scope boundary (architecture.md "Module Interface" §4): [CovertDetector] is a pure
 * analyzer over in-memory buffers, not the audio transport. This screen owns that
 * transport — `AudioRecord` capture, fed to `detector.analyze()` in a loop — since
 * architecture.md draws that line at the UI/transport layer, same split task #7 used for
 * the modem's `AudioTrack`/`AudioRecord`.
 */

/**
 * One flagged transition, logged for the scrolling history list below the live readout.
 * A "detection" is a discrete event (clear -> flagged), not a row per analysis frame —
 * see [DetectorController] for why.
 */
data class DetectionHistoryEntry(
    val timestampMillis: Long,
    val confidence: Float,
)

/** How many rows the scrolling history keeps before dropping the oldest. */
private const val MAX_HISTORY_ENTRIES = 50

/**
 * Stateful root: owns the [DetectorController] and the RECORD_AUDIO permission flow.
 * [DetectorContent] below is the pure/previewable UI.
 */
@Composable
fun DetectorScreen(detector: CovertDetector<PcmAudio>, onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(detector) { DetectorController(detector, context.applicationContext) }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    // mic-4: stop the passive listen loop when the app is backgrounded, rather than leaving the
    // mic capturing (and the live confidence readout burning battery) behind a closed/minimized
    // app. No time bound is needed on the loop itself once it reliably stops here.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        controller.stopListening()
    }

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

    DetectorContent(
        isListening = controller.isListening,
        result = controller.lastResult,
        history = controller.history,
        flagThreshold = detector.flagThreshold,
        micPermissionDenied = micPermissionDenied,
        micError = controller.micError,
        onToggleListen = {
            if (controller.isListening) {
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
 * Task #14 — the Firefly Jar dispatcher's real flow for
 * [dev.herakles.nightjar.picker.Module.DETECTOR] ("the meadow" —
 * [dev.herakles.nightjar.picker.JarRole.WATCHING]). Named `jarWatchFlow`, not
 * `jarCatchFlow` — this module is passive-only, no encode/transmit verb, matching the
 * distinction [dev.herakles.nightjar.picker.JarRole] draws. `JarCatchFlows.kt`'s
 * `catchFlowFor` dispatcher calls this directly.
 *
 * Re-skins [DetectorContent]'s existing live confidence readout + flagged-history list
 * (design/screen-flow.md § Screen 7 "the watching jar" wireframe, shown as "the meadow" since v6) rather than showing
 * firefly dots — this module never creates or receives a payload (architecture.md § 3,
 * spec.md INV-4), so it never calls [FireflyRepository.insert]; [repository] is accepted only for
 * signature symmetry with the other three modules' jar flows and is otherwise unused
 * here. [onExit] is likewise unused — there's no "catch completed" moment for a passive
 * watcher to fire it from; `JarDetailScreen`'s own "back to the shelf" row already
 * covers leaving this screen.
 *
 * This composable owns its own [AcousticDetector] instance — a second, independent one
 * from whichever `DetectorScreen` (the technical picker's route) may also be running,
 * same as `MainActivity.kt` constructing its own `AcousticDetector()` for that screen.
 * `DetectorScreen`'s own file KDoc keeps *that* composable decoupled from the concrete
 * detector for dependency-injection/testability reasons; this stateful root has no such
 * caller to inject one, so it constructs its own directly, matching how
 * `JarCatchFlows.kt`'s other three branches each wire up their own real
 * `CovertCarrier` construction (architecture.md § Firefly Jar § 6).
 */
@Composable
fun jarWatchFlow(repository: FireflyRepository, trailStore: TrailStateStore, onExit: () -> Unit) {
    val context = LocalContext.current
    val detector = remember { AcousticDetector() }
    val controller = remember(detector) { DetectorController(detector, context.applicationContext) }

    // W2-3 riddle trail (design/riddle-trail.md § Step 4, gate-36): the meadow holds no
    // fireflies, so its own step has no decode to catch -- it completes on a real flagged
    // detection while watching (Branch A) during this trail-watch session, or on an honest,
    // bounded timeout (Branch B). [practicePlayer] plays the singing jar's own practice WAV back
    // through the speaker ~1.5s after "watch" starts, stopped whenever the watch session ends --
    // the same shared `AudioTrack` transport (`FireflyPlayer.kt`) every other clip in this app
    // already plays through, rather than a second, `MediaPlayer`-based one.
    val audioManager = context.getSystemService(AudioManager::class.java)
    val practicePlayer = remember { FireflyPlayer(audioManager) }

    DisposableEffect(controller, practicePlayer) {
        onDispose {
            controller.dispose()
            practicePlayer.release()
        }
    }
    // mic-4: stop the passive listen loop when the app is backgrounded, rather than leaving the
    // mic capturing behind a closed/minimized app. No time bound is needed on the loop itself
    // once it reliably stops here.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        controller.stopListening()
    }

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

    val trailState by trailStore.state.collectAsState()
    val trailActive = trailState.currentStep == TrailStep.MEADOW

    var showHonestFallback by remember { mutableStateOf(false) }
    var trailAdvancePending by remember { mutableStateOf(false) }

    // Branch A: a real flagged rising edge during an active trail-watch session advances the
    // step immediately (gate-38's "stops on tap" -- here, stops on the detection itself).
    // [trailAdvancePending] is a same-session latch, not a persisted flag: it only ever needs to
    // stop a *second* advance() call within the same watch session (the window between this
    // effect relaunching on the next ~85ms analysis frame and trailState/trailActive catching up
    // to the first call's own advance()), and is reset at the top of every fresh watch session
    // below.
    LaunchedEffect(trailActive, controller.isListening, controller.lastResult) {
        if (trailActive && controller.isListening && !trailAdvancePending && controller.lastResult?.flagged == true) {
            trailAdvancePending = true
            practicePlayer.stop()
            trailStore.advance(TrailStep.MEADOW)
        }
    }

    // The auto-play + Branch B honest-fallback timer. Cancelled outright (via the LaunchedEffect
    // key change) the instant watching stops or the trail step is no longer active -- including
    // by Branch A's own advance() above, which flips trailState.currentStep away from MEADOW.
    LaunchedEffect(controller.isListening, trailActive) {
        if (!trailActive || !controller.isListening) {
            showHonestFallback = false
            practicePlayer.stop()
            return@LaunchedEffect
        }
        trailAdvancePending = false
        showHonestFallback = false
        val startedAtMillis = System.currentTimeMillis()
        delay(MEADOW_PRACTICE_PLAYBACK_DELAY_MS)
        val practicePcm = withContext(Dispatchers.IO) {
            val practiceFile = PracticeFireflies.practiceFile(context, PracticeFireflies.Jar.SINGING)
            if (practiceFile.exists()) WavFile.decodePcm16(practiceFile.readBytes()) else null
        }
        if (practicePcm != null) {
            practicePlayer.play(practicePcm.samples, practicePcm.numChannels)
        }
        while (isActive && !trailAdvancePending) {
            val elapsedMillis = System.currentTimeMillis() - startedAtMillis
            if (meadowHonestFallbackDue(elapsedMillis)) {
                showHonestFallback = true
                break
            }
            delay(MEADOW_FALLBACK_POLL_INTERVAL_MS)
        }
    }

    JarWatchContent(
        isListening = controller.isListening,
        result = controller.lastResult,
        history = controller.history,
        micPermissionDenied = micPermissionDenied,
        micError = controller.micError,
        onToggleWatch = {
            if (controller.isListening) {
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
        watchHighlighted = trailActive,
        honestFallbackVisible = showHonestFallback,
    )
}

/** Delay between "watch" starting and the singing jar's practice WAV playing back through the
 *  speaker (design/riddle-trail.md § Step 4: "on the order of 1-2s after watch starts"). */
internal const val MEADOW_PRACTICE_PLAYBACK_DELAY_MS = 1_500L

/** Branch B's bounded watch window (design/riddle-trail.md § Step 4 Branch B: "candidate: 20s,
 *  matching the acoustic modem's own MAX_LISTEN_SECONDS precedent" --
 *  `AcousticModemScreen.kt`'s `MAX_LISTEN_SECONDS = 20.0`). */
internal const val MEADOW_HONEST_FALLBACK_TIMEOUT_MS = 20_000L

/** How often the watch-session coroutine re-checks [meadowHonestFallbackDue] while waiting.
 *  Coarse enough not to matter for battery/CPU, fine enough that the fallback copy appears
 *  promptly once the window closes. */
private const val MEADOW_FALLBACK_POLL_INTERVAL_MS = 500L

/**
 * True once Branch B's honest-fallback copy should show: [elapsedMillisSinceWatchStarted] (since
 * this trail-watch session's own "watch" tap) has reached [MEADOW_HONEST_FALLBACK_TIMEOUT_MS]
 * with no flagged detection. A plain, `internal` pure function -- factored out of the coroutine
 * loop above purely so `MeadowTrailTest` can exercise the threshold arithmetic directly, the same
 * reasoning `AudioStegoScreen.kt`'s `isFireflyCatchEvent` is pulled out of its own composable.
 */
internal fun meadowHonestFallbackDue(elapsedMillisSinceWatchStarted: Long): Boolean =
    elapsedMillisSinceWatchStarted >= MEADOW_HONEST_FALLBACK_TIMEOUT_MS

/**
 * Pure/previewable content for [jarWatchFlow] — no [FireflyDao], no [AcousticDetector],
 * same stateful-root/pure-content split every other screen in this app uses. Copy follows
 * design/screen-flow.md § Screen 7's watching-jar copy-mapping table verbatim
 * (`flagged` -> "something's out there", `clear` -> "all quiet"); [FireflyReceived] reuses
 * identity.md's `AccentSignal` cyan value on purpose, same "a message arrived" event seen
 * through the jar skin (design/firefly-jar-identity.md § Palette). No raw analyzer
 * [DetectionResult.detail] string or byte estimate here — those are the technical
 * screen's deliberate rough edge (see [ReadoutBlock]); the wireframe this re-skins shows
 * only the glow-strength number and the flagged word.
 */
@Composable
private fun JarWatchContent(
    isListening: Boolean,
    result: DetectionResult?,
    history: List<DetectionHistoryEntry>,
    micPermissionDenied: Boolean,
    micError: String?,
    onToggleWatch: () -> Unit,
    // W2-3 (gate-36/gate-38): true while the meadow's trail step is the active one -- glows the
    // watch/stop row. Default keeps every existing @Preview call site compiling unchanged.
    watchHighlighted: Boolean = false,
    // W2-3 (gate-36 Branch B): shown once a trail-watch session has run
    // MEADOW_HONEST_FALLBACK_TIMEOUT_MS with nothing flagged -- design/riddle-trail.md's honest
    // copy plus a pointer to skip or try the singing jar for real.
    honestFallbackVisible: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // DESIGN_SPEC.md §5 1h "watch button" — purple-tinted, 12dp radius, the
            // primary-button tier (the watching jar has one action, not a catch/look pair).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(JarWatchingDim.copy(alpha = 0.1f))
                    .border(width = 1.dp, color = JarWatchingDim.copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp))
                    .clickable(onClick = onToggleWatch)
                    .trailHighlight(active = watchHighlighted, description = if (isListening) "stop" else "watch")
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = if (isListening) "stop" else "watch",
                    style = JarType.ButtonLabel,
                    color = JarTextSecondary,
                )
            }
            if (micPermissionDenied) {
                Text(
                    text = "the meadow needs microphone access to watch.",
                    style = JarType.Body,
                    color = JarTextSecondary,
                )
            }
            // mic-1: visible instead of crashing when every AudioSource tier fails to initialize.
            if (micError != null) {
                Text(text = micError, style = JarType.Body, color = JarTextSecondary)
            }
            if (honestFallbackVisible) {
                Text(
                    text = stringResource(R.string.trail_meadow_honest_fallback),
                    style = JarType.Body,
                    color = JarTextSecondary,
                )
            }
        }

        JarGlowReadout(result = result)

        JarSpottedHistory(history = history)
    }
}

/** DESIGN_SPEC.md §5 1h "confidence readout card" — cyan-tinted, 10dp radius. Reuses the
 *  look/spot cyan tokens: this readout is the same "did a signal arrive" concept the other
 *  three jars' spot/look treatment already colors cyan. */
@Composable
private fun JarGlowReadout(result: DetectionResult?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(JarActionLookFill)
            .border(width = 1.dp, color = JarActionLookBorder, shape = RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = "glow strength", style = JarType.Footer, color = JarTextTertiary)
        if (result == null) {
            Text(
                text = "glow strength appears once you start watching",
                style = JarType.Body,
                color = JarTextSecondary,
            )
        } else {
            Text(
                text = "${(result.confidence * 100).roundToInt()}%",
                style = JarType.Numeral,
                color = JarTextPrimary,
            )
            Text(
                text = if (result.flagged) "something's out there" else "all quiet",
                style = JarType.TileTitle,
                // DESIGN_SPEC.md §7 item 1: the mockup hardcodes this label to cyan regardless
                // of value — a documented bug. The history-row convention below (cyan when
                // flagged, cream otherwise) is the intended semantic; implemented here instead
                // of reproduced.
                color = if (result.flagged) FireflyReceived else JarTextPrimary,
            )
        }
    }
}

@Composable
private fun JarSpottedHistory(history: List<DetectionHistoryEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "spotted", style = JarType.SectionLabel, color = JarTextTertiary)
        if (history.isEmpty()) {
            Text(text = "nothing spotted yet", style = JarType.Footer, color = JarWatchingDim)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                history.forEach { entry -> JarSpottedRow(entry) }
            }
        }
    }
}

private val jarSpottedTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** DESIGN_SPEC.md §5 1h "history row" — a faint neutral fill, no border, 6dp radius (the
 *  technique-chip/history-row shape tier). Timestamp-only mono use, matching [HistoryRow]'s
 *  precedent on the technical screen. */
@Composable
private fun JarSpottedRow(entry: DetectionHistoryEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(JarHistoryRowFill)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = jarSpottedTimeFormat.format(entry.timestampMillis),
            style = JarType.Timestamp,
            color = JarTextSecondary,
        )
        Text(
            text = glowLabel(entry.confidence),
            style = JarType.TileCaption,
            // Every entry here is already a rising-edge-into-flagged event (see this file's
            // own DetectorController.onResult doc) — always the "flagged" branch of
            // DESIGN_SPEC.md §5 1h's conditional history-percentage color, never "clear".
            color = FireflyReceived,
        )
    }
}

/**
 * Softened word in place of the technical screen's raw percentage
 * (design/screen-flow.md § Screen 7 example rows: "14:22 bright" / "14:21 faint"). History
 * entries are always >= [CovertDetector.flagThreshold] (rising-edge-into-flagged events
 * only, see [DetectorController.onResult]), so the buckets below split that already-flagged
 * range into three shades rather than needing to cover the full [0,1] range.
 */
private fun glowLabel(confidence: Float): String = when {
    confidence >= 0.6f -> "bright"
    confidence >= 0.35f -> "steady"
    else -> "faint"
}

// --- Previews: JarWatchContent is pure, so these need no CovertDetector/FireflyDao fake. ---

@Preview(showBackground = true, backgroundColor = 0xFF161229)
@Composable
private fun PreviewJarWatchNeverWatched() {
    JarWatchContent(
        isListening = false,
        result = null,
        history = emptyList(),
        micPermissionDenied = false,
        micError = null,
        onToggleWatch = {},
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF161229)
@Composable
private fun PreviewJarWatchSomethingOutThere() {
    JarWatchContent(
        isListening = true,
        result = DetectionResult(confidence = 0.65f, flagged = true),
        history = listOf(
            DetectionHistoryEntry(timestampMillis = System.currentTimeMillis(), confidence = 0.65f),
            DetectionHistoryEntry(timestampMillis = System.currentTimeMillis() - 60_000, confidence = 0.4f),
        ),
        micPermissionDenied = false,
        micError = null,
        onToggleWatch = {},
    )
}

/**
 * Pure UI: no side effects, no audio, no permission logic. Dense single column, no
 * cards, no icons. The flagged/clear distinction now uses [AccentSignal] on the
 * `flagged` word only (Task #17) — this screen's whole reason to exist is spec INV-4
 * (self-detecting the app's own transmission), so "flagged" is the one state on this
 * screen that earns the app's single reserved accent. Still no glow, no color pulse,
 * no shimmer on the live readout — the confidence percentage itself stays TextPrimary
 * always, "clear" stays TextSecondary. Same restraint task #7 applied to the modem.
 *
 * Task #28: the title now carries a persistent one-line note explaining what the
 * confidence number actually is and that it runs continuously/passively — real
 * feedback said the app gave no idea what the percentage implied. Static under the
 * title (not folded into [ReadoutBlock]) since it's true before, during, and after any
 * listen session, not a live reading itself.
 *
 * Task #35: visual grouping pass only, no new state/behavior. The listen control (and
 * its permission note, which is tightly coupled to it) sits in its own inner column;
 * a hairline [BorderDefault] divider — the same subtractive 1px stroke the image-stego
 * preview box already uses, not a card/elevation surface — separates it from the live
 * confidence readout, and a second divider separates the readout from the history list.
 * Makes the three functional zones (listen control / live readout / history) legible at
 * a glance instead of reading as one undifferentiated column.
 */
@Composable
fun DetectorContent(
    isListening: Boolean,
    result: DetectionResult?,
    history: List<DetectionHistoryEntry>,
    flagThreshold: Float,
    micPermissionDenied: Boolean,
    micError: String?,
    onToggleListen: () -> Unit,
    onBack: () -> Unit,
) {
    val listenLabel = if (isListening) "stop" else "listen"

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
                    text = "detector",
                    style = MaterialTheme.typography.displayLarge,
                    color = TextPrimary,
                )
                Text(
                    text = "confidence tracks match against the modem's own signal. " +
                        "runs continuously while listening, never decodes anything.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionRow(label = listenLabel, onClick = onToggleListen)

                if (micPermissionDenied) {
                    Text(
                        text = "microphone permission needed to listen.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }

                // mic-1: visible instead of crashing when every AudioSource tier fails to
                // initialize (mic held by a call or another app).
                if (micError != null) {
                    Text(
                        text = micError,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }
            }

            HorizontalDivider(color = BorderDefault, thickness = 1.dp)

            ReadoutBlock(result = result, flagThreshold = flagThreshold)

            HorizontalDivider(color = BorderDefault, thickness = 1.dp)

            HistoryBlock(history = history)
        }
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = TextPrimary,
        )
    }
}

/**
 * The live confidence/anomaly-score readout. Shows a quiet placeholder before the first
 * [DetectionResult] arrives, then the running confidence percentage, the flagged/clear
 * word, the configured flag threshold, and — when present — the detector's own [detail]
 * note. Surfacing [DetectionResult.detail] verbatim (e.g. "sustained tone-grid energy: 4
 * on-grid bin(s) >= 15.0 dB over floor") is the one deliberate rough edge on this screen,
 * same move task #7 made with the modem's "N bytes corrected" note: real analyzer output
 * surfaced, not smoothed into a generic status word.
 */
@Composable
private fun ReadoutBlock(result: DetectionResult?, flagThreshold: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "confidence",
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
        )
        if (result == null) {
            Text(
                text = "confidence appears once you start listening",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
        } else {
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
            Text(
                text = "flags at ${(flagThreshold * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
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
        }
    }
}

@Composable
private fun HistoryBlock(history: List<DetectionHistoryEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "history",
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
        )
        if (history.isEmpty()) {
            Text(
                text = "no detections yet",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
        } else {
            Column {
                history.forEach { entry -> HistoryRow(entry) }
            }
        }
    }
}

private val historyTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

/**
 * Timestamp in monospace — the doctrine's one sanctioned mono use (timestamps carry
 * information; nothing else on this screen uses mono). Confidence value stays in the
 * default type to match the live readout above it.
 */
@Composable
private fun HistoryRow(entry: DetectionHistoryEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = historyTimeFormat.format(entry.timestampMillis),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = TextSecondary,
        )
        Text(
            text = "${(entry.confidence * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
        )
    }
}

/**
 * Owns the [CovertDetector] passive-analysis loop plus the `AudioRecord` transport that
 * drives it (architecture.md draws the codec/transport line at the UI layer, not inside
 * the interface — the same split task #7 used for the modem's `AudioTrack`/`AudioRecord`
 * controller). Constructor takes only the interface — no `AcousticDetector` reference
 * anywhere in this file.
 *
 * Not an `androidx.lifecycle.ViewModel` — that dependency isn't in this module's gradle
 * file, and a plain `mutableStateOf`-backed class disposed via `DisposableEffect` is the
 * same pattern `AcousticModemController` already established.
 *
 * A history row is appended on each rising edge into `flagged` (clear -> flagged), not on
 * every `analyze()` call — the task asked for "a simple scrolling history of past
 * detections," and a detection is a discrete event, not a running log of every ~85ms
 * analysis frame. The live confidence/flagged readout updates continuously regardless;
 * only the history list is edge-triggered.
 */
class DetectorController(private val detector: CovertDetector<PcmAudio>, private val appContext: Context) {

    var isListening: Boolean by mutableStateOf(false)
        private set

    var lastResult: DetectionResult? by mutableStateOf(null)
        private set

    var history: List<DetectionHistoryEntry> by mutableStateOf(emptyList())
        private set

    /**
     * mic-1 fix: set when [captureLoop] can't get a working [android.media.AudioRecord] — every
     * [MicCapture.openBestAudioRecord] tier busy/unavailable, or `startRecording()` itself
     * throwing once initialized — instead of the uncaught `IllegalStateException` this used to
     * crash on (this controller previously built a bare `AudioSource.MIC` record directly, with
     * no state check and only a `SecurityException` catch). Cleared at the start of every
     * [startListening] call.
     */
    var micError: String? by mutableStateOf(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listening = AtomicBoolean(false)
    private var wasFlagged = false

    /** Start passive listening. No-op while already listening. */
    fun startListening() {
        if (isListening) return
        listening.set(true)
        isListening = true
        micError = null
        scope.launch {
            try {
                captureLoop()
            } catch (permissionRevoked: SecurityException) {
                // Falls through to the finally block; the live readout just stops
                // updating, same silent-stop treatment as a manual "stop" tap.
            } catch (micBusy: IllegalStateException) {
                // mic-1 fix: startRecording() threw once the record was already initialized —
                // surface a visible reason instead of crashing. (The "returns null" half of this
                // finding is handled directly inside captureLoop() below, with no exception at
                // all.)
                micError = "the microphone is busy — a call or another app is using it."
            } finally {
                listening.set(false)
                isListening = false
            }
        }
    }

    /** Ends the capture loop; the in-flight coroutine exits its read loop on its own. */
    fun stopListening() {
        listening.set(false)
    }

    /** Cancels any in-flight listening. Call from `DisposableEffect.onDispose`. */
    fun dispose() {
        listening.set(false)
        scope.cancel()
    }

    private suspend fun captureLoop() = withContext(Dispatchers.IO) {
        val minBufBytes = AudioRecord.getMinBufferSize(
            NightjarAcoustics.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val recordBufferBytes = if (minBufBytes > 0) minBufBytes * 4 else NightjarAcoustics.FRAME_SAMPLES * 8

        // mic-1/mic-3 fix: shares AcousticModemController's UNPROCESSED -> VOICE_RECOGNITION ->
        // MIC AudioSource fallback (avoids platform speech DSP distorting the tone-grid energy
        // this detector measures — spec.md INV-4) and its NoiseSuppressor/AGC/AEC suppression,
        // instead of the bare `AudioSource.MIC` AudioRecord this loop used to build directly with
        // neither and no STATE_INITIALIZED check.
        val audioRecord = MicCapture.openBestAudioRecord(appContext, recordBufferBytes)
        if (audioRecord == null) {
            micError = "the microphone is busy — a call or another app is using it."
            return@withContext
        }

        // Read a few frames at a time (~85ms of audio) rather than one FRAME_SAMPLES chunk
        // (~21ms) per read: detector.analyze() internally re-slices into FRAME_SAMPLES-sized
        // frames regardless of the chunk size it's handed (AcousticDetector's KDoc), so this
        // only throttles how often the live readout recomposes — smooth enough to read
        // without writing Compose state every ~21ms.
        val chunk = ShortArray(NightjarAcoustics.FRAME_SAMPLES * 4)
        // mic-8 precedent applied here too: effects are disabled from inside this try, right
        // after the AudioRecord they operate on is already in hand, so a throwing OEM effect
        // factory can't skip past the record's own release() below.
        var disabledEffects: List<AudioEffect> = emptyList()
        try {
            disabledEffects = MicCapture.disablePlatformAudioEffects(audioRecord.audioSessionId)
            audioRecord.startRecording()
            while (listening.get()) {
                val n = audioRecord.read(chunk, 0, chunk.size)
                if (n <= 0) break
                val sample = if (n == chunk.size) chunk else chunk.copyOf(n)
                val result = detector.analyze(sample)
                onResult(result)
            }
        } finally {
            // mic-1 fix: only call stop() if the record actually reached RECORDSTATE_RECORDING —
            // calling it otherwise (e.g. startRecording() itself threw) throws its own
            // IllegalStateException and would mask whatever failure got us here.
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
            audioRecord.release()
            disabledEffects.forEach { it.release() }
        }
    }

    private fun onResult(result: DetectionResult) {
        lastResult = result
        DebugProbe.reportDetectorConfidence(ModuleId.ACOUSTIC_DETECTOR, result.confidence)
        if (result.flagged && !wasFlagged) {
            val entry = DetectionHistoryEntry(
                timestampMillis = System.currentTimeMillis(),
                confidence = result.confidence,
            )
            history = (listOf(entry) + history).take(MAX_HISTORY_ENTRIES)
        }
        wasFlagged = result.flagged
    }
}

// --- Previews: DetectorContent is pure, so these need no CovertDetector fake. ---

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewNeverListened() {
    PreviewSurface {
        DetectorContent(
            isListening = false,
            result = null,
            history = emptyList(),
            flagThreshold = 0.2f,
            micPermissionDenied = false,
            micError = null,
            onToggleListen = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewClear() {
    PreviewSurface {
        DetectorContent(
            isListening = true,
            result = DetectionResult(confidence = 0.05f, flagged = false),
            history = emptyList(),
            flagThreshold = 0.2f,
            micPermissionDenied = false,
            micError = null,
            onToggleListen = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewFlagged() {
    PreviewSurface {
        DetectorContent(
            isListening = true,
            result = DetectionResult(
                confidence = 0.65f,
                flagged = true,
                detail = "sustained tone-grid energy: 4 on-grid bin(s) >= 15.0 dB over floor",
            ),
            history = listOf(
                DetectionHistoryEntry(timestampMillis = System.currentTimeMillis(), confidence = 0.65f),
                DetectionHistoryEntry(timestampMillis = System.currentTimeMillis() - 12_000, confidence = 0.4f),
                DetectionHistoryEntry(timestampMillis = System.currentTimeMillis() - 41_000, confidence = 0.9f),
            ),
            flagThreshold = 0.2f,
            micPermissionDenied = false,
            micError = null,
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
