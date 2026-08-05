package dev.herakles.nightjar.modules.fireflyjar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.LsbBitPlane
import dev.herakles.nightjar.SpectrogramData
import dev.herakles.nightjar.WavFile
import dev.herakles.nightjar.spectrogram
import dev.herakles.nightjar.picker.JarRole
import dev.herakles.nightjar.picker.Module
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
    var selectedFirefly by remember(module) { mutableStateOf<FireflyRecord?>(null) }

    JarDetailContent(
        module = module,
        fireflies = fireflies,
        selectedFirefly = selectedFirefly,
        onSelectFirefly = { selectedFirefly = it },
        onDismissDetail = { selectedFirefly = null },
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
    // Default keeps every existing @Preview call site (none of which attach media) compiling
    // unchanged -- same "pure/previewable" reasoning this composable's own KDoc already states
    // for [moduleFlow].
    loadMedia: suspend (String) -> ByteArray? = { null },
) {
    JarNightSky(modifier = Modifier.fillMaxSize()) {
        if (selectedFirefly != null) {
            FireflyDetailContent(
                module = module,
                firefly = selectedFirefly,
                onBack = onDismissDetail,
                loadMedia = loadMedia,
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
                        FireflySwarmSection(fireflies = fireflies, onSelect = onSelectFirefly, loadMedia = loadMedia)
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
                    FireflySwarmTile(record = firefly, onClick = { onSelect(firefly) }, loadMedia = loadMedia)
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
 */
@Composable
private fun FireflyDot(record: FireflyRecord, onClick: () -> Unit) {
    val color = if (record.direction == "CREATED") FireflyCreated else FireflyReceived
    val clock = rememberFireflyClock()
    Canvas(
        modifier = Modifier
            .size(28.dp)
            .clickable(onClick = onClick),
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
@Composable
private fun FireflySwarmTile(record: FireflyRecord, onClick: () -> Unit, loadMedia: suspend (String) -> ByteArray?) {
    val kind = record.carrierKind
    if (kind == null) {
        FireflyDot(record = record, onClick = onClick)
        return
    }
    val color = if (record.direction == "CREATED") FireflyCreated else FireflyReceived
    when (val thumbnail = rememberSwarmThumbnail(record = record, kind = kind, loadMedia = loadMedia)) {
        null, SwarmThumbnail.Failed -> FireflyDot(record = record, onClick = onClick)
        is SwarmThumbnail.Image -> Box(
            modifier = swarmThumbModifier(color).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = thumbnail.bitmap.asImageBitmap(),
                contentDescription = "the image this firefly hid inside",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        is SwarmThumbnail.Audio -> Box(
            modifier = swarmThumbModifier(color).clickable(onClick = onClick),
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
            MetaCard(label = "size", value = fireflyByteLabel(firefly.payloadSizeBytes))
            MetaCard(label = "channel", value = module.jarChannel)
            MetaCard(
                label = "direction",
                value = if (caught) "caught" else "spotted",
                valueColor = accent,
            )
        }
    }
}

/** One of the three mini-cards under a firefly's message (DESIGN_SPEC.md §5 1c). */
@Composable
private fun MetaCard(label: String, value: String, valueColor: Color = JarTextPrimary) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(JarCardFill)
            .border(1.dp, JarCardBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = label, style = JarType.MetaLabel, color = JarTextTertiary)
        Text(text = value, style = JarType.MetaValue, color = valueColor)
    }
}

private fun formatFireflyTime(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMillis))

private fun fireflyByteLabel(bytes: Int): String = if (bytes == 1) "1 byte" else "$bytes bytes"

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
 * brackets what a magnitude spectrogram genuinely can and can't reveal per
 * [FireflyRecord.technique], since two of the three [dev.herakles.nightjar.AudioStegoTechnique]
 * cases hide their payload in a domain (sub-perceptual log-magnitude QIM, stereo polarity) no
 * magnitude spectrogram can show — see that function's KDoc for the four cases.
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
    // Null until ready; `showSpectrogram` only ever has something to show once it is.
    var spectrogramImage by remember(firefly.id) { mutableStateOf<ImageBitmap?>(null) }
    var showSpectrogram by remember(firefly.id) { mutableStateOf(false) }
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
                }
            }
            else -> failed = true
        }
    }

    val player = remember(firefly.id) { FireflyPlayer() }
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
                    }
                }
                currentWav != null && currentPeaks != null -> FireflyAudioCarrier(
                    wav = currentWav,
                    peaks = currentPeaks,
                    spectrogramImage = spectrogramImage,
                    showSpectrogram = showSpectrogram,
                    onToggleSpectrogram = { showSpectrogram = it },
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
 * Waveform/spectrogram + play/stop control (gate-18, gate-19) — this app's first Canvas
 * waveform, plus Stage D/3's spectrogram toggle alongside it. The waveform bars use [accent] at
 * two alphas (played vs. not-yet-played) plus a drawn playhead line, the same "dim track, bright
 * fill" grammar the acoustic modem's jar-mode glow-strength meter already established
 * (AcousticModemScreen.kt's `JarListeningCard`) rather than a new visual language. The play/stop
 * pill reuses [JarType.ButtonLabel], the same style "send"/"stop"/"watch" already use there.
 *
 * [spectrogramImage] is null until [FireflyCarrierBlock]'s `LaunchedEffect` finishes computing
 * it — the "waveform" / "spectrogram" [FireflyAudioViewToggle] and [audioSpectrogramCaption] only
 * render once it isn't, same "toggle absent until ready" rule the IMAGE case's
 * [FireflyBitPlaneToggle] already follows. Playback is unaffected by which view is showing: the
 * play/stop control and [wav]/[peaks] stay the source of truth for audio either way, the
 * spectrogram is a read-only visualization, not a second player.
 */
@Composable
private fun FireflyAudioCarrier(
    wav: WavFile.ParsedWav,
    peaks: FloatArray,
    spectrogramImage: ImageBitmap?,
    showSpectrogram: Boolean,
    onToggleSpectrogram: (Boolean) -> Unit,
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showSpectrogram && spectrogramImage != null) {
            FireflySpectrogramCanvas(
                image = spectrogramImage,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )
        } else {
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
        if (spectrogramImage != null) {
            FireflyAudioViewToggle(
                showSpectrogram = showSpectrogram,
                accent = accent,
                onToggle = onToggleSpectrogram,
            )
            if (showSpectrogram) {
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
 * Stage D/3 (gate-19) — the "waveform" / "spectrogram" switch under an AUDIO carrier, mirroring
 * [FireflyBitPlaneToggle]'s exact grammar: two tappable [JarType.Footer] labels, selected takes
 * the firefly's own [accent], unselected drops to [JarTextTertiary] — not a new control shape
 * for what is still this app's one two-option carrier-view pick.
 */
@Composable
private fun FireflyAudioViewToggle(showSpectrogram: Boolean, accent: Color, onToggle: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "waveform",
            style = JarType.Footer,
            color = if (!showSpectrogram) accent else JarTextTertiary,
            modifier = Modifier.clickable(onClick = { onToggle(false) }),
        )
        Text(
            text = "spectrogram",
            style = JarType.Footer,
            color = if (showSpectrogram) accent else JarTextTertiary,
            modifier = Modifier.clickable(onClick = { onToggle(true) }),
        )
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
 * - `"SPECTROGRAM_LSB"` — the payload is QIM on log-magnitude at `AudioStegoCarrier.kt`'s
 *   `QUANTIZATION_STEP`=0.12, sub-perceptual by design. The spectrogram still renders (this
 *   function never suppresses the view), but the caption says plainly that the eye can't catch
 *   it — a cover-vs-stego difference view is the honest follow-up, out of scope here (spec.md).
 * - `"PHASE_INVERSION"` — the payload lives in stereo polarity between L/R, a domain
 *   [spectrogram]'s own mono-mix collapses before this function ever sees a column. A magnitude
 *   spectrogram structurally cannot show it — an L/R-polarity view is the honest follow-up, also
 *   out of scope here.
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
        text = "the payload is quantization on log-magnitude, delta 0.12, sub-perceptual. a spectrogram can't show it.",
        genuinelyVisible = false,
    )
    "PHASE_INVERSION" -> AudioSpectrogramCaption(
        text = "the payload is a stereo polarity trick between the two channels. a magnitude spectrogram can't show it.",
        genuinelyVisible = false,
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
