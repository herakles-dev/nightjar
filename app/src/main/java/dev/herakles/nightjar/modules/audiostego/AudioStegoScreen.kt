package dev.herakles.nightjar.modules.audiostego

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.AudioStegoCarrier
import dev.herakles.nightjar.AudioStegoTechnique
import dev.herakles.nightjar.CovertCarrier
import dev.herakles.nightjar.DecodeFailure
import dev.herakles.nightjar.DecodeResult
import dev.herakles.nightjar.NightjarAcoustics
import dev.herakles.nightjar.PcmAudio
import dev.herakles.nightjar.modules.fireflyjar.FireflyDao
import dev.herakles.nightjar.modules.fireflyjar.FireflyRecord
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.ui.theme.BgBase
import dev.herakles.nightjar.ui.theme.BorderDefault
import dev.herakles.nightjar.ui.theme.FireflyCreated
import dev.herakles.nightjar.ui.theme.FireflyReceived
import dev.herakles.nightjar.ui.theme.JarGlassOutline
import dev.herakles.nightjar.ui.theme.JarTextPrimary
import dev.herakles.nightjar.ui.theme.JarTextSecondary
import dev.herakles.nightjar.ui.theme.TextPrimary
import dev.herakles.nightjar.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Module 2 (audio steganography) real UI — the closest architectural analog is
 * [dev.herakles.nightjar.modules.imagestego.ImageStegoScreen] ("Screen 4"): a carrier bound to a
 * specific cover at construction time, not a live-transmission carrier like the acoustic modem.
 * Written against the [CovertCarrier] interface only — zero references to the concrete
 * `AudioStegoCarrier` class anywhere in this file, the same discipline every other module screen
 * in this app already follows.
 *
 * Cover-bound-at-construction plus an operator-selectable *technique* is one more axis than
 * [dev.herakles.nightjar.modules.imagestego.ImageStegoScreen] has to handle (that screen only
 * switches cover image, never codec), so [carrierFactory] here takes both
 * `(PcmAudio, AudioStegoTechnique) -> CovertCarrier<PcmAudio>` — still zero references to the
 * concrete class, just one more factory parameter.
 *
 * No bundled audio assets exist anywhere in this app (no `res/raw/`, no `assets/`) — both sample
 * cover clips are synthesized in-memory PCM via [synthesizeSampleCover]
 * (`AudioStegoSampleCovers.kt`), matching how the rest of this app already treats [PcmAudio] as
 * in-memory-only.
 *
 * Scope boundary (same one `AcousticModemScreen.kt`'s KDoc draws): [CovertCarrier] is a pure
 * codec over in-memory buffers, not the audio transport. This screen owns playback —
 * `android.media.AudioTrack` for "play cover"/"play working" — mirroring
 * `AcousticModemController.playPcm`'s `AudioTrack.Builder()`/`AudioAttributes`/`AudioFormat`
 * construction exactly (`MODE_STATIC`, `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC`,
 * `ENCODING_PCM_16BIT`), the only existing `AudioTrack` usage in this app. One necessary
 * departure: [AudioStegoTechnique.PHASE_INVERSION]'s `encode()` output is interleaved stereo
 * (`AudioStegoCarrier`'s class KDoc), twice the mono cover's sample count — [AudioStegoController]
 * tracks the working clip's channel count alongside its samples so "play working" can build an
 * `AudioTrack` with the correct `CHANNEL_OUT_MONO`/`CHANNEL_OUT_STEREO` mask instead of assuming
 * mono like the acoustic modem's own playback (which is always mono).
 */

/**
 * The 6 states this screen's embed/extract cycle can be in, exactly mirroring
 * [dev.herakles.nightjar.modules.imagestego.StegoStatus]'s shape (minus that screen's
 * `check for hidden data` cases — this module has no `CovertDetector` yet).
 */
sealed interface AudioStegoStatus {
    data object Idle : AudioStegoStatus
    data object Embedding : AudioStegoStatus
    data object Extracting : AudioStegoStatus
    data class Embedded(val payloadBytes: Int) : AudioStegoStatus
    data class ExtractedSuccess(val text: String) : AudioStegoStatus
    data class ExtractedFailure(val reason: DecodeFailure, val detail: String?) : AudioStegoStatus
}

/**
 * Which clip is currently playing, if any — drives the playback row's "playing cover"/
 * "playing working" label swap (UX pass: previously neither verb gave any feedback that
 * anything was happening). Bare status-word convention, same as [StatusWord] — no waveform, no
 * meter, no accent color, per this screen's approved non-goals.
 */
enum class PlaybackTarget { COVER, WORKING }

/**
 * Stateful root: owns the [AudioStegoController], the technique/cover selection, and the payload
 * text field. [AudioStegoContent] below is the pure/previewable UI (no side effects, no
 * `Context`, no audio synthesis/playback).
 */
@Composable
fun AudioStegoScreen(
    carrierFactory: (PcmAudio, AudioStegoTechnique) -> CovertCarrier<PcmAudio>,
    onBack: () -> Unit,
) {
    val controller = remember(carrierFactory) { AudioStegoController(carrierFactory) }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    var technique by remember { mutableStateOf(AudioStegoTechnique.PHASE_INVERSION) }
    var cover by remember { mutableStateOf(AudioSampleCover.SPOKEN_WORD) }
    var payloadText by remember { mutableStateOf("") }

    // Pure/deterministic synthesis (AudioStegoSampleCovers.kt) — safe to call directly from
    // composition, same as ImageStegoScreen's BitmapFactory.decodeResource for its bundled
    // sample covers.
    val coverAudio = remember(cover) { synthesizeSampleCover(cover) }
    val maxPayloadBytes = remember(coverAudio, technique) { controller.maxPayloadBytesFor(coverAudio, technique) }
    // UX pass: capacity for EVERY technique against the current cover, so the technique rows can
    // show "· NB" inline — comparing techniques no longer requires selecting each one first.
    // Cheap: AudioStegoCarrier's maxPayloadBytes is O(1) arithmetic at construction, no FFT runs
    // until encode()/decode() actually execute.
    val techniqueCapacities = remember(coverAudio) {
        AudioStegoTechnique.entries.associateWith { controller.maxPayloadBytesFor(coverAudio, it) }
    }

    // Switching EITHER selector (technique or cover clip) resets the working clip back to the
    // newly-selected cover's raw samples and resets status to Idle — keyed on both, so either
    // change alone re-fires this effect (mirrors ImageStegoScreen's single-key
    // LaunchedEffect(coverBitmap), extended to this screen's second axis).
    LaunchedEffect(coverAudio, technique) {
        controller.selectCover(coverAudio)
    }

    AudioStegoContent(
        status = controller.status,
        technique = technique,
        onSelectTechnique = { technique = it },
        techniqueCapacities = techniqueCapacities,
        cover = cover,
        onSelectCover = { cover = it },
        payloadText = payloadText,
        onPayloadTextChange = { payloadText = it },
        maxPayloadBytes = maxPayloadBytes,
        nowPlaying = controller.nowPlaying,
        onPlayCover = { controller.playCover(coverAudio) },
        onPlayWorking = { controller.playWorking() },
        onEmbed = { controller.embed(coverAudio, technique, payloadText.encodeToByteArray()) },
        onExtract = { controller.extract(technique) },
        onBack = onBack,
    )
}

/**
 * Task #13 — the real jar-framed catch flow for [Module.AUDIO_STEGANOGRAPHY] ("the humming
 * jar", [dev.herakles.nightjar.picker.JarRole.CREATION]). `JarCatchFlows.kt`'s `catchFlowFor`
 * dispatcher (task #9) calls this directly. Per design/screen-flow.md § Screen 7, this is "a
 * themed skin over the exact same state machines and carrier calls Screens 2, 4, and 5 already
 * define" — it re-presents [AudioStegoController]/[AudioStegoStatus], it does not reimplement
 * them. [AudioStegoCarrier] itself is untouched.
 *
 * One departure from this file's top-of-file "zero references to the concrete
 * [AudioStegoCarrier] class" discipline: that rule describes [AudioStegoScreen]/
 * [AudioStegoContent], whose `carrierFactory` is injected from `MainActivity.kt`. The Firefly
 * Jar dispatcher's stub signature (`jarCatchFlow(dao: FireflyDao, onExit: () -> Unit)`, task #9,
 * not touched by this task) has no factory-injection slot, so this composable builds its own
 * [AudioStegoController] the same way `MainActivity.kt` builds the technical screen's one —
 * `AudioStegoCarrier(cover, technique)` — just inlined here instead of at a call site.
 *
 * Deliberately narrower than [AudioStegoContent]: no A/B playback, no per-technique/per-cover
 * info popouts, no cross-technique capacity comparison — none of those appear in
 * screen-flow.md § Screen 7's diagram for this surface ("no extra fields needed" beyond the
 * technique/cover selectors and the payload field), and this surface's whole point is a
 * simpler, softer front door than the technical screen behind it.
 *
 * "catch a firefly" expands into the technique + cover selectors and the payload field inline;
 * "look for fireflies" has no fields of its own and fires [AudioStegoController.extract]
 * directly — same two-section shape screen-flow.md's diagram specifies. A successful catch/look
 * inserts exactly one [FireflyRecord] into [dao] (`direction = "CREATED"`/`"RECEIVED"`) via a
 * `LaunchedEffect` keyed on [AudioStegoController.status]: [AudioStegoController.embed]/
 * [AudioStegoController.extract] are fire-and-forget from their own coroutine (they mutate
 * `status`, no completion callback to hang the insert off directly), and each real transition
 * into a terminal status is a genuinely new sealed-class instance (there's always an
 * `Embedding`/`Extracting` step in between two terminal states), so the effect reliably fires
 * once per completed action rather than only once per distinct value.
 */
@Composable
fun jarCatchFlow(dao: FireflyDao, onExit: () -> Unit) {
    val controller = remember {
        AudioStegoController(carrierFactory = { cover, technique -> AudioStegoCarrier(cover, technique) })
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    var technique by remember { mutableStateOf(AudioStegoTechnique.PHASE_INVERSION) }
    var cover by remember { mutableStateOf(AudioSampleCover.SPOKEN_WORD) }
    var payloadText by remember { mutableStateOf("") }
    var catchExpanded by remember { mutableStateOf(false) }

    val coverAudio = remember(cover) { synthesizeSampleCover(cover) }
    val maxPayloadBytes = remember(coverAudio, technique) { controller.maxPayloadBytesFor(coverAudio, technique) }

    // Same reset-on-selector-change behavior as AudioStegoScreen's own LaunchedEffect above.
    LaunchedEffect(coverAudio, technique) {
        controller.selectCover(coverAudio)
    }

    // Logs a firefly the moment the controller's own state machine lands on a terminal success —
    // see this function's KDoc for why a status-keyed effect is the right hook point here.
    //
    // Captures controller.status into a local val ONCE per composition rather than re-reading
    // controller.status inside the effect body, matching AcousticModemScreen.kt's jarCatchFlow
    // (task #11). Re-reading the live property inside the coroutine let a fast decode() race
    // ahead of Compose's recomposition scheduling: the Extracting-triggered effect body could
    // observe status having already moved on to ExtractedSuccess and insert a firefly for it,
    // then the genuine ExtractedSuccess-triggered relaunch inserted a second, identical row for
    // the same completed extract (task #18's live-reproduced double insert, ids 8 & 9).
    val fireflyStatus = controller.status
    LaunchedEffect(fireflyStatus) {
        when (fireflyStatus) {
            is AudioStegoStatus.Embedded -> dao.insert(
                FireflyRecord(
                    moduleId = Module.AUDIO_STEGANOGRAPHY.name,
                    direction = "CREATED",
                    timestampMillis = System.currentTimeMillis(),
                    payloadSizeBytes = fireflyStatus.payloadBytes,
                    technique = technique.name,
                    payloadPreview = payloadText.take(40),
                ),
            )
            is AudioStegoStatus.ExtractedSuccess -> dao.insert(
                FireflyRecord(
                    moduleId = Module.AUDIO_STEGANOGRAPHY.name,
                    direction = "RECEIVED",
                    timestampMillis = System.currentTimeMillis(),
                    payloadSizeBytes = fireflyStatus.text.encodeToByteArray().size,
                    technique = technique.name,
                    payloadPreview = fireflyStatus.text.take(40),
                ),
            )
            else -> Unit
        }
    }

    JarAudioStegoCatchFlowContent(
        status = controller.status,
        catchExpanded = catchExpanded,
        onToggleCatchExpanded = { catchExpanded = !catchExpanded },
        technique = technique,
        onSelectTechnique = { technique = it },
        cover = cover,
        onSelectCover = { cover = it },
        payloadText = payloadText,
        onPayloadTextChange = { payloadText = it },
        maxPayloadBytes = maxPayloadBytes,
        onEmbed = { controller.embed(coverAudio, technique, payloadText.encodeToByteArray()) },
        onExtract = { controller.extract(technique) },
        onExit = onExit,
    )
}

/**
 * Pure/previewable jar-mode content — same stateful-root/pure-content split [AudioStegoContent]
 * itself follows. Rendered directly inside `JarDetailContent`'s own padded, scrollable `Column`
 * (`JarDetailScreen.kt`'s `moduleFlow` slot) alongside the firefly swarm and the "back to the
 * shelf" row already provided there, so this content owns no outer padding, background, or top-
 * level back affordance of its own — except a second, bottom-anchored "back to the shelf" link:
 * the swarm plus an expanded catch section can scroll the real one out of view, and this flow's
 * own [onExit] parameter exists precisely to give a way out that doesn't require scrolling back
 * up.
 */
@Composable
private fun JarAudioStegoCatchFlowContent(
    status: AudioStegoStatus,
    catchExpanded: Boolean,
    onToggleCatchExpanded: () -> Unit,
    technique: AudioStegoTechnique,
    onSelectTechnique: (AudioStegoTechnique) -> Unit,
    cover: AudioSampleCover,
    onSelectCover: (AudioSampleCover) -> Unit,
    payloadText: String,
    onPayloadTextChange: (String) -> Unit,
    maxPayloadBytes: Int,
    onEmbed: () -> Unit,
    onExtract: () -> Unit,
    onExit: () -> Unit,
) {
    val idleEquivalent = status is AudioStegoStatus.Idle ||
        status is AudioStegoStatus.Embedded ||
        status is AudioStegoStatus.ExtractedSuccess ||
        status is AudioStegoStatus.ExtractedFailure
    val payloadBytes = payloadText.encodeToByteArray().size
    val canEmbed = idleEquivalent && payloadText.isNotEmpty() && payloadBytes <= maxPayloadBytes

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            JarFlowRow(label = "catch a firefly", enabled = idleEquivalent, onClick = onToggleCatchExpanded)
            if (catchExpanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "technique",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarTextSecondary,
                        )
                        AudioStegoTechnique.entries.forEach { option ->
                            JarSelectorRow(
                                label = techniqueLabel(option),
                                selected = option == technique,
                                enabled = idleEquivalent,
                                onClick = { onSelectTechnique(option) },
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "cover clip",
                            style = MaterialTheme.typography.labelSmall,
                            color = JarTextSecondary,
                        )
                        AudioSampleCover.entries.forEach { option ->
                            JarSelectorRow(
                                label = option.label,
                                selected = option == cover,
                                enabled = idleEquivalent,
                                onClick = { onSelectCover(option) },
                            )
                        }
                    }
                    BasicTextField(
                        value = payloadText,
                        onValueChange = onPayloadTextChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = JarTextPrimary),
                        cursorBrush = SolidColor(JarTextPrimary),
                        enabled = idleEquivalent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(width = 1.dp, color = JarGlassOutline)
                            .padding(12.dp),
                        decorationBox = { innerTextField ->
                            if (payloadText.isEmpty()) {
                                Text(
                                    text = "what do you want to hide?",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = JarTextSecondary,
                                )
                            }
                            innerTextField()
                        },
                    )
                    Text(
                        text = if (payloadBytes > maxPayloadBytes) {
                            "too big for this jar — trim it or try a different light"
                        } else {
                            "$payloadBytes / $maxPayloadBytes bytes"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = JarTextSecondary,
                    )
                    JarFlowRow(label = "let it glow", enabled = canEmbed, onClick = onEmbed)
                }
            }
        }

        JarFlowRow(label = "look for fireflies", enabled = idleEquivalent, onClick = onExtract)

        JarStatusBlock(status = status)

        Text(
            text = "back to the shelf",
            style = MaterialTheme.typography.labelSmall,
            color = JarTextSecondary,
            modifier = Modifier
                .clickable(onClick = onExit)
                .padding(top = 4.dp),
        )
    }
}

