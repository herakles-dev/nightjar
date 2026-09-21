package dev.herakles.nightjar.modules.fireflyjar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.herakles.nightjar.LsbBitPlane
import dev.herakles.nightjar.SpectrogramData
import dev.herakles.nightjar.StereoPolarity
import dev.herakles.nightjar.WavFile
import dev.herakles.nightjar.matchCover
import dev.herakles.nightjar.modules.audiostego.AudioSampleCover
import dev.herakles.nightjar.modules.audiostego.synthesizeSampleCover
import dev.herakles.nightjar.picker.JarRole
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.spectrogram
import dev.herakles.nightjar.stegoDifference
import dev.herakles.nightjar.stereoPolarity
import dev.herakles.nightjar.ui.theme.FireflyCreated
import dev.herakles.nightjar.ui.theme.FireflyReceived
import dev.herakles.nightjar.ui.theme.JarCardBorder
import dev.herakles.nightjar.ui.theme.JarCardFill
import dev.herakles.nightjar.ui.theme.JarGlassOutline
import dev.herakles.nightjar.ui.theme.JarMessageBorder
import dev.herakles.nightjar.ui.theme.JarMessageFill
import dev.herakles.nightjar.ui.theme.JarTextPrimary
import dev.herakles.nightjar.ui.theme.JarTextSecondary
import dev.herakles.nightjar.ui.theme.JarTextTertiary
import dev.herakles.nightjar.ui.theme.JarType
import dev.herakles.nightjar.ui.theme.JarWatchingDim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Task #10 — the Firefly Jar per-module detail screen (design/screen-flow.md § Screen 7,
 * architecture.md § Firefly Jar § 6). One composable serves every [Module]: the shared shell
 * (wordmark, hero jar, firefly swarm or watching-jar readout, the module's own catch/look flow)
 * is identical for all of them, driven only by [Module.jarName]/[Module.jarRole] — this file
 * never `when`s on which specific module it is. Architecture.md § 6 reserves that exhaustive
 * per-module branch to exactly two places, [FireflyGlyphs.drawJarGlyph] and [catchFlowFor]
 * (`JarCatchFlows.kt`, task #9); this screen only hosts what [catchFlowFor] hands it — it
 * never reimplements embed/extract/transmit/listen logic itself (screen-flow.md § Screen 7
 * "what this addition deliberately does not build").
 *
 * Task #14 (design refresh, "Cozy Pixel Night") re-skins this shell against
 * `sessions/nightjar/artifacts/design-refresh/DESIGN_SPEC.md` §5 (screens 1b/1f/1g/1h): the
 * [JarNightSky] backdrop, a live [JarGlyph] hero (140×170.dp for the singing jar's hub
 * emphasis, 100×120.dp for the other three), and [JarType]/Cozy Pixel Night colors throughout.
 * The action rows / technique picker / confidence-and-history readout each module's own
 * `jarCatchFlow`/`jarWatchFlow` renders inside [moduleFlow] are untouched by this pass — that's
 * `JarCatchFlows.kt`'s dispatch plumbing, out of this file's scope.
 *
 * For [JarRole.CREATION] modules the shell shows a "your fireflies" section (or, empty, "no
 * fireflies yet") above the module's catch/look flow; for [JarRole.WATCHING] (today just the
 * detector) it skips that section entirely — that module never writes a [FireflyRecord]
 * (architecture.md § 3), and [catchFlowFor] hosts its own re-skinned confidence/history readout
 * instead.
 */
@Composable
fun JarDetailScreen(module: Module, repository: FireflyRepository, onBack: () -> Unit) {
    val fireflyFlow = remember(repository, module) { repository.observeByModule(module.name) }
    val fireflies by fireflyFlow.collectAsState(initial = emptyList())
    // F-03 fix (dup M-07): saved by id, not the record itself -- FireflyRecord isn't Parcelable,
    // and a Long id is directly Saveable with no custom Saver needed (unlike MainActivity.kt's
    // Screen, see ScreenSaver there). The record is re-derived from the live [fireflies] list
    // below every recomposition, so a firefly edited/deleted elsewhere is never stale here, and
    // Activity recreation (rotation, or a dark-mode toggle before MainActivity's configChanges fix
    // landed) no longer silently closes an open detail popup.
    var selectedFireflyId by rememberSaveable(module) { mutableStateOf<Long?>(null) }
    val selectedFirefly = fireflies.find { it.id == selectedFireflyId }
    val coroutineScope = rememberCoroutineScope()

    JarDetailContent(
        module = module,
        fireflies = fireflies,
        selectedFirefly = selectedFirefly,
        onSelectFirefly = { selectedFireflyId = it.id },
        onDismissDetail = { selectedFireflyId = null },
        // G-01/F-02 (gate-20, INV-6): per-firefly delete, routed through
        // FireflyRepository.deleteFirefly ONLY -- never a direct FireflyMediaStore/FireflyDao
        // call, since a shared (content-addressed, dedup'd) carrier file must stay
        // reference-counted (see FireflyRepository.kt's own KDoc). If the deleted firefly is the
        // one currently open in the detail popup, drop back to the swarm view immediately rather
        // than waiting for the Flow to catch up.
        onDeleteFirefly = { id ->
            coroutineScope.launch { repository.deleteFirefly(id) }
            if (selectedFireflyId == id) selectedFireflyId = null
        },
        onBack = onBack,
        moduleFlow = { catchFlowFor(module = module, repository = repository, onExit = onBack) },
        // Stage C2 (gate-18): the carrier viewer's one I/O hook. FireflyDetailContent stays a
        // pure composable with no FireflyDao/FireflyMediaStore reference of its own -- it just
        // gets handed a suspend function that already knows how to fetch bytes by mediaPath.
        loadMedia = { path -> repository.readMedia(path) },
    )
}

/**
 * Pure/previewable content: no [FireflyDao], no `Flow` collection, same stateful-root/
 * pure-content split every other screen in this app uses (e.g. `DetectorScreen`'s
 * `DetectorContent`, `JarShelfScreen`'s `JarShelfContent`). [moduleFlow] is a slot rather than
 * a direct [catchFlowFor] call, so this composable stays previewable without a real
 * [FireflyDao] or carrier behind it — same reasoning `ImageStegoContent` stays free of any
 * `ImageStegoCarrier` reference.
 */
@Composable
fun JarDetailContent(
    module: Module,
    fireflies: List<FireflyRecord>,
    selectedFirefly: FireflyRecord?,
    onSelectFirefly: (FireflyRecord) -> Unit,
    onDismissDetail: () -> Unit,
    onBack: () -> Unit,
    moduleFlow: @Composable () -> Unit,
    // G-01/F-02 (gate-20, INV-6): per-firefly delete. Default no-op keeps every existing
    // @Preview call site compiling unchanged -- same "pure/previewable" reasoning this
    // composable's own KDoc already states for [moduleFlow]/[loadMedia].
    onDeleteFirefly: (Long) -> Unit = {},
    // Default keeps every existing @Preview call site (none of which attach media) compiling
    // unchanged -- same "pure/previewable" reasoning this composable's own KDoc already states
    // for [moduleFlow].
    loadMedia: suspend (String) -> ByteArray? = { null },
) {
    // G-01/F-02: shared confirm-delete state for both entry points -- a swarm tile's long-press
    // and the detail popup's explicit delete action. Only one of the two views below is ever
    // visible at once, so one Long? + one AlertDialog covers both without duplicating the confirm
    // copy/flow. Softened jar voice (design/firefly-jar-identity.md's "cute copy... permitted"),
    // same plain-AlertDialog shape this screen's sibling ("clear history", JarShelfScreen.kt)
    // already established for a destructive confirm.
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }

    JarNightSky(modifier = Modifier.fillMaxSize()) {
        if (selectedFirefly != null) {
            FireflyDetailContent(
                module = module,
                firefly = selectedFirefly,
                onBack = onDismissDetail,
                loadMedia = loadMedia,
                onRequestDelete = { pendingDeleteId = selectedFirefly.id },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                BackRow(label = "back to the shelf", onClick = onBack)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = module.jarName,
                            style = JarType.ScreenTitle,
                            color = if (module.jarRole == JarRole.WATCHING) JarTextSecondary else JarTextPrimary,
                        )
                        Text(
                            text = jarDetailCaption(module.jarRole),
                            style = JarType.ScreenSubtitle,
                            color = if (module.jarRole == JarRole.WATCHING) JarWatchingDim else JarTextTertiary,
                        )
                    }

                    JarHero(module = module, fireflies = fireflies)

                    if (module.jarRole == JarRole.CREATION) {
                        FireflySwarmSection(
                            fireflies = fireflies,
                            onSelect = onSelectFirefly,
                            onLongPressFirefly = { pendingDeleteId = it.id },
                            loadMedia = loadMedia,
                        )
                    }

                    moduleFlow()

                    // DESIGN_SPEC.md §5 1b — the only jar detail screen with a footer; the
                    // acoustic round-trip's real distance/quiet-room envelope (architecture.md
                    // §7), not a made-up number.
                    if (module == Module.ACOUSTIC_MODEM) {
                        Text(
                            text = "works best within 1m, in a quiet room",
                            style = JarType.Footer,
                            color = JarWatchingDim,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    val confirmingDeleteId = pendingDeleteId
    if (confirmingDeleteId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("let this firefly go?") },
            text = { Text("it won't come back.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteId = null
                    onDeleteFirefly(confirmingDeleteId)
                }) {
                    Text("let it go")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("keep it")
                }
            },
        )
    }
}

@Composable
private fun BackRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = "← $label", style = JarType.BackLink, color = JarTextSecondary)
    }
}