/** 44dp full-width jar-mode verb row — [JarFlowRow] is this flow's own thing, not
 *  [ActionRow]/[SelectorRowWithInfo], since the jar surface's palette (design/
 *  firefly-jar-identity.md) is [JarTextPrimary]/[JarTextSecondary], not
 *  [TextPrimary]/[TextSecondary], and this flow has no per-row info toggle. */
@Composable
private fun JarFlowRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) JarTextPrimary else JarTextSecondary,
        )
    }
}

/** One selectable technique/cover-clip row inside the expanded "catch a firefly" section — no
 *  info toggle (unlike [SelectorRowWithInfo]), matching this jar-mode flow's simpler field set. */
@Composable
private fun JarSelectorRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) JarTextPrimary else JarTextSecondary,
        )
    }
}

@Composable
private fun JarStatusBlock(status: AudioStegoStatus) {
    when (status) {
        is AudioStegoStatus.Idle -> Unit
        is AudioStegoStatus.Embedding -> Text(
            text = "catching...",
            style = MaterialTheme.typography.labelLarge,
            color = JarTextSecondary,
        )
        is AudioStegoStatus.Extracting -> Text(
            text = "looking...",
            style = MaterialTheme.typography.labelLarge,
            color = JarTextSecondary,
        )
        is AudioStegoStatus.Embedded -> {
            val plural = if (status.payloadBytes == 1) "" else "s"
            Text(
                text = "you caught one — ${status.payloadBytes} byte$plural",
                style = MaterialTheme.typography.bodyLarge,
                // design/firefly-jar-identity.md § Palette: FireflyCreated fires on every
                // successful catch on this surface, unlike identity.md's "silence is success".
                color = FireflyCreated,
            )
        }
        is AudioStegoStatus.ExtractedSuccess -> Text(
            text = status.text,
            style = MaterialTheme.typography.bodyLarge,
            color = FireflyReceived,
        )
        is AudioStegoStatus.ExtractedFailure -> Text(
            text = jarExtractFailureMessage(status.reason),
            style = MaterialTheme.typography.bodyLarge,
            color = JarTextSecondary,
        )
    }
}

/** Jar-mode copy mapping for [DecodeFailure] — exact strings from design/screen-flow.md § Screen
 *  7's "Copy mapping" table, distinct from [extractFailureMessage]'s technical-screen wording. */
private fun jarExtractFailureMessage(reason: DecodeFailure): String = when (reason) {
    DecodeFailure.NO_PAYLOAD_FOUND -> "nothing's glowing in here right now."
    DecodeFailure.HEADER_INVALID -> "that light doesn't look right — probably not one of yours."
    DecodeFailure.PAYLOAD_TOO_LARGE -> "too big to fit in the jar."
    DecodeFailure.UNRECOVERABLE_FEC -> "the light faded before it got here."
    DecodeFailure.INTEGRITY_MISMATCH -> "it flickered wrong on the way — the message got scrambled."
}

/**
 * Pure UI: no side effects, no synthesis, no playback. Dense single column, no cards, no icons.
 * No [dev.herakles.nightjar.ui.theme.AccentSignal] anywhere on this screen — this screen's own
 * approved design explicitly opts out even for the playback moments (see design/screen-flow.md
 * "Screen 5"), unlike the acoustic modem's `transmitting`/`listening` accent treatment.
 */
@Composable
fun AudioStegoContent(
    status: AudioStegoStatus,
    technique: AudioStegoTechnique,
    onSelectTechnique: (AudioStegoTechnique) -> Unit,
    techniqueCapacities: Map<AudioStegoTechnique, Int>,
    cover: AudioSampleCover,
    onSelectCover: (AudioSampleCover) -> Unit,
    payloadText: String,
    onPayloadTextChange: (String) -> Unit,
    maxPayloadBytes: Int,
    nowPlaying: PlaybackTarget?,
    onPlayCover: () -> Unit,
    onPlayWorking: () -> Unit,
    onEmbed: () -> Unit,
    onExtract: () -> Unit,
    onBack: () -> Unit,
) {
    val idleEquivalent = status is AudioStegoStatus.Idle ||
        status is AudioStegoStatus.Embedded ||
        status is AudioStegoStatus.ExtractedSuccess ||
        status is AudioStegoStatus.ExtractedFailure
    val payloadBytes = payloadText.encodeToByteArray().size
    val canEmbed = idleEquivalent && payloadText.isNotEmpty() && payloadBytes <= maxPayloadBytes

    // Design pass: "info popouts for learning" -- this app exists to teach these techniques, so
    // explaining WHY each one works (not just letting the user click through blind) is core to
    // the point, not decoration. Local, purely-presentational UI state (no business logic, no
    // Context) -- doesn't need to be hoisted to the stateful root, same reasoning `remember`
    // already gets used for elsewhere in genuinely-pure composables. Accordion (one open at a
    // time) so the screen doesn't grow into a wall of text if someone taps through all of them.
    var expandedInfo by remember { mutableStateOf<InfoKey?>(null) }
    fun toggleInfo(key: InfoKey) {
        expandedInfo = if (expandedInfo == key) null else key
    }

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
                .imePadding()
                .padding(top = 56.dp, start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "audio steganography",
                    style = MaterialTheme.typography.displayLarge,
                    color = TextPrimary,
                )
                Text(
                    text = "hide text inside a clip. playback proves it sounds unchanged.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabelRow(
                    label = "technique",
                    infoLabel = "what's the tradeoff?",
                    infoExpanded = expandedInfo == InfoKey.Tradeoff,
                    onToggleInfo = { toggleInfo(InfoKey.Tradeoff) },
                )
                if (expandedInfo == InfoKey.Tradeoff) {
                    InfoText(tradeoffExplainer())
                }
                AudioStegoTechnique.entries.forEach { option ->
                    val capacity = techniqueCapacities[option]
                    val infoKey = InfoKey.Technique(option)
                    SelectorRowWithInfo(
                        // Inline capacity (UX pass) -- comparing techniques no longer requires
                        // selecting each one first to see its byte counter.
                        label = if (capacity != null) "${techniqueLabel(option)} · ${capacity}B" else techniqueLabel(option),
                        selected = option == technique,
                        enabled = idleEquivalent,
                        onClick = { onSelectTechnique(option) },
                        infoExpanded = expandedInfo == infoKey,
                        onToggleInfo = { toggleInfo(infoKey) },
                    )
                    if (expandedInfo == infoKey) {
                        InfoText(techniqueExplainer(option))
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "cover clip",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                )
                AudioSampleCover.entries.forEach { option ->
                    val infoKey = InfoKey.Cover(option)
                    SelectorRowWithInfo(
                        label = option.label,
                        selected = option == cover,
                        enabled = idleEquivalent,
                        onClick = { onSelectCover(option) },
                        infoExpanded = expandedInfo == infoKey,
                        onToggleInfo = { toggleInfo(infoKey) },
                    )
                    if (expandedInfo == infoKey) {
                        InfoText(coverExplainer(option))
                    }
                }
            }

            // A/B listening test: proves the working clip sounds unchanged from the pristine
            // cover. Available in any idle-equivalent state; never changes `status`.
            PlaybackRow(
                enabled = idleEquivalent,
                nowPlaying = nowPlaying,
                onPlayCover = onPlayCover,
                onPlayWorking = onPlayWorking,
            )

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
                    // UX pass: over-capacity previously just silently grayed out "embed" with no
                    // explanation -- now the counter itself says why.
                    text = if (payloadBytes > maxPayloadBytes) {
                        "$payloadBytes / $maxPayloadBytes bytes — ${payloadBytes - maxPayloadBytes} over, trim it or switch technique"
                    } else {
                        "$payloadBytes / $maxPayloadBytes bytes"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }

            Column {
                Text(
                    text = "embed hides text in the clip, extract reads it back",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ActionRow(label = "embed", enabled = canEmbed, onClick = onEmbed)
                ActionRow(label = "extract", enabled = idleEquivalent, onClick = onExtract)
            }

            StatusBlock(status = status)
        }
    }
}