/** Static, module-agnostic caption per [JarRole] — architecture.md § 6 keeps this file from
 *  branching on a specific [Module], only on the 2-case [JarRole] axis (same axis
 *  [FireflyDao]'s own logging discipline already draws — see architecture.md § 3). */
private fun jarDetailCaption(role: JarRole): String = when (role) {
    JarRole.CREATION -> "every firefly you've caught or spotted here"
    JarRole.WATCHING -> "hold it up and see if anything glows nearby"
}

/**
 * The module's hero jar, centered — 140×170.dp for the singing jar (DESIGN_SPEC.md §3/§5 1b:
 * the acoustic modem's hub screen gets hero emphasis), 100×120.dp for the other three (§5
 * 1f/1g/1h). The watching jar's dim/radar variant is [FireflyGlyphs.drawJarGlyph]'s own branch
 * on [Module.DETECTOR] — this call site doesn't know or care it's rendering differently.
 */
@Composable
private fun JarHero(module: Module, fireflies: List<FireflyRecord>) {
    val (heroWidth, heroHeight) = if (module == Module.ACOUSTIC_MODEM) 140.dp to 170.dp else 100.dp to 120.dp
    val visuals = remember(fireflies) {
        fireflies.map { firefly ->
            FireflyVisual(
                id = firefly.id.toInt(),
                color = if (firefly.direction == "CREATED") FireflyCreated else FireflyReceived,
            )
        }
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        JarGlyph(module = module, fireflies = visuals, modifier = Modifier.size(heroWidth, heroHeight), large = true)
    }
}

/**
 * "your fireflies" section (DESIGN_SPEC.md §5 1b/1f) — a section label over a row of tappable
 * swarm tiles, or, empty, a centered "no fireflies yet" line with no label at all (§5 1g). Only
 * ever called for [JarRole.CREATION] modules.
 *
 * Carrier-thumbnail task: [loadMedia] threads straight through from [JarDetailScreen], the same
 * hook that already reaches [FireflyDetailContent]'s carrier viewer — this composable still does
 * no I/O of its own, it only hands the hook down to [FireflySwarmTile].
 */
@Composable
private fun FireflySwarmSection(
    fireflies: List<FireflyRecord>,
    onSelect: (FireflyRecord) -> Unit,
    onLongPressFirefly: (FireflyRecord) -> Unit,
    loadMedia: suspend (String) -> ByteArray?,
) {
    if (fireflies.isEmpty()) {
        Text(
            text = "no fireflies yet",
            style = JarType.Footer,
            // The dimmest text tier (#6B6690) — same numeric value as JarWatchingDim, reused
            // here for its color, not its "watching jar" meaning (DESIGN_SPEC.md §1).
            color = JarWatchingDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = "your fireflies", style = JarType.SectionLabel, color = JarTextTertiary)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // key() on firefly.id, not list position: the underlying query is timestampMillis
            // DESC, so a fresh catch shifts every existing tile's position by one. Without an
            // explicit key, Compose would reattach each slot's remembered decode state to
            // whatever record now occupies that position rather than the record that originally
            // decoded it -- keying on id keeps a tile's cached thumbnail (and its in-flight
            // LaunchedEffect, if the decode hasn't finished yet) with the firefly it belongs to
            // no matter where it lands in the row after a reorder.
            fireflies.forEach { firefly ->
                key(firefly.id) {
                    FireflySwarmTile(
                        record = firefly,
                        onClick = { onSelect(firefly) },
                        // G-01 (gate-20): long-press a swarm thumbnail to request delete --
                        // confirmed by the shared AlertDialog in JarDetailContent, never deleted
                        // straight from a gesture.
                        onLongClick = { onLongPressFirefly(firefly) },
                        loadMedia = loadMedia,
                    )
                }
            }
        }
    }
}

/**
 * One tappable firefly in the "your fireflies" swarm — glow driven by [fireflyAlpha] /
 * [rememberFireflyClock], the same breathing function every other firefly in this app now uses
 * (DESIGN_SPEC.md §4.3), rather than a bespoke tween. Deterministic per [FireflyRecord.id] so
 * the same firefly breathes the same way across recompositions and in `@Preview`.
 *
 * Unchanged by the carrier-thumbnail task — this is still the entire treatment for a
 * [FireflyRecord] with no carrier ([FireflyRecord.carrierKind] null), and [FireflySwarmTile]
 * also falls back to this exact composable for a media-bearing record whose thumbnail is still
 * decoding or failed to load.
 *
 * [onLongClick] (G-01, gate-20) requests the shared confirm-delete dialog in [JarDetailContent] --
 * this composable never deletes anything itself, it only reports the gesture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FireflyDot(record: FireflyRecord, onClick: () -> Unit, onLongClick: () -> Unit) {
    val color = if (record.direction == "CREATED") FireflyCreated else FireflyReceived
    val clock = rememberFireflyClock()
    Canvas(
        modifier = Modifier
            .size(28.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            // P3: a bare Canvas has no text a screen reader can read at all otherwise.
            .semantics(mergeDescendants = true) {
                contentDescription = fireflySwarmContentDescription(record)
                customActions = listOf(
                    CustomAccessibilityAction("let this firefly go") { onLongClick(); true },
                )
            },
    ) {
        val alpha = fireflyAlpha(record.id.toInt(), clock.value)
        val radius = size.minDimension * 0.22f
        drawCircle(color = color.copy(alpha = 0.28f * alpha), radius = radius * 2.2f)
        drawCircle(color = color.copy(alpha = alpha), radius = radius)
    }
}

private const val SWARM_THUMB_SIZE_DP = 64

/** Sparkline column count for the swarm's small AUDIO tile — well under [WAVEFORM_COLUMNS] (120,
 *  the detail screen's full-width waveform), since a 64dp box has nowhere near the horizontal
 *  room to resolve that many bars. */
private const val SWARM_WAVEFORM_COLUMNS = 28

/** Downsample target for the swarm's IMAGE tile, in raw pixels — see [calculateInSampleSize].
 *  64dp is ~167px on the Pixel 6a's own density (2.61x); 160 covers that with a little headroom
 *  without decoding anywhere near a source image's full resolution. */
private const val SWARM_THUMB_REQ_PX = 160

/** What a swarm tile has to show once its carrier finishes loading, or why it doesn't. Kept
 *  small on purpose: [Image] holds a real (downsampled) [Bitmap], but [Audio] holds only the
 *  already-reduced [FloatArray] of peaks, never the [WavFile.ParsedWav] samples decoded to
 *  produce them — a swarm full of AUDIO fireflies never keeps a multi-megabyte PCM buffer alive
 *  per tile once its peaks are computed. */
private sealed interface SwarmThumbnail {
    data class Image(val bitmap: Bitmap) : SwarmThumbnail
    data class Audio(val peaks: FloatArray) : SwarmThumbnail
    data object Failed : SwarmThumbnail
}

/**
 * Decodes and caches one firefly's swarm thumbnail, keyed on [FireflyRecord.id] — exactly once
 * per id, off the composition thread, never inline in a draw scope. [rememberFireflyClock]'s
 * breathing tick recomposes this screen continuously (every jar and dot on it reads that clock),
 * so decode work done directly in a composable body would re-run on every one of those frames;
 * keying both the state and the [LaunchedEffect] on [FireflyRecord.id] means this only (re)runs
 * when the tile's underlying firefly actually changes — once per id for the lifetime of this
 * composable's slot (see [FireflySwarmSection]'s `key()` note for why that slot stays attached to
 * the same id across list reorders).
 *
 * Returns null while still decoding — [FireflySwarmTile] falls back to [FireflyDot] for that
 * case too, same as an outright failure.
 */
@Composable
private fun rememberSwarmThumbnail(
    record: FireflyRecord,
    kind: String,
    loadMedia: suspend (String) -> ByteArray?,
): SwarmThumbnail? {
    var thumbnail by remember(record.id) { mutableStateOf<SwarmThumbnail?>(null) }
    LaunchedEffect(record.id) {
        val path = record.mediaPath
        if (path == null) {
            thumbnail = SwarmThumbnail.Failed
            return@LaunchedEffect
        }
        val bytes = withContext(Dispatchers.IO) { loadMedia(path) }
        thumbnail = if (bytes == null) {
            SwarmThumbnail.Failed
        } else {
            when (kind) {
                "IMAGE" -> decodeSwarmImageThumbnail(bytes)?.let { SwarmThumbnail.Image(it) } ?: SwarmThumbnail.Failed
                "AUDIO" -> decodeSwarmAudioPeaks(bytes)?.let { SwarmThumbnail.Audio(it) } ?: SwarmThumbnail.Failed
                else -> SwarmThumbnail.Failed
            }
        }
    }
    return thumbnail
}