/**
 * One selectable technique/cover-clip row — 40dp tap target, matches `ImageStegoScreen.kt`'s
 * `CoverRow` / `AcousticModemScreen.kt`'s `SettingOptionRow` styling, plus a separate trailing
 * "?"/"close" info toggle (design pass — independent tap target from row selection, so learning
 * about a technique never requires switching to it first).
 */
@Composable
private fun SelectorRowWithInfo(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    infoExpanded: Boolean,
    onToggleInfo: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) TextPrimary else TextSecondary,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clickable(onClick = onToggleInfo)
                .padding(start = 16.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = if (infoExpanded) "close" else "?",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}

/**
 * Section label ("technique") plus a trailing info toggle — same row-level split as
 * [SelectorRowWithInfo], for a section-wide explainer rather than a per-option one. The toggle
 * gets a real 40dp touch target (not just the text glyphs' own tight bounds) — a bare
 * `Modifier.clickable` directly on a `labelSmall` `Text` measured out too small to hit reliably
 * (caught by an actual missed tap during on-device verification, not a style guess).
 */
@Composable
private fun SectionLabelRow(label: String, infoLabel: String, infoExpanded: Boolean, onToggleInfo: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = TextSecondary)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clickable(onClick = onToggleInfo)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = if (infoExpanded) "close" else infoLabel,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}

/** Expanded info-popout body text — indented slightly to read as nested under its parent row/
 *  section, `labelSmall`/`TextSecondary` like every other explanatory caption on this screen
 *  (the intro line, the byte counter) — no new color, no card, no background fill. */
@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp, end = 8.dp),
    )
}

/** Which info popout (at most one) is currently expanded — see [AudioStegoContent]'s `expandedInfo`. */
private sealed interface InfoKey {
    data object Tradeoff : InfoKey
    data class Technique(val technique: AudioStegoTechnique) : InfoKey
    data class Cover(val cover: AudioSampleCover) : InfoKey
}

/**
 * The fidelity/capacity/survivability tradeoff every technique on this screen sits somewhere on —
 * straight from this project's own research library (`covert-data/library/03_audio_steganography.md`
 * frames it as a triangle where "at most two can be maximized at once"), not invented for this UI.
 */
private fun tradeoffExplainer(): String =
    "Every technique trades off three things: how natural the clip still sounds (fidelity), how " +
        "much you can hide (capacity), and how well it survives being re-compressed or corrupted " +
        "(survivability). At most two of the three, ever."

/** Plain-language explanation of what each technique is actually doing — grounded in
 *  [dev.herakles.nightjar.AudioStegoCarrier]'s own class KDoc, simplified for a reader who
 *  doesn't already know DSP. */