/**
 * Downsampled swarm-tile bitmap — [BitmapFactory]'s standard two-pass `inJustDecodeBounds` +
 * [BitmapFactory.Options.inSampleSize] technique (see [calculateInSampleSize]), never a
 * full-resolution decode scaled down afterward. A full-size image decoded at source resolution
 * and then laid out at 64dp would still hold every source pixel in memory for as long as the
 * tile stays composed — with several image fireflies in one swarm, that's the OOM path this
 * task's brief calls out. This is the same [BitmapFactory.decodeByteArray] primitive
 * [FireflyCarrierBlock] already uses for the detail screen, with a sampling pass added in front
 * of it — not a second decode path.
 */
private suspend fun decodeSwarmImageThumbnail(bytes: ByteArray): Bitmap? = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, SWARM_THUMB_REQ_PX)
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/** Largest power-of-2 [BitmapFactory.Options.inSampleSize] that keeps both dimensions at or
 *  above [reqPx] — Android's own documented "Loading Large Bitmaps Efficiently" algorithm,
 *  applied here instead of decoding at full resolution and downscaling the resulting [Bitmap]
 *  afterward (which pays the full-resolution decode's memory cost before throwing most of it
 *  away). A no-op ([inSampleSize] stays `1`) for source images already at or under [reqPx] — true
 *  today for this app's own 100x100 bundled sample covers. */
private fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, reqPx: Int): Int {
    var inSampleSize = 1
    if (rawHeight > reqPx || rawWidth > reqPx) {
        val halfHeight = rawHeight / 2
        val halfWidth = rawWidth / 2
        while ((halfHeight / inSampleSize) >= reqPx && (halfWidth / inSampleSize) >= reqPx) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

/**
 * Swarm-tile peaks — reuses [WavFile.decodePcm16] and [waveformPeaks] verbatim, the same decode
 * and peak-computation [FireflyCarrierBlock] already established for the detail screen's
 * full-width waveform, just with [SWARM_WAVEFORM_COLUMNS] in place of the detail view's
 * [WAVEFORM_COLUMNS]. The decoded [WavFile.ParsedWav.samples] — up to roughly a million shorts
 * for a multi-second acoustic clip — never leaves this function: only the reduced [FloatArray] of
 * peaks (tens of floats) is returned and cached, so a swarm full of AUDIO fireflies doesn't hold
 * one full PCM buffer per tile the way keeping [WavFile.ParsedWav] itself around would.
 */
private suspend fun decodeSwarmAudioPeaks(bytes: ByteArray): FloatArray? = withContext(Dispatchers.IO) {
    val parsed = WavFile.decodePcm16(bytes) ?: return@withContext null
    waveformPeaks(parsed.samples, parsed.numChannels, SWARM_WAVEFORM_COLUMNS)
}

/**
 * The swarm's ~64dp rounded carrier tile (this task): an IMAGE firefly's downsampled bitmap, or
 * an AUDIO firefly's static sparkline, inside the same direction-colored glow chrome
 * ([swarmThumbModifier]) [FireflyCreated]/[FireflyReceived] already carry everywhere else on this
 * screen. A null [FireflyRecord.carrierKind] renders the original 28dp [FireflyDot] unchanged —
 * no carrier, nothing new to show. The same [FireflyDot] fallback also covers a media-bearing
 * firefly whose thumbnail is still decoding or failed to load, per this task's brief ("fall back
 * to the dot rather than an error tile") — never an error glyph, never an empty gray box.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FireflySwarmTile(
    record: FireflyRecord,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    loadMedia: suspend (String) -> ByteArray?,
) {
    val kind = record.carrierKind
    if (kind == null) {
        FireflyDot(record = record, onClick = onClick, onLongClick = onLongClick)
        return
    }
    val color = if (record.direction == "CREATED") FireflyCreated else FireflyReceived
    when (val thumbnail = rememberSwarmThumbnail(record = record, kind = kind, loadMedia = loadMedia)) {
        null, SwarmThumbnail.Failed -> FireflyDot(record = record, onClick = onClick, onLongClick = onLongClick)
        is SwarmThumbnail.Image -> Box(
            modifier = swarmThumbModifier(color)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                // P3: mergeDescendants folds the Image's own description in below -- nulled
                // out there so it doesn't get appended onto this one, and this one names the
                // actual firefly rather than just "a picture".
                .semantics(mergeDescendants = true) {
                    contentDescription = fireflySwarmContentDescription(record)
                    customActions = listOf(
                        CustomAccessibilityAction("let this firefly go") { onLongClick(); true },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = thumbnail.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        is SwarmThumbnail.Audio -> Box(
            modifier = swarmThumbModifier(color)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                // P3: same as the FireflyDot case -- FireflySwarmWaveform is a bare Canvas.
                .semantics(mergeDescendants = true) {
                    contentDescription = fireflySwarmContentDescription(record)
                    customActions = listOf(
                        CustomAccessibilityAction("let this firefly go") { onLongClick(); true },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            FireflySwarmWaveform(
                peaks = thumbnail.peaks,
                color = color,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
            )
        }
    }
}

/** Shared chrome for the swarm's carrier tiles — a soft radial [color] glow behind the content
 *  plus a [color]-tinted border, the same visual grammar [FireflyDot]'s own double-circle glow
 *  already establishes for direction color, applied here as card chrome instead of two drawn
 *  circles. */
private fun swarmThumbModifier(color: Color): Modifier = Modifier
    .size(SWARM_THUMB_SIZE_DP.dp)
    .clip(RoundedCornerShape(12.dp))
    .background(Brush.radialGradient(0f to color.copy(alpha = 0.16f), 1f to color.copy(alpha = 0.04f)))
    .border(width = 1.dp, color = color.copy(alpha = 0.35f), shape = RoundedCornerShape(12.dp))

/**
 * Static (non-playing) peak-per-column sparkline for a swarm AUDIO tile — the same bar-chart
 * grammar [FireflyWaveform] draws on the detail screen, minus the playhead/progress state a
 * glance-sized thumbnail has no room for (tapping the tile opens the detail screen's real
 * play/stop control, which already exists). Draws [peaks] as given, never recomputes them, and
 * reads no clock — a still sparkline costs nothing per frame, unlike the breathing dot it
 * replaces.
 */
@Composable
private fun FireflySwarmWaveform(peaks: FloatArray, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (peaks.isEmpty()) return@Canvas
        val columnWidth = size.width / peaks.size
        val midY = size.height / 2f
        peaks.forEachIndexed { index, peak ->
            val x = index * columnWidth + columnWidth / 2f
            val barHeight = (size.height * 0.85f) * peak.coerceAtLeast(0.05f)
            drawLine(
                color = color.copy(alpha = 0.75f),
                start = Offset(x, midY - barHeight / 2f),
                end = Offset(x, midY + barHeight / 2f),
                strokeWidth = (columnWidth * 0.6f).coerceAtLeast(1f),
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Tapping a firefly dot's detail popup (screen-flow.md § Screen 7). [FireflyRecord
 * .payloadPreview]/[FireflyRecord.payloadSizeBytes] are shown verbatim — the same "real data,
 * not smoothed" discipline the technical screens apply to FEC counts and analyzer detail
 * strings. Doesn't name the module's specific channel (e.g. "sent through sound") since that
 * string doesn't exist as a [Module] field and adding one would mean a third per-module branch
 * point this file is built to avoid (architecture.md § 6) — [module]'s [Module.jarName] already
 * ties the record back to its jar without one.
 *
 * Task #16 brings this popup onto the refreshed surface too (DESIGN_SPEC.md §5 1c): the tapped
 * firefly gets a [HeroFirefly] out of its jar, and the payload sits in a gold-tinted box above
 * a size/channel/direction metadata row. Only `direction` takes an accent color — it's the one
 * value that says which way the message went.
 *
 * Stage C2 (gate-18) adds the carrier viewer: when [FireflyRecord.carrierKind] is non-null, a
 * [FireflyCarrierBlock] renders between the timestamp and the message box — an image render or a
 * waveform + play/stop control, depending on [FireflyRecord.carrierKind] ("IMAGE"/"AUDIO"), never
 * on [module] (architecture.md § 6). `carrierKind == null` (every pre-migration record, e.g. the
 * physical device's own fireflies id 34/35/36) renders nothing new here at all — this function's
 * text-only layout stays exactly what it was before this stage.
 */
@Composable
private fun FireflyDetailContent(
    module: Module,
    firefly: FireflyRecord,
    onBack: () -> Unit,
    loadMedia: suspend (String) -> ByteArray?,
    // G-01/F-02 (gate-20): a visible delete action, because a swarm tile's long-press
    // ([FireflyDot]/[FireflySwarmTile]) isn't discoverable on its own -- this is the popup's own
    // entry point into the same shared confirm-delete dialog ([JarDetailContent]).
    onRequestDelete: () -> Unit = {},
) {
    val caught = firefly.direction == "CREATED"
    val accent = if (caught) FireflyCreated else FireflyReceived

    // No JarNightSky here — JarDetailContent already has this composable inside one, and a
    // second backdrop would just run a duplicate starfield under the first.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        BackRow(label = "back to the jar", onClick = onBack)

        HeroFirefly(
            color = accent,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 20.dp, bottom = 28.dp)
                .size(80.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (caught) "a firefly you caught" else "a firefly you spotted",
                style = JarType.ActionTitle,
                color = accent,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "${formatFireflyTime(firefly.timestampMillis)}, ${module.jarName}",
                style = JarType.Timestamp,
                color = JarTextSecondary,
                textAlign = TextAlign.Center,
            )
        }

        firefly.carrierKind?.let { kind ->
            FireflyCarrierBlock(firefly = firefly, kind = kind, loadMedia = loadMedia, accent = accent)
        }

        firefly.payloadPreview?.let { preview ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(JarMessageFill)
                    .border(1.dp, JarMessageBorder, RoundedCornerShape(10.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "message", style = JarType.MetaLabel, color = JarTextTertiary)
                Text(text = preview, style = JarType.Body, color = JarTextPrimary)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            // P2 (on-device review): three tiles used to share the row unevenly -- each
            // measured at its own natural width in order, so whatever "size"/"channel" didn't
            // use was all "direction" got, and on a real Pixel 6a that left too little for the
            // word "direction" and it hard-wrapped mid-letter ("DIRECTIO"/"N"). Equal weights
            // give every tile the same third of the row regardless of its neighbors' content,
            // and softWrap=false + TextOverflow.Visible is the backstop: a label or value never
            // truncates and never breaks mid-word, even if it's still tight at 1.3x font scale --
            // it can only ever spill past its own tile's edge, whole.
            MetaCard(
                label = "size",
                value = fireflyByteLabel(firefly.payloadSizeBytes),
                modifier = Modifier.weight(1f),
            )
            MetaCard(
                label = "channel",
                value = module.jarChannel,
                modifier = Modifier.weight(1f),
            )
            MetaCard(
                label = "direction",
                value = if (caught) "caught" else "spotted",
                valueColor = accent,
                modifier = Modifier.weight(1f),
            )
        }

        // G-01/F-02 (gate-20): the popup's own visible delete affordance -- long-press on the
        // swarm thumbnail reaches the same dialog, but isn't discoverable by itself.
        Text(
            text = "let this firefly go",
            style = JarType.Footer,
            color = JarWatchingDim,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRequestDelete)
                .padding(top = 20.dp),
        )
    }
}

/** One of the three mini-cards under a firefly's message (DESIGN_SPEC.md §5 1c). */
@Composable
private fun MetaCard(
    label: String,
    value: String,
    valueColor: Color = JarTextPrimary,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(JarCardFill)
            .border(1.dp, JarCardBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // P2: neither line wraps -- a wrapped single word breaks mid-letter with no good
        // hyphenation point, so a too-narrow tile instead overflows its own bounds whole
        // rather than mangling the word. See the call site's comment for why the tile is
        // rarely that narrow in the first place.
        Text(
            text = label,
            style = JarType.MetaLabel,
            color = JarTextTertiary,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
        Text(
            text = value,
            style = JarType.MetaValue,
            color = valueColor,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}

private fun formatFireflyTime(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMillis))

private fun fireflyByteLabel(bytes: Int): String = if (bytes == 1) "1 byte" else "$bytes bytes"

/**
 * P3 (on-device review, accessibility): a swarm tile's spoken/semantic label. Without it,
 * TalkBack (and UI automation) reaches an unlabeled glyph -- [FireflyDot] and
 * [FireflySwarmWaveform] are bare [androidx.compose.foundation.Canvas]es with no text of their
 * own, and the carrier-thumbnail [Image] only ever said what kind of thing it was ("the image
 * this firefly hid inside"), never which firefly. Mirrors [FireflyDetailContent]'s own
 * "a firefly you caught"/timestamp/[fireflyByteLabel] copy so the spoken label and the visible
 * detail screen agree on the same firefly. `internal` and Compose-free so
 * `CarrierInsightCaptionsTest`'s sibling JVM tests can drive it directly.
 */
internal fun fireflySwarmContentDescription(record: FireflyRecord): String {
    val direction = if (record.direction == "CREATED") "firefly you caught" else "firefly you spotted"
    return "$direction, ${formatFireflyTime(record.timestampMillis)}, ${fireflyByteLabel(record.payloadSizeBytes)}"
}

// Stage D/4 (gate-19): the carrier's media size, human-readable. SI (1000-based) to match the
// "MB" copy the shelf storage readout uses. Locale.US keeps the decimal point deterministic.
internal fun fireflyMediaSizeLabel(bytes: Long): String = when {
    bytes < 1000L -> if (bytes == 1L) "1 byte" else "$bytes bytes"
    bytes < 1_000_000L -> String.format(Locale.US, "%.0f KB", bytes / 1000.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
}

internal fun fireflyCapacityPercent(percent: Double): String = when {
    percent <= 0.0 -> "0%"
    percent < 0.001 -> "<0.001%"
    percent >= 1.0 -> String.format(Locale.US, "%.1f%%", percent)
    else -> String.format(Locale.US, "%.3f%%", percent)
}

// Stage D/4 (gate-19): the single highest-value teaching string on the detail screen -- how
// little of the carrier the payload occupies. "hid 13 bytes in 1.2 MB · 0.001% used".
internal fun fireflyCapacityLine(payloadBytes: Int, mediaBytes: Long): String {
    val percent = if (mediaBytes > 0L) payloadBytes.toDouble() / mediaBytes.toDouble() * 100.0 else 0.0
    return "hid ${fireflyByteLabel(payloadBytes)} in ${fireflyMediaSizeLabel(mediaBytes)} · ${fireflyCapacityPercent(percent)} used"
}

/**
 * The carrier viewer (gate-18, v4 addition): what an image or audio firefly actually looked or
 * sounded like. Only ever composed when [FireflyRecord.carrierKind] is non-null — a
 * pre-migration or media-less record never reaches this function, so [FireflyDetailContent]'s
 * text-only layout stays byte-for-byte unchanged for fireflies like the physical device's own id
 * 34/35/36.
 *
 * Branches on [kind] ("IMAGE"/"AUDIO"), a plain `String` field on the record — never on
 * [Module]. That's deliberate: architecture.md § 6 permits exactly two exhaustive per-[Module]
 * `when`s ([FireflyGlyphs.drawJarGlyph] and [catchFlowFor]), and [FireflyRecord.carrierKind]
 * exists precisely so this file doesn't need a third.
 *
 * Decoding (PNG via [BitmapFactory], WAV via [WavFile.decodePcm16]) is real work, so both run
 * inside [LaunchedEffect] keyed on [firefly]'s id — re-decoding only when the selected firefly
 * changes, never on plain recomposition, which this screen's breathing clock drives constantly —
 * and off the composition thread via [Dispatchers.IO]. [loadMedia] is the pure I/O hook
 * [JarDetailScreen] threads down from `FireflyRepository.readMedia`; this composable never
 * touches a media store or a `Context` directly, matching every other pure/previewable
 * composable in this file.
 *
 * Stage D/2 (gate-19) adds the IMAGE case's "where it hid" layer: [LsbBitPlane.ofBitmap] runs
 * right after the PNG decode, same [LaunchedEffect], same [Dispatchers.Default] hop, so the
 * bit-plane bitmap is ready by the time the thumbnail is — no per-tap recompute. An "image" /
 * "bit-plane" [FireflyBitPlaneToggle] swaps which bitmap the 96dp thumbnail renders.
 *
 * Stage D/3 (gate-19) adds the AUDIO case's own "where it hid" layer, same shape: [spectrogram]
 * runs right after the WAV decode, same [LaunchedEffect], a [Dispatchers.Default] hop, tinted
 * into an accent-colored [ImageBitmap] ([spectrogramImageBitmap]) so it's ready by the time the
 * waveform is. A "waveform" / "spectrogram" [FireflyAudioViewToggle] swaps which the carrier
 * shows. Unlike the IMAGE toggle, this one also honestly labels itself — [audioSpectrogramCaption]
 * brackets what a magnitude spectrogram genuinely does and doesn't reveal per
 * [FireflyRecord.technique] across all four AUDIO cases — MFSK, the acoustic modem, and (per
 * [SpectrogramTest]'s measurements, corrected 2026-08-05 after rev-t2's adversarial-lite HIGH
 * finding) PHASE_INVERSION are all genuinely visible once mono-mixed; only SPECTROGRAM_LSB's
 * sub-perceptual QIM nudge stays below a coherent visibility threshold. See that function's
 * KDoc for the measured basis of each case.
 *
 * v5 addition (design-v5.md §3.5/§4.3, gate-24/25) extends the AUDIO case once more: right after
 * `spectrogramImage`, this same [LaunchedEffect] also precomputes the two honest carrier views
 * design-v5.md added — a re-derived-cover difference map for
 * [dev.herakles.nightjar.AudioStegoTechnique.SPECTROGRAM_LSB] mono clips and an L/R polarity
 * readout for [dev.herakles.nightjar.AudioStegoTechnique.PHASE_INVERSION] stereo clips — gated
 * by [audioCarrierViewOptions], never by a new per-[Module] `when`. See [FireflyAudioCarrier]'s
 * own KDoc for how the toggle offers and renders them.
 */
@Composable
private fun FireflyCarrierBlock(
    firefly: FireflyRecord,
    kind: String,
    loadMedia: suspend (String) -> ByteArray?,
    accent: Color,
) {
    var bitmap by remember(firefly.id) { mutableStateOf<Bitmap?>(null) }
    // Stage D/2 (gate-19): the IMAGE carrier's LSB bit-plane, precomputed alongside `bitmap`
    // (same LaunchedEffect, same IO/Default dispatch) rather than on toggle-tap -- the transform
    // is cheap ([LsbBitPlane]'s KDoc) but there's no reason to do it on the composition thread
    // when this coroutine is already off it. Null until ready; `showBitPlane` below only ever
    // has something to show once it is.
    var bitPlaneBitmap by remember(firefly.id) { mutableStateOf<Bitmap?>(null) }
    var showBitPlane by remember(firefly.id) { mutableStateOf(false) }
    var wav by remember(firefly.id) { mutableStateOf<WavFile.ParsedWav?>(null) }
    var peaks by remember(firefly.id) { mutableStateOf<FloatArray?>(null) }
    // Stage D/3 (gate-19): the AUDIO carrier's spectrogram, precomputed and tinted into an
    // ImageBitmap alongside `wav`/`peaks` (same LaunchedEffect, same Default hop) -- same
    // "precompute once, never on toggle-tap" reasoning `bitPlaneBitmap` above already documents.
    // Null until ready; the view toggle only ever has something to show once it is.
    var spectrogramImage by remember(firefly.id) { mutableStateOf<ImageBitmap?>(null) }
    var carrierView by remember(firefly.id) { mutableStateOf(AudioCarrierView.WAVEFORM) }
    // v5 addition (design-v5.md §3.5/§4.3, gate-24/25): the two "honest carrier view" insights,
    // precomputed alongside `spectrogramImage` above in the same LaunchedEffect/Default hop --
    // same discipline, one step further. [differenceReady]/[polarityReady] separate "still
    // computing" from "computed, nothing to show": `differenceInsight == null` once
    // [differenceReady] is true is gate-24's own withheld case (INV-8) -- [StegoDifferenceView]
    // renders that reason itself, this file never guesses one. [stereoPolarity] never returns
    // null, so [polarity] only needs [polarityReady] to gate the toggle's "working" fallback
    // while it's mid-flight. Both stay null/false for every firefly whose technique/channel
    // count doesn't earn a new option (gate-25) -- see [audioCarrierViewOptions].
    var differenceInsight by remember(firefly.id) { mutableStateOf<StegoDifferenceInsight?>(null) }
    var differenceReady by remember(firefly.id) { mutableStateOf(false) }
    var polarity by remember(firefly.id) { mutableStateOf<StereoPolarity?>(null) }
    var polarityReady by remember(firefly.id) { mutableStateOf(false) }
    var failed by remember(firefly.id) { mutableStateOf(false) }

    LaunchedEffect(firefly.id) {
        val path = firefly.mediaPath
        if (path == null) {
            failed = true
            return@LaunchedEffect
        }
        val bytes = withContext(Dispatchers.IO) { loadMedia(path) }
        if (bytes == null) {
            failed = true
            return@LaunchedEffect
        }
        when (kind) {
            "IMAGE" -> {
                val decoded = withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (decoded == null) {
                    failed = true
                } else {
                    val bitPlane = withContext(Dispatchers.Default) { LsbBitPlane.ofBitmap(decoded) }
                    bitmap = decoded
                    bitPlaneBitmap = bitPlane
                }
            }
            "AUDIO" -> {
                val decoded = withContext(Dispatchers.IO) {
                    WavFile.decodePcm16(bytes)?.let { parsed ->
                        parsed to waveformPeaks(parsed.samples, parsed.numChannels, WAVEFORM_COLUMNS)
                    }
                }
                if (decoded == null) {
                    failed = true
                } else {
                    val (parsedWav, computedPeaks) = decoded
                    wav = parsedWav
                    peaks = computedPeaks
                    spectrogramImage = withContext(Dispatchers.Default) {
                        val data = spectrogram(parsedWav.samples, channels = parsedWav.numChannels)
                        spectrogramImageBitmap(data, accent)
                    }
                    // v5 addition (design-v5.md §3.5, gate-24): re-derive the cover and diff
                    // against it, gated to exactly the technique/channel combo matchCover's
                    // energy-ratio statistic is valid for (design-v5.md §3.1 -- MFSK's tones
                    // outweigh the cover, so this never runs for that technique). Both matchCover
                    // and stegoDifference run inside the same Default hop -- the ~480 KB
                    // re-derived cover a CoverMatch carries never survives past this block, only
                    // the reduced StegoDifferenceInsight reaches Compose state.
                    if (firefly.technique == "SPECTROGRAM_LSB" && parsedWav.numChannels == 1) {
                        differenceInsight = withContext(Dispatchers.Default) {
                            val match = matchCover(
                                parsedWav.samples,
                                AudioSampleCover.entries.map { it.label to { synthesizeSampleCover(it) } },
                            )
                            match?.let { StegoDifferenceInsight(stegoDifference(it.cover, parsedWav.samples), it.label) }
                        }
                        differenceReady = true
                    }
                    // v5 addition (design-v5.md §4.3, gate-25): the persisted PHASE_INVERSION
                    // clip is already stereo end-to-end (design-v5.md §4.1), so this needs no
                    // re-derivation step -- just the same off-main compute discipline.
                    if (firefly.technique == "PHASE_INVERSION" && parsedWav.numChannels == 2) {
                        polarity = withContext(Dispatchers.Default) { stereoPolarity(parsedWav.samples) }
                        polarityReady = true
                    }
                }
            }
            else -> failed = true
        }
    }

    // F-04 fix: FireflyPlayer requests real audio focus (AudioManager.requestAudioFocus) before
    // playing and abandons it on stop/release -- see FireflyPlayer.kt's own KDoc. AudioManager is
    // resolved here (the only place in this file that touches a Context) and handed down as a
    // constructor argument, keeping FireflyPlayer itself Context-free.
    val audioManager = LocalContext.current.getSystemService(AudioManager::class.java)
    val player = remember(firefly.id) { FireflyPlayer(audioManager) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // Local vals -- `by remember` properties don't smart-cast, so the `when` below reads these
    // instead of `bitmap`/`wav`/`peaks` directly.
    val currentBitmap = bitmap
    val currentBitPlane = bitPlaneBitmap
    val currentWav = wav
    val currentPeaks = peaks
    if (currentBitmap == null && currentWav == null && !failed) return // still decoding -- nothing to show yet

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(JarCardFill)
            .border(width = 1.dp, color = JarCardBorder, shape = RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "carrier", style = JarType.MetaLabel, color = JarTextTertiary)
        if (firefly.mediaBytes > 0L) {
            Text(
                text = fireflyCapacityLine(firefly.payloadSizeBytes, firefly.mediaBytes),
                style = JarType.Footer,
                color = JarTextTertiary,
            )
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                failed -> Text(
                    text = "the carrier didn't survive",
                    style = JarType.Footer,
                    color = JarWatchingDim,
                    textAlign = TextAlign.Center,
                )
                currentBitmap != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val displayed = if (showBitPlane && currentBitPlane != null) currentBitPlane else currentBitmap
                    Image(
                        bitmap = displayed.asImageBitmap(),
                        contentDescription = if (showBitPlane && currentBitPlane != null) {
                            "the LSB bit-plane of the image this firefly hid inside -- bright pixels are where a payload bit lives"
                        } else {
                            "the image this firefly hid inside"
                        },
                        modifier = Modifier
                            .size(96.dp)
                            .border(width = 1.dp, color = JarGlassOutline),
                    )
                    if (currentBitPlane != null) {
                        FireflyBitPlaneToggle(
                            showBitPlane = showBitPlane,
                            accent = accent,
                            onToggle = { showBitPlane = it },
                        )
                        // U-02: the bit-plane toggle's own honesty caption -- see
                        // imageBitPlaneCaption's KDoc for what it claims and how that's grounded.
                        if (showBitPlane) {
                            Text(
                                text = imageBitPlaneCaption(),
                                style = JarType.Footer,
                                color = JarWatchingDim,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                currentWav != null && currentPeaks != null -> FireflyAudioCarrier(
                    wav = currentWav,
                    peaks = currentPeaks,
                    spectrogramImage = spectrogramImage,
                    carrierView = carrierView,
                    onSelectView = { carrierView = it },
                    availableViews = audioCarrierViewOptions(firefly.technique, currentWav.numChannels),
                    differenceInsight = differenceInsight,
                    differenceReady = differenceReady,
                    polarity = polarity,
                    polarityReady = polarityReady,
                    technique = firefly.technique,
                    accent = accent,
                    player = player,
                )
            }
        }
    }
}

/**
 * Stage D/2 (gate-19) — the "image" / "bit-plane" switch under an IMAGE carrier's thumbnail.
 * Two tappable labels, not a Material `Switch`: this reuses the exact selected/unselected color
 * grammar `ImageStegoScreen.kt`'s `JarCoverRow` already established for this app's other
 * two-option pick (selected text takes the firefly's own [accent], unselected drops to
 * [JarTextTertiary]) rather than introducing a new control shape for one toggle. [JarType.Footer]
 * — this app's dimmest text tier — keeps the control visibly secondary to the 96dp thumbnail
 * above it, matching the "data visualization, not decoration" framing in
 * `design/firefly-jar-identity.md`.
 */
@Composable
private fun FireflyBitPlaneToggle(showBitPlane: Boolean, accent: Color, onToggle: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "image",
            style = JarType.Footer,
            color = if (!showBitPlane) accent else JarTextTertiary,
            modifier = Modifier.clickable(onClick = { onToggle(false) }),
        )
        Text(
            text = "bit-plane",
            style = JarType.Footer,
            color = if (showBitPlane) accent else JarTextTertiary,
            modifier = Modifier.clickable(onClick = { onToggle(true) }),
        )
    }
}

/**
 * U-02 — the bit-plane toggle's own caption, the IMAGE carrier's missing counterpart to
 * [audioSpectrogramCaption]. Static, not per-firefly: every IMAGE carrier's bit-plane looks the
 * same way for the same reason, whether the cover was one of the two bundled
 * [dev.herakles.nightjar.modules.imagestego.SampleCover]s or a photo picked from the device
 * (task #33).
 *
 * The honest claim, not the flattering one: an *untouched* photo's least-significant bits
 * already read as static, not as a smooth picture — real sensor noise (and, for a re-encoded
 * picked photo, lossy compression) leaves the bottom bit close to a coin flip from one pixel to
 * the next, long before any payload touches it. So the bit-plane can't be read as a treasure
 * map ("the fuzzy patch is where the message is"): a payload region and an untouched region
 * both look like fuzz, and this caption says that instead of implying otherwise.
 *
 * States nothing per-firefly and no specific figure, so there's no number here for a pure
 * function to compute — but the underlying claim is still grounded in real measurement, not
 * asserted on faith: [ImageBitPlaneCaptionTest] decodes both bundled
 * [dev.herakles.nightjar.modules.imagestego.SampleCover] PNGs (the exact resources
 * [dev.herakles.nightjar.ImageStegoCarrier] embeds into) and confirms [LsbBitPlane.compute]'s
 * output on each never holds a same-color run longer than a handful of pixels, in any row or
 * column — measured max 6 of 100 on both bundled covers — so neither one's own bit-plane has a
 * smooth patch to point at.
 */
internal fun imageBitPlaneCaption(): String =
    "even an untouched photo's bit-plane already looks like static, not a picture. fuzz here doesn't mean a message is hiding here."

/**
 * v5 addition (design-v5.md §5, gate-24/25) — the AUDIO carrier's view switch, generalized from
 * Stage D/3's original two-way `Boolean showSpectrogram` to the up-to-four views this file can
 * now show for one firefly. [label] is the toggle's own tappable text
 * ([FireflyAudioViewToggle]). Which of the four apply to a given firefly is decided by
 * [audioCarrierViewOptions], never by a `when` over [dev.herakles.nightjar.picker.Module]
 * (architecture.md § 6).
 */
internal enum class AudioCarrierView(val label: String) {
    WAVEFORM("waveform"),
    SPECTROGRAM("spectrogram"),
    DIFFERENCE("difference"),
    POLARITY("polarity"),
}

/**
 * v5 addition (design-v5.md §5, gate-24/25) — which [AudioCarrierView]s a firefly's audio
 * carrier offers, always starting from the two Stage D/3 (gate-19) baseline views. "difference"
 * is added only for [dev.herakles.nightjar.AudioStegoTechnique.SPECTROGRAM_LSB] mono clips —
 * matching exactly the technique/channel combination [dev.herakles.nightjar.matchCover]'s
 * energy-ratio statistic is valid for (design-v5.md §3.1: MFSK's additive tones outweigh the
 * cover, so this never offers "difference" there). "polarity" is added only for
 * [dev.herakles.nightjar.AudioStegoTechnique.PHASE_INVERSION] stereo clips — there is nothing to
 * show against a single channel. Every other combination — MFSK, the acoustic modem's `null`
 * technique, a mono clip, a mono/pre-migration firefly with no [technique] at all — gets no new
 * option (gate-25): a firefly this doesn't apply to looks exactly as it did before this task.
 *
 * Pure and `internal` so [AudioCarrierViewOptionsTest] can drive it directly with plain JVM
 * values, no Compose/Robolectric needed — the same split every other caption/readout builder in
 * this package already follows ([CarrierInsightViews.kt]).
 */
internal fun audioCarrierViewOptions(technique: String?, numChannels: Int): List<AudioCarrierView> {
    val views = mutableListOf(AudioCarrierView.WAVEFORM, AudioCarrierView.SPECTROGRAM)
    if (technique == "SPECTROGRAM_LSB" && numChannels == 1) views += AudioCarrierView.DIFFERENCE
    if (technique == "PHASE_INVERSION" && numChannels == 2) views += AudioCarrierView.POLARITY
    return views
}

/**
 * v5 addition (design-v5.md §3/§4) — the "difference"/"polarity" toggle's own lightweight
 * loading state. Both insights are precomputed off the composition thread alongside the
 * spectrogram ([FireflyCarrierBlock]'s `LaunchedEffect`), but that precompute can still be
 * mid-flight the instant the user taps over to either option — the toggle itself only waits on
 * `spectrogramImage` being ready, not on these two (see [FireflyAudioCarrier]'s own KDoc). One
 * lowercase status word in the jar's voice, the same idiom every other in-progress state in this
 * app already uses ("catching"/"peeking"/"analyzing"/"listening" — `AudioStegoScreen.kt`,
 * `ImageStegoScreen.kt`, `AcousticModemScreen.kt`), not a spinner and not a percentage.
 */
@Composable
private fun CarrierInsightWorking() {
    Text(
        text = "working this one out",
        style = JarType.Footer,
        color = JarWatchingDim,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    )
}

/**
 * Waveform/spectrogram/difference/polarity + play/stop control (gate-18, gate-19, v5 addition
 * gate-24/25) — this app's first Canvas waveform, plus Stage D/3's spectrogram toggle, plus
 * (this task, V5-7) the two honest carrier views design-v5.md §3/§4 added:
 * [dev.herakles.nightjar.modules.fireflyjar.StegoDifferenceView] for
 * [dev.herakles.nightjar.AudioStegoTechnique.SPECTROGRAM_LSB] and
 * [dev.herakles.nightjar.modules.fireflyjar.StereoPolarityView] for
 * [dev.herakles.nightjar.AudioStegoTechnique.PHASE_INVERSION]. [availableViews]
 * ([audioCarrierViewOptions]) is the option set the toggle actually offers; [carrierView] is
 * which one is currently selected, owned one level up in [FireflyCarrierBlock] (keyed on the
 * firefly's id, same as every other piece of this block's state) so it resets whenever a
 * different firefly is opened.
 *
 * [spectrogramImage] is null until [FireflyCarrierBlock]'s `LaunchedEffect` finishes computing
 * it — the whole toggle row and [audioSpectrogramCaption] only render once it isn't, same
 * "toggle absent until ready" rule the IMAGE case's [FireflyBitPlaneToggle] already follows.
 * [differenceInsight]/[polarity] follow a different rule once the toggle itself is showing:
 * they're each gated by their own `Ready` flag rather than by nullability alone (`differenceInsight
 * == null` after `differenceReady` is gate-24's genuine "cover unmatched" withheld case, not
 * "still computing" — collapsing the two would either flash a false withheld message or drop
 * the honest one), rendering [CarrierInsightWorking] until then. Playback is unaffected by which
 * view is showing: the play/stop control and [wav]/[peaks] stay the source of truth for audio
 * either way; every carrier view here is read-only, never a second player.
 *
 * F-04 fix: this is "the composable that owns the player" in the sense that matters -- it holds
 * [isPlaying] and is the only place that calls [player]'s play/stop, even though the instance
 * itself is constructed one level up in [FireflyCarrierBlock]. [LifecycleEventEffect] stops
 * playback on `ON_STOP` (backgrounding the app) so a caught clip doesn't keep playing through the
 * speaker after the user leaves -- before this fix, nothing did (the composable stays alive
 * across backgrounding, so [FireflyCarrierBlock]'s own `DisposableEffect.onDispose` never fired).
 */
@Composable
private fun FireflyAudioCarrier(
    wav: WavFile.ParsedWav,
    peaks: FloatArray,
    spectrogramImage: ImageBitmap?,
    carrierView: AudioCarrierView,
    onSelectView: (AudioCarrierView) -> Unit,
    availableViews: List<AudioCarrierView>,
    differenceInsight: StegoDifferenceInsight?,
    differenceReady: Boolean,
    polarity: StereoPolarity?,
    polarityReady: Boolean,
    technique: String?,
    accent: Color,
    player: FireflyPlayer,
) {
    var isPlaying by remember { mutableStateOf(false) }
    val clock = rememberFireflyClock()

    // FireflyPlayer.kt has no completion callback (MODE_STATIC AudioTrack offers none), so this
    // polls progressFraction() once a frame while playing and falls back to "play" on its own the
    // moment a clip finishes -- the user shouldn't have to tap "stop" on a track that already
    // ended. Same withFrameNanos loop shape as rememberStandaloneFireflyClock (FireflyGlyphs.kt).
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            withFrameNanos {}
            if (player.progressFraction() >= 1f) isPlaying = false
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (isPlaying) {
            player.stop()
            isPlaying = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (carrierView) {
            AudioCarrierView.SPECTROGRAM -> if (spectrogramImage != null) {
                FireflySpectrogramCanvas(
                    image = spectrogramImage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                )
            } else {
                // Unreachable in practice -- see this function's KDoc -- but present anyway,
                // same "unreachable-but-present" discipline `AudioStegoCarrier.kt`'s own
                // `DecodeFailure` handling documents: fall back to the waveform rather than
                // rendering nothing.
                FireflyWaveform(
                    peaks = peaks,
                    isPlaying = isPlaying,
                    player = player,
                    clock = clock,
                    accent = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                )
            }
            AudioCarrierView.DIFFERENCE -> if (differenceReady) {
                StegoDifferenceView(insight = differenceInsight, accent = accent, modifier = Modifier.fillMaxWidth())
            } else {
                CarrierInsightWorking()
            }
            AudioCarrierView.POLARITY -> if (polarityReady && polarity != null) {
                StereoPolarityView(polarity = polarity, accent = accent, modifier = Modifier.fillMaxWidth())
            } else {
                CarrierInsightWorking()
            }
            AudioCarrierView.WAVEFORM -> FireflyWaveform(
                peaks = peaks,
                isPlaying = isPlaying,
                player = player,
                clock = clock,
                accent = accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )
        }
        if (spectrogramImage != null) {
            FireflyAudioViewToggle(
                current = carrierView,
                available = availableViews,
                accent = accent,
                onSelect = onSelectView,
            )
            if (carrierView == AudioCarrierView.SPECTROGRAM) {
                val caption = audioSpectrogramCaption(technique)
                Text(
                    text = caption.text,
                    style = JarType.Footer,
                    color = if (caption.genuinelyVisible) JarTextTertiary else JarWatchingDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        FireflyPlayButton(
            playing = isPlaying,
            accent = accent,
            onClick = {
                if (isPlaying) {
                    player.stop()
                    isPlaying = false
                } else {
                    player.play(wav.samples, wav.numChannels)
                    isPlaying = player.isPlaying
                }
            },
        )
    }
}

/**
 * Stage D/3 (gate-19), extended v5 (design-v5.md §5, gate-24/25) — the carrier-view switch under
 * an AUDIO carrier, generalized from its original two-label "waveform"/"spectrogram" pair to up
 * to four: [available] is [audioCarrierViewOptions]'s own output, so "difference"/"polarity"
 * only ever appear for the one technique + channel-count combination each is honest for
 * (gate-25: MFSK, mono, and pre-migration fireflies never see a new label here). Same
 * selected/unselected grammar as [FireflyBitPlaneToggle]: the selected label takes the firefly's
 * own [accent], an unselected one drops to [JarTextTertiary] — still not a new control shape,
 * just more of the same one.
 */
@Composable
private fun FireflyAudioViewToggle(
    current: AudioCarrierView,
    available: List<AudioCarrierView>,
    accent: Color,
    onSelect: (AudioCarrierView) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        available.forEach { view ->
            Text(
                text = view.label,
                style = JarType.Footer,
                color = if (view == current) accent else JarTextTertiary,
                modifier = Modifier.clickable(onClick = { onSelect(view) }),
            )
        }
    }
}

/**
 * Stage D/3 (gate-19) — draws a precomputed spectrogram [image] ([spectrogramImageBitmap]) at
 * whatever size the layout gives it. No clock read here (unlike [FireflyWaveform]): the
 * spectrogram isn't synced to playback position, so this draws once per [image] change instead
 * of repainting every frame.
 */
@Composable
private fun FireflySpectrogramCanvas(image: ImageBitmap, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawImage(
            image = image,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
    }
}

/**
 * Stage D/3 (gate-19) — turns a pure [SpectrogramData] into an [accent]-tinted [ImageBitmap]:
 * one pixel per (time column, frequency bin), alpha carrying normalized log-magnitude (min..max
 * across the whole clip, the same per-clip auto-range a real spectrogram viewer uses since a
 * fixed dB window would either clip a loud clip or wash out a quiet one) and RGB fixed to
 * [accent] — the same "direction-colored glow" grammar [FireflyWaveform]'s bars and
 * [swarmThumbModifier]'s tile chrome already use, applied here as a raster instead of drawn
 * primitives. Frequency bin 0 (DC) renders at the BOTTOM row and the highest bin at the top —
 * "low freq at bottom" per this stage's brief — so row `height-1-bin` holds bin's value, not row
 * `bin`. Runs off the composition thread ([FireflyCarrierBlock]'s `Dispatchers.Default` hop),
 * same as [LsbBitPlane.ofBitmap] — a multi-hundred-column, 513-bin raster is real allocation
 * work, not something to redo on every recomposition.
 *
 * Deliberately NOT bin-downsampled: [AudioStegoTechnique.MFSK]'s honest "visible" claim
 * ([audioSpectrogramCaption]) depends on its 8-bin-wide tone band (bins 420-427 of 513) staying
 * distinguishable — averaging bins together to shrink the image could dilute that band into its
 * quiet neighbors and turn a true claim into a false one. One [ImageBitmap] pixel per bin, drawn
 * scaled by [FireflySpectrogramCanvas], costs one `drawImage` call regardless of resolution —
 * cheaper than downsampling would have bought.
 */
private fun spectrogramImageBitmap(data: SpectrogramData, accent: Color): ImageBitmap {
    val width = data.columns.size
    val height = data.binCount
    if (width <= 0 || height <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()

    var minDb = Double.POSITIVE_INFINITY
    var maxDb = Double.NEGATIVE_INFINITY
    for (column in data.columns) {
        for (value in column) {
            if (value < minDb) minDb = value
            if (value > maxDb) maxDb = value
        }
    }
    val range = (maxDb - minDb).coerceAtLeast(1.0)

    val accentArgb = accent.toArgb()
    val r = (accentArgb shr 16) and 0xFF
    val g = (accentArgb shr 8) and 0xFF
    val b = accentArgb and 0xFF

    val pixels = IntArray(width * height)
    for (x in 0 until width) {
        val column = data.columns[x]
        for (bin in 0 until height) {
            val row = height - 1 - bin // low freq (bin 0) at the bottom row
            val normalized = ((column[bin] - minDb) / range).coerceIn(0.0, 1.0)
            val alpha = (normalized * 255).roundToInt().coerceIn(0, 255)
            pixels[row * width + x] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Stage D/3 (gate-19) — the honesty labels this stage exists to ship. Branches on
 * [FireflyRecord.technique] (a plain `String?`, never on [Module] — architecture.md § 6 reserves
 * the per-[Module] `when` budget to [FireflyGlyphs.drawJarGlyph] and [catchFlowFor]; this is a
 * per-technique branch on a data field, the same axis [FireflyBitPlaneToggle]'s neighbor
 * `FireflyCarrierBlock` already draws on [kind] rather than [Module]).
 *
 * Four cases, matched against how `technique` is actually written (`AcousticModemScreen.kt`,
 * `AudioStegoScreen.kt`):
 * - `"MFSK"` — [AudioStegoTechnique.MFSK]'s eight tones (`AudioStegoCarrier.kt`'s
 *   `MFSK_BASE_BIN`=420..427, ~19.7 kHz) really do sit in a bright band a spectrogram shows.
 *   [SpectrogramTest] grounds the bin claim.
 * - `null` — the acoustic modem (Module 3) also writes AUDIO media with no `technique`; its own
 *   FSK tone grid (architecture.md § Acoustic Protocol) is equally genuinely visible here.
 * - `"SPECTROGRAM_LSB"` — **corrected again 2026-09 (design-v5.md §3.3): the shipped caption was
 *   false on the app's own bundled covers.** The payload is QIM on log-magnitude at
 *   `AudioStegoCarrier.kt`'s `QUANTIZATION_STEP`=0.12, and against full-scale NOISE covers
 *   [SpectrogramTest]'s cover-vs-stego measurement finds only a vanishingly small, scattered
 *   fraction of pixels crossing a meaningfully visible delta (4 of 205,200 cells, 0.002%) — not a
 *   coherent band the way MFSK's is. But both bundled covers (`AudioStegoSampleCovers.kt`) have
 *   real digital silence (SPOKEN_WORD's burst/gap envelope, SOFT_SYNTH's fade-in), and where the
 *   cover is silent QIM has nothing to nudge: `LOG_MAGNITUDE_FLOOR` creates fresh bins instead,
 *   and rounding back to 16-bit fills the gap with broadband noise -- clearly, coherently visible
 *   brightening, not sub-perceptual. [SpectrogramTest] now measures this directly against a real
 *   bundled cover, so the caption says both things honestly: most of the payload stays below a
 *   spectrogram's threshold, AND the silent-cover gaps genuinely show. A cover-vs-stego
 *   difference view is the honest follow-up, out of scope here (spec.md).
 *
 *   **The "under 1.8 db" figure is itself measured, not asserted** (independent-review
 *   follow-up): [SpectrogramTest] recomputes the real encoder's NUDGED-cell log-magnitude delta
 *   on the codec's own block-aligned basis, both covers, strengths 1-4, at each cover's max
 *   payload. Measured worst case: 1.6287 dB (SOFT_SYNTH, strength 3) — a hair over the
 *   theoretical 1.5·`QUANTIZATION_STEP` = 1.56 dB QIM bound because real PCM16 rounding
 *   (`ifft` + `roundToShort`) adds a small amount on top of the encoder's pure-math quantization
 *   step. 1.8 db is that measured ceiling plus a safety margin, not the original unverified
 *   figure.
 * - `"PHASE_INVERSION"` — **corrected 2026-08-05 per rev-t2's adversarial-lite HIGH finding.**
 *   The original caption here claimed a magnitude spectrogram can't show this technique's
 *   payload; that was backwards. [spectrogram]'s mono-mix (`(L+R)/channels`) is EXACTLY
 *   [AudioStegoCarrier]'s own decode step for this technique (its class KDoc: "sum it down to
 *   mono ... the identical original content phase-cancels out, leaving only the secondary
 *   signal audible") — summing cancels the cover and leaves only the small mixed-in payload
 *   offset, and because nothing else survives in the mix to compete with it, per-clip brightness
 *   normalization renders that offset near the clip's own ceiling. [SpectrogramTest] measured
 *   this directly on a real encoded carrier: worst-case normalized brightness 0.880 across the
 *   whole clip. So the mono view doesn't fail to show this payload — it's the one case here
 *   where mono-mixing itself is the exposure mechanism, which is this technique's actual
 *   weakness, not a spectrogram limitation.
 *
 * The `else` branch is unreachable today (every AUDIO firefly's `technique` is one of the four
 * cases above) and present anyway, same "unreachable-but-present" discipline
 * `AudioStegoCarrier.kt`'s own `DecodeFailure` handling already documents for its unreachable
 * cases.
 */
private data class AudioSpectrogramCaption(val text: String, val genuinelyVisible: Boolean)

private fun audioSpectrogramCaption(technique: String?): AudioSpectrogramCaption = when (technique) {
    "MFSK" -> AudioSpectrogramCaption(
        text = "eight tones sit in a bright band near 19.7khz. that's the payload, visible right here.",
        genuinelyVisible = true,
    )
    null -> AudioSpectrogramCaption(
        text = "this is the acoustic modem's own fsk tone grid, drawing its symbols directly. visible right here.",
        genuinelyVisible = true,
    )
    "SPECTROGRAM_LSB" -> AudioSpectrogramCaption(
        text = "most of the payload is nudges under 1.8 db a spectrogram can't show. where the cover " +
            "went silent, the codec had to add faint sound -- that part can show here.",
        genuinelyVisible = false,
    )
    "PHASE_INVERSION" -> AudioSpectrogramCaption(
        text = "mixing to mono is this technique's own decode step. it cancels the cover and leaves the hidden payload exposed, right here.",
        genuinelyVisible = true,
    )
    else -> AudioSpectrogramCaption(text = "", genuinelyVisible = false)
}

/**
 * Peak-per-column bar chart. [clock] is read only to keep this draw scope repainting every frame
 * while [isPlaying] — a repaint tick, never a position source (`FireflyPlayer.kt`'s own contract)
 * — via the same draw-phase state read [FireflyDot]/[JarGlyph] already use for the breathing
 * animation elsewhere on this screen. [peaks] itself is precomputed once per firefly by
 * [waveformPeaks], not recomputed here every frame.
 */
@Composable
private fun FireflyWaveform(
    peaks: FloatArray,
    isPlaying: Boolean,
    player: FireflyPlayer,
    clock: State<Float>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        clock.value // repaint tick only -- see this function's KDoc

        if (peaks.isEmpty()) return@Canvas
        val progress = if (isPlaying) player.progressFraction() else 0f
        val columnWidth = size.width / peaks.size
        val midY = size.height / 2f
        val playedUpTo = size.width * progress

        peaks.forEachIndexed { index, peak ->
            val x = index * columnWidth + columnWidth / 2f
            val barHeight = (size.height * 0.9f) * peak.coerceAtLeast(0.05f)
            drawLine(
                color = if (x <= playedUpTo) accent else accent.copy(alpha = 0.28f),
                start = Offset(x, midY - barHeight / 2f),
                end = Offset(x, midY + barHeight / 2f),
                strokeWidth = (columnWidth * 0.6f).coerceAtLeast(1f),
                cap = StrokeCap.Round,
            )
        }

        if (isPlaying) {
            drawLine(
                color = accent,
                start = Offset(playedUpTo, 0f),
                end = Offset(playedUpTo, size.height),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }
}

/**
 * The carrier's play/stop pill — same accent-tinted fill/border/[JarType.ButtonLabel] grammar
 * AcousticModemScreen.kt's jar-mode "send"/"stop" buttons already use, just without their
 * gradient (this control isn't the screen's one primary action).
 */
@Composable
private fun FireflyPlayButton(playing: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(width = 1.dp, color = accent.copy(alpha = 0.22f), shape = RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = if (playing) "stop" else "play", style = JarType.ButtonLabel, color = accent)
    }
}

private const val WAVEFORM_COLUMNS = 120

/**
 * Loudest (max-abs) sample per column, [channels]-interleaved PCM16 collapsed to a single peak
 * per frame first, normalized to 0f..1f. Computed once per firefly ([FireflyCarrierBlock]'s
 * `LaunchedEffect`), not per Canvas frame — redoing this over up to ~1M samples 60x/second while
 * a clip plays would be real, avoidable cost.
 */
private fun waveformPeaks(samples: ShortArray, channels: Int, columns: Int): FloatArray {
    val safeChannels = channels.coerceAtLeast(1)
    val frameCount = samples.size / safeChannels
    if (frameCount <= 0 || columns <= 0) return FloatArray(0)
    val framesPerColumn = frameCount.toFloat() / columns
    return FloatArray(columns) { col ->
        val start = (col * framesPerColumn).toInt().coerceIn(0, frameCount - 1)
        val end = ((col + 1) * framesPerColumn).toInt().coerceIn(start + 1, frameCount)
        var peak = 0
        for (frame in start until end) {
            for (ch in 0 until safeChannels) {
                val value = abs(samples[frame * safeChannels + ch].toInt())
                if (value > peak) peak = value
            }
        }
        (peak / 32768f).coerceIn(0f, 1f)
    }
}

// --- Previews: JarDetailContent is pure, so these need no FireflyDao/CovertCarrier fake. One
// per jar variant (DESIGN_SPEC.md §7 item 7 — the four jars deliberately differ). ---

private fun previewFirefly(direction: String, preview: String, bytes: Int, id: Long = 1L): FireflyRecord =
    FireflyRecord(
        id = id,
        moduleId = Module.ACOUSTIC_MODEM.name,
        direction = direction,
        timestampMillis = System.currentTimeMillis(),
        payloadSizeBytes = bytes,
        technique = null,
        payloadPreview = preview,
    )

@Composable
private fun PreviewModuleFlowPlaceholder(text: String) {
    Text(text = text, style = JarType.TileCaption, color = JarTextTertiary)
}

@Preview(name = "Singing jar (acoustic modem)", showBackground = true, backgroundColor = 0xFF161229)
@Composable
private fun PreviewJarDetailSinging() {
    JarDetailContent(
        module = Module.ACOUSTIC_MODEM,
        fireflies = listOf(
            previewFirefly("CREATED", "wet-snacks-design", 18, id = 1),
            previewFirefly("RECEIVED", "the ravens have landed", 22, id = 2),
            previewFirefly("CREATED", "hello", 5, id = 3),
        ),
        selectedFirefly = null,
        onSelectFirefly = {},
        onDismissDetail = {},
        onBack = {},
        moduleFlow = { PreviewModuleFlowPlaceholder("catch a firefly · look for fireflies") },
    )
}

@Preview(name = "Framed jar (image steganography)", showBackground = true, backgroundColor = 0xFF161229)
@Composable
private fun PreviewJarDetailFramed() {
    JarDetailContent(
        module = Module.IMAGE_STEGANOGRAPHY,
        fireflies = listOf(previewFirefly("RECEIVED", "the ravens have landed", 22, id = 1)),
        selectedFirefly = null,
        onSelectFirefly = {},
        onDismissDetail = {},
        onBack = {},
        moduleFlow = { PreviewModuleFlowPlaceholder("catch a firefly · look for fireflies · check for hidden data") },
    )
}

@Preview(name = "Humming jar (audio steganography, empty)", showBackground = true, backgroundColor = 0xFF161229)
@Composable
private fun PreviewJarDetailHumming() {
    JarDetailContent(
        module = Module.AUDIO_STEGANOGRAPHY,
        fireflies = emptyList(),
        selectedFirefly = null,
        onSelectFirefly = {},
        onDismissDetail = {},
        onBack = {},
        moduleFlow = { PreviewModuleFlowPlaceholder("catch a firefly · look for fireflies") },
    )
}

@Preview(name = "Watching jar (detector)", showBackground = true, backgroundColor = 0xFF161229)
@Composable
private fun PreviewJarDetailWatching() {
    JarDetailContent(
        module = Module.DETECTOR,
        fireflies = emptyList(),
        selectedFirefly = null,
        onSelectFirefly = {},
        onDismissDetail = {},
        onBack = {},
        moduleFlow = { PreviewModuleFlowPlaceholder("watch") },
    )
}

@Preview(name = "Firefly detail popup", showBackground = true, backgroundColor = 0xFF161229)
@Composable
private fun PreviewFireflyDetail() {
    JarDetailContent(
        module = Module.ACOUSTIC_MODEM,
        fireflies = emptyList(),
        selectedFirefly = previewFirefly("CREATED", "wet-snacks-design", 18),
        onSelectFirefly = {},
        onDismissDetail = {},
        onBack = {},
        moduleFlow = {},
    )
}