private fun techniqueExplainer(technique: AudioStegoTechnique): String = when (technique) {
    AudioStegoTechnique.PHASE_INVERSION ->
        "Splits the clip into two channels: one is the original cover, the other an inverted " +
            "copy with your message mixed in as a barely-there signal. Play it normally and it " +
            "sounds like ordinary audio — but summing the two channels cancels the cover and " +
            "leaves only the hidden message behind. Simple to reason about, lowest capacity of " +
            "the three, and fragile to compression."
    AudioStegoTechnique.SPECTROGRAM_LSB ->
        "Converts the clip into its frequency-domain representation (a spectrogram) and nudges " +
            "specific frequency bins by tiny, controlled amounts to encode your message — the " +
            "same principle Module 1 uses for images, just applied to sound frequencies instead " +
            "of pixels. Every frame contributes several bits instead of one, so capacity is far " +
            "higher than phase inversion."
    AudioStegoTechnique.MFSK ->
        "Encodes your message as a sequence of tones layered on top of the cover clip, then " +
            "wraps the whole thing in real error-correcting math (Reed-Solomon — the same family " +
            "of code behind QR codes and CDs). Lower, fixed capacity, but built to recover the " +
            "message even if part of the clip gets corrupted or noisy."
}

/** Plain-language explanation of what a sample cover clip actually is — grounded in
 *  `AudioStegoSampleCovers.kt`'s own KDoc (both clips are synthesized, never bundled/recorded
 *  audio; this popout is where that honesty-about-synthetic-content fact reaches the user, not
 *  just source comments). */
private fun coverExplainer(cover: AudioSampleCover): String = when (cover) {
    AudioSampleCover.SPOKEN_WORD ->
        "A synthesized approximation of speech — amplitude-modulated noise shaped into " +
            "speech-like syllable bursts. This app has no real recordings bundled in, so this is " +
            "a stand-in with speech-like texture, not an actual voice."
    AudioSampleCover.SOFT_SYNTH ->
        "A synthesized pad tone — a few sine waves layered together with a slow fade in and " +
            "out, meant to sound like a simple ambient synth note."
}

/** 48dp verb button — matches `ImageStegoScreen.kt`'s `ActionRow` exactly (no icon, no fill). */
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
 * "play cover" / "play working" side by side (wireframe: `play cover        play working`) — two
 * 48dp-tall verbs sharing one row, unlike every other verb on this screen (which is full-width).
 * No waveform/scrubber/level-meter, per the approved design's explicit non-goals — but the label
 * itself swaps to "playing cover"/"playing working" while [nowPlaying] matches (UX pass: neither
 * verb previously gave ANY feedback that a tap had done anything). Same bare-status-word
 * convention [StatusWord] already uses elsewhere on this screen, not a new pattern.
 */
@Composable
private fun PlaybackRow(
    enabled: Boolean,
    nowPlaying: PlaybackTarget?,
    onPlayCover: () -> Unit,
    onPlayWorking: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        PlaybackVerb(
            label = if (nowPlaying == PlaybackTarget.COVER) "playing cover" else "play cover",
            enabled = enabled,
            onClick = onPlayCover,
        )
        PlaybackVerb(
            label = if (nowPlaying == PlaybackTarget.WORKING) "playing working" else "play working",
            enabled = enabled,
            onClick = onPlayWorking,
        )
    }
}

@Composable
private fun PlaybackVerb(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
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

/** Display copy for [AudioStegoTechnique]. Kept local to this screen — not codec state. */
private fun techniqueLabel(technique: AudioStegoTechnique): String = when (technique) {
    AudioStegoTechnique.PHASE_INVERSION -> "phase inversion"
    AudioStegoTechnique.SPECTROGRAM_LSB -> "spectrogram lsb"
    AudioStegoTechnique.MFSK -> "mfsk (robust)"
}

@Composable
private fun StatusBlock(status: AudioStegoStatus) {
    when (status) {
        is AudioStegoStatus.Idle -> Unit // nothing running, nothing to report
        is AudioStegoStatus.Embedding -> StatusWord("embedding")
        is AudioStegoStatus.Extracting -> StatusWord("extracting")
        is AudioStegoStatus.Embedded -> {
            val plural = if (status.payloadBytes == 1) "" else "s"
            Text(
                text = "embedded ${status.payloadBytes} byte$plural into the working clip",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
        }
        is AudioStegoStatus.ExtractedSuccess -> Text(
            text = status.text,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
        )
        is AudioStegoStatus.ExtractedFailure -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = extractFailureMessage(status.reason),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
            // identity.md's "one rough edge per shipping screen" — the raw codec detail string,
            // not just the mapped human copy above (mirrors ImageStegoScreen's AnalyzedBlock
            // surfacing DetectionResult.detail verbatim).
            status.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
        }
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
 * Maps [DecodeFailure] to on-voice copy for `extract` — exact strings specified by the approved
 * design (lowercase, no exclamation, informational not destructive, same voice contract
 * `ImageStegoScreen.kt`'s `extractFailureMessage` uses).
 */
private fun extractFailureMessage(reason: DecodeFailure): String = when (reason) {
    DecodeFailure.NO_PAYLOAD_FOUND -> "no hidden payload found in this clip."
    DecodeFailure.HEADER_INVALID -> "header didn't check out. this clip may not hold a nightjar payload."
    DecodeFailure.PAYLOAD_TOO_LARGE -> "declared payload is too large for this clip. frame rejected."
    DecodeFailure.UNRECOVERABLE_FEC -> "too much signal loss to recover."
    DecodeFailure.INTEGRITY_MISMATCH -> "checksum didn't match. the payload was altered or corrupted."
}

/**
 * Owns the [CovertCarrier] round-trip work plus the `AudioTrack` playback transport for this
 * screen — same split `AcousticModemController`/`ImageStegoController` already use (codec is a
 * pure in-memory interface call; transport is this screen's own responsibility).
 *
 * Two separate [CoroutineScope]s, on purpose: [codecScope] runs on [Dispatchers.Default] (CPU-
 * bound embed/extract math — FFTs, Reed-Solomon — exactly like `ImageStegoController`, NOT
 * `Dispatchers.IO`), while [playbackScope] runs on [Dispatchers.IO] (genuinely I/O-adjacent
 * `AudioTrack` construction/`write()`/`play()`, mirroring `AcousticModemController.playPcm`).
 */
class AudioStegoController(
    private val carrierFactory: (PcmAudio, AudioStegoTechnique) -> CovertCarrier<PcmAudio>,
) {
    var status: AudioStegoStatus by mutableStateOf(AudioStegoStatus.Idle)
        private set

    /**
     * The clip `extract` operates on and "play working" plays: the selected cover's pristine
     * samples until a successful [embed] replaces it with the produced stego clip. Empty only
     * before the screen's first [selectCover] call (mirrors `ImageStegoController.workingBitmap`'s
     * KDoc — the stateful root's `LaunchedEffect(coverAudio, technique)` fires before first
     * render, so this is never actually observed empty in practice).
     */
    var workingAudio: PcmAudio by mutableStateOf(ShortArray(0))
        private set

    /**
     * Channel count of [workingAudio] — 1 (mono) for the pristine cover and for
     * [AudioStegoTechnique.SPECTROGRAM_LSB]/[AudioStegoTechnique.MFSK] output, 2 for
     * [AudioStegoTechnique.PHASE_INVERSION] output (interleaved stereo — `AudioStegoCarrier`'s
     * class KDoc). "play working" needs this to build an `AudioTrack` with the correct channel
     * mask instead of assuming mono.
     */
    var workingChannelCount: Int by mutableStateOf(1)
        private set

    private val codecScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackJob: Job? = null

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    /** Which clip is currently playing, if any -- see [PlaybackTarget]'s KDoc. */
    var nowPlaying: PlaybackTarget? by mutableStateOf(null)
        private set

    private val idleEquivalent: Boolean
        get() = status is AudioStegoStatus.Idle ||
            status is AudioStegoStatus.Embedded ||
            status is AudioStegoStatus.ExtractedSuccess ||
            status is AudioStegoStatus.ExtractedFailure

    /** Reset to a freshly selected cover clip, discarding any prior embed/extract result. Called
     *  whenever either selector (technique or cover clip) changes. */
    fun selectCover(cover: PcmAudio) {
        workingAudio = cover
        workingChannelCount = 1
        status = AudioStegoStatus.Idle
    }

    /** Capacity of [cover] for [technique], read synchronously — cheap arithmetic only, no FFT
     *  work happens until [CovertCarrier.encode]/[CovertCarrier.decode] actually run. */
    fun maxPayloadBytesFor(cover: PcmAudio, technique: AudioStegoTechnique): Int =
        carrierFactory(cover, technique).maxPayloadBytes

    /**
     * Hide [payload] in [cover] using [technique] and make the result the new working clip.
     * No-op while busy. Always encodes into the pristine selected [cover] (never into an
     * already-embedded working clip), so repeated taps stay predictable.
     */
    fun embed(cover: PcmAudio, technique: AudioStegoTechnique, payload: ByteArray) {
        if (!idleEquivalent) return
        codecScope.launch {
            status = AudioStegoStatus.Embedding
            val carrier = carrierFactory(cover, technique)
            val stego = try {
                carrier.encode(payload)
            } catch (oversized: IllegalArgumentException) {
                status = AudioStegoStatus.Idle
                return@launch
            }
            workingAudio = stego
            workingChannelCount = if (technique == AudioStegoTechnique.PHASE_INVERSION) 2 else 1
            status = AudioStegoStatus.Embedded(payload.size)
        }
    }

    /** Attempt to recover a payload from the current [workingAudio] using [technique]. No-op
     *  while busy. */
    fun extract(technique: AudioStegoTechnique) {
        if (!idleEquivalent) return
        val sample = workingAudio
        codecScope.launch {
            status = AudioStegoStatus.Extracting
            val carrier = carrierFactory(sample, technique)
            status = when (val result = carrier.decode(sample)) {
                is DecodeResult.Success -> AudioStegoStatus.ExtractedSuccess(result.payload.decodeToString())
                is DecodeResult.Failure -> AudioStegoStatus.ExtractedFailure(result.reason, result.detail)
            }
        }
    }

    /** Plays [cover] (always mono) over the speaker. Available in any idle-equivalent state;
     *  never touches [status]. */
    fun playCover(cover: PcmAudio) {
        if (!idleEquivalent) return
        play(cover, channelCount = 1, target = PlaybackTarget.COVER)
    }

    /** Plays the current [workingAudio] at its actual [workingChannelCount]. Available in any
     *  idle-equivalent state; never touches [status]. */
    fun playWorking() {
        if (!idleEquivalent) return
        play(workingAudio, workingChannelCount, target = PlaybackTarget.WORKING)
    }

    /**
     * Stops/releases any currently-playing `AudioTrack` before starting [pcm] — tapping
     * "play working" while "play cover" is still playing cuts it off cleanly rather than
     * overlapping, per the approved design. Sets/clears [nowPlaying] around the actual playback
     * window (UX pass) so the row can show "playing X" instead of a static, feedback-free label.
     */
    private fun play(pcm: PcmAudio, channelCount: Int, target: PlaybackTarget) {
        playbackJob?.cancel()
        playbackJob = playbackScope.launch {
            stopActiveTrack()
            if (pcm.isEmpty()) return@launch
            val track = buildAudioTrack(pcm, channelCount) ?: return@launch
            activeAudioTrack = track
            nowPlaying = target
            try {
                track.write(pcm, 0, pcm.size)
                track.play()
                val frameCount = pcm.size / channelCount
                val durationMs = frameCount.toLong() * 1000L / NightjarAcoustics.SAMPLE_RATE_HZ
                delay(durationMs + 200)
            } finally {
                stopActiveTrack()
                nowPlaying = null
            }
        }
    }

    private fun stopActiveTrack() {
        activeAudioTrack?.let { track ->
            try {
                track.stop()
            } catch (alreadyStopped: IllegalStateException) {
                // Already stopped/uninitialized -- nothing to clean up.
            }
            track.release()
        }
        activeAudioTrack = null
    }

    private fun buildAudioTrack(pcm: PcmAudio, channelCount: Int): AudioTrack? {
        val channelMask = if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        return try {
            AudioTrack.Builder()
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
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (unsupported: UnsupportedOperationException) {
            null
        } catch (invalid: IllegalArgumentException) {
            null
        }
    }

    /** Cancels any in-flight embed/extract work and stops/releases playback. Call from
     *  `DisposableEffect.onDispose`. */
    fun dispose() {
        codecScope.cancel()
        playbackJob?.cancel()
        playbackScope.cancel()
        stopActiveTrack()
    }
}

// --- Previews: AudioStegoContent is pure, so these need no CovertCarrier fake. ---

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewIdle() {
    PreviewSurface {
        AudioStegoContent(
            status = AudioStegoStatus.Idle,
            technique = AudioStegoTechnique.PHASE_INVERSION,
            onSelectTechnique = {},
            techniqueCapacities = previewCapacities,
            cover = AudioSampleCover.SPOKEN_WORD,
            onSelectCover = {},
            payloadText = "the ravens have landed",
            onPayloadTextChange = {},
            maxPayloadBytes = 96,
            nowPlaying = null,
            onPlayCover = {},
            onPlayWorking = {},
            onEmbed = {},
            onExtract = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewEmbedded() {
    PreviewSurface {
        AudioStegoContent(
            status = AudioStegoStatus.Embedded(payloadBytes = 18),
            technique = AudioStegoTechnique.MFSK,
            onSelectTechnique = {},
            techniqueCapacities = previewCapacities,
            cover = AudioSampleCover.SOFT_SYNTH,
            onSelectCover = {},
            payloadText = "the ravens have landed",
            onPayloadTextChange = {},
            maxPayloadBytes = 37,
            nowPlaying = PlaybackTarget.WORKING,
            onPlayCover = {},
            onPlayWorking = {},
            onEmbed = {},
            onExtract = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewExtractedSuccess() {
    PreviewSurface {
        AudioStegoContent(
            status = AudioStegoStatus.ExtractedSuccess(text = "the ravens have landed"),
            technique = AudioStegoTechnique.SPECTROGRAM_LSB,
            onSelectTechnique = {},
            techniqueCapacities = previewCapacities,
            cover = AudioSampleCover.SPOKEN_WORD,
            onSelectCover = {},
            payloadText = "",
            onPayloadTextChange = {},
            maxPayloadBytes = 512,
            nowPlaying = null,
            onPlayCover = {},
            onPlayWorking = {},
            onEmbed = {},
            onExtract = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun PreviewExtractedFailure() {
    PreviewSurface {
        AudioStegoContent(
            status = AudioStegoStatus.ExtractedFailure(
                reason = DecodeFailure.UNRECOVERABLE_FEC,
                detail = "Reed-Solomon could not correct the received MFSK codeword (more than 8 byte errors)",
            ),
            technique = AudioStegoTechnique.MFSK,
            onSelectTechnique = {},
            techniqueCapacities = previewCapacities,
            cover = AudioSampleCover.SOFT_SYNTH,
            onSelectCover = {},
            payloadText = "",
            onPayloadTextChange = {},
            maxPayloadBytes = 37,
            nowPlaying = null,
            onPlayCover = {},
            onPlayWorking = {},
            onEmbed = {},
            onExtract = {},
            onBack = {},
        )
    }
}

/** Shared placeholder capacities for the `@Preview` functions above -- roughly matches real
 *  figures for a 5s cover (see `AudioStegoSampleCovers.kt`), doesn't need to be exact. */
private val previewCapacities = mapOf(
    AudioStegoTechnique.PHASE_INVERSION to 51,
    AudioStegoTechnique.SPECTROGRAM_LSB to 512,
    AudioStegoTechnique.MFSK to 37,
)

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(BgBase)) {
        content()
    }
}
