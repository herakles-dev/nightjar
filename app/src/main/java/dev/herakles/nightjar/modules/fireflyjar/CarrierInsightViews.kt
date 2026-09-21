package dev.herakles.nightjar.modules.fireflyjar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.DiffCell
import dev.herakles.nightjar.StegoDifferenceMap
import dev.herakles.nightjar.StereoPolarity
import dev.herakles.nightjar.ui.theme.JarTextPrimary
import dev.herakles.nightjar.ui.theme.JarTextSecondary
import dev.herakles.nightjar.ui.theme.JarTextTertiary
import dev.herakles.nightjar.ui.theme.JarType
import dev.herakles.nightjar.ui.theme.JarWatchingDim
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * v5 addition (design-v5.md §3.4/§4.2/§5, gate-24/25/26). The two "honest carrier view"
 * composables plus their pure caption/readout builders — [StegoDifferenceView] for
 * [dev.herakles.nightjar.AudioStegoTechnique.SPECTROGRAM_LSB] fireflies (drawn from
 * [StegoDifferenceMap], §3) and [StereoPolarityView] for
 * [dev.herakles.nightjar.AudioStegoTechnique.PHASE_INVERSION] fireflies (drawn from
 * [StereoPolarity], §4). Placed beside the existing bit-plane/spectrogram carrier views
 * (`JarDetailScreen.kt`'s `FireflyBitPlaneToggle`/`FireflySpectrogramCanvas`) as one more
 * "data visualization, not decoration" surface on the jar detail screen — the same allowance
 * that already licenses those two shipped views, confirmed here for these two as well
 * (`design/firefly-jar-identity.md` never bans data visualizations, and this jar surface has
 * shipped two of them since gate-19; nothing in that doc's reversal/allowance list conflicts
 * with adding a third and fourth of the same kind).
 *
 * Every caption/readout below is a plain function returning a lowercase [String] — no
 * exclamation points, no "Got it!" affirmations, matching every other jar caption in this
 * codebase ([dev.herakles.nightjar.modules.fireflyjar] callers, e.g. `JarDetailScreen.kt`'s
 * `audioSpectrogramCaption`) — precisely so each one is independently testable without Compose
 * (the honest-labels doctrine, design-v5.md C1: every claim a caption makes is backed by a
 * test). `CarrierInsightCaptionsTest.kt` is that test file.
 *
 * Both composables are wired into `JarDetailScreen.kt` (V5-7, design-v5.md §11) and render live
 * there, as two of the four `AudioCarrierView` picker options ([StegoDifferenceView] for
 * `AudioCarrierView.DIFFERENCE`, [StereoPolarityView] for `AudioCarrierView.POLARITY`, alongside
 * the existing spectrogram/waveform views). This file defines the two views and their pure
 * builders, and documents (in each composable's own KDoc) exactly what a caller needs to pass in.
 */

// ---------------------------------------------------------------------------------------------
// Cover-vs-stego difference view (design-v5.md §3)
// ---------------------------------------------------------------------------------------------

/**
 * Bundles a computed [StegoDifferenceMap] with the display label of the bundled cover
 * [dev.herakles.nightjar.matchCover] found it against — the two pieces [StegoDifferenceView] and
 * its caption/readout builders need. Deliberately does **not** carry the matched
 * [dev.herakles.nightjar.CoverMatch.cover] itself: design-v5.md §3.5's call site drops the
 * ~480 KB re-derived cover the instant the map is computed (`LaunchedEffect`, `Dispatchers
 * .Default`), keeping only "the reduced map" — plus this cheap label — in Compose state.
 */
data class StegoDifferenceInsight(val map: StegoDifferenceMap, val coverLabel: String)

/**
 * design-v5.md §3.4's fallback line. Shown by [StegoDifferenceView] whenever
 * [dev.herakles.nightjar.matchCover] couldn't find the firefly's cover (returned `null`), so
 * there is nothing honest to subtract (INV-8: this view never approximates — it withholds with a
 * stated reason instead of guessing).
 */
const val STEGO_DIFFERENCE_WITHHELD_CAPTION: String =
    "can't rebuild this firefly's original cover anymore, so there's nothing honest to subtract."

/**
 * The cover-vs-stego difference view for a [dev.herakles.nightjar.AudioStegoTechnique
 * .SPECTROGRAM_LSB] firefly (design-v5.md §3.4). [insight] is `null` exactly when
 * [dev.herakles.nightjar.matchCover] withheld — this composable renders
 * [STEGO_DIFFERENCE_WITHHELD_CAPTION] in that case and nothing else, never a guessed or partial
 * picture (INV-8). When [insight] is present, this draws:
 * - a 96dp-tall cell grid (design-v5.md §3.4): x is codec frames, one column of blank context
 *   before the changed range and two after it (frames [StegoDifferenceMap] never touched, so
 *   they are provably [DiffCell.UNCHANGED] with no extra data needed to draw them); y is bins
 *   0 until [StegoDifferenceMap.binCount] with bin 0 at the bottom, the same "low frequency at
 *   the bottom" convention `JarDetailScreen.kt`'s `spectrogramImageBitmap` already uses.
 *   [DiffCell.NUDGED] cells render in [accent] at `alpha = min(1, |Δ| / 0.18)` — 0.18 nats
 *   (1.5·QUANTIZATION_STEP) is QIM's own theoretical nudge ceiling
 *   ([StegoDifferenceMap.maxNudgeNats]'s own KDoc bound, not a codec constant re-duplicated
 *   here), so a strength-4 "created" cell elsewhere on the grid can never wash a nudge's alpha
 *   out. [DiffCell.CREATED] cells render solid [JarTextPrimary] (cream) — a distinct class from
 *   NUDGED, never blended with it. [DiffCell.UNCHANGED] cells render nothing (transparent).
 * - a [JarType.Footer] readout line ([stegoDifferenceReadout]).
 * - the honesty caption ([stegoDifferenceCaption]).
 *
 * Callers (the later wiring task, design-v5.md §5/§11 V5-7) pass [insight] from a `LaunchedEffect`
 * that first calls `matchCover(samples, AudioSampleCover.entries.map { it.label to { synthesize
 * SampleCover(it) } })`, and only on a non-null result calls `stegoDifference(match.cover, samples)`
 * to build the [StegoDifferenceMap] — the same "compute once off the composition thread, precompute
 * alongside the other carrier data" discipline `FireflyCarrierBlock`'s `bitPlaneBitmap`/
 * `spectrogramImage` already establish. [accent] is the firefly's own accent color, the same one
 * every other carrier view in this file/`JarDetailScreen.kt` receives.
 */
@Composable
fun StegoDifferenceView(insight: StegoDifferenceInsight?, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (insight == null) {
            Text(
                text = STEGO_DIFFERENCE_WITHHELD_CAPTION,
                style = JarType.Footer,
                color = JarWatchingDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    // gate-38 (v6): this canvas had no semantics at all -- TalkBack skipped it
                    // entirely, landing on the withheld/readout/caption Text nodes around it with
                    // no idea a grid sat between them. contentDescription built from the same
                    // StegoDifferenceMap drawStegoDifferenceCells paints, never a new claim.
                    .semantics(mergeDescendants = true) {
                        contentDescription = stegoDifferenceContentDescription(insight.map)
                    },
            ) {
                drawStegoDifferenceCells(insight.map, accent)
            }
            Text(
                text = stegoDifferenceReadout(insight.map, insight.coverLabel),
                style = JarType.Footer,
                color = JarTextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stegoDifferenceCaption(insight.map),
                style = JarType.Footer,
                color = JarTextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 1.5·QUANTIZATION_STEP = 0.18 nats — [StegoDifferenceMap.maxNudgeNats]'s own documented QIM
 *  ceiling, reused (not a codec constant re-duplicated from `AudioStegoCarrier`) as the fixed
 *  alpha scale for [DiffCell.NUDGED] cells, per design-v5.md §3.4: "the scale caps at the largest
 *  possible nudge, so 61 dB 'created' cells can't wash the nudges out." */
private const val NUDGE_ALPHA_SCALE_NATS = 0.18f

private fun DrawScope.drawStegoDifferenceCells(map: StegoDifferenceMap, accent: Color) {
    if (map.firstChangedFrame == -1 || map.kinds.isEmpty()) return // cover matched exactly -- nothing to draw
    val rows = map.kinds.size
    val paddedColumns = rows + 3 // one frame of blank context before the range, two after (§3.4)
    val columnWidth = size.width / paddedColumns
    val rowHeight = size.height / map.binCount
    for (frameIndex in 0 until rows) {
        val x = (frameIndex + 1) * columnWidth // +1 leaves the leading context column blank
        val kindRow = map.kinds[frameIndex]
        val deltaRow = map.deltaNats[frameIndex]
        for (bin in 0 until map.binCount) {
            val color = when (kindRow[bin]) {
                DiffCell.NUDGED -> accent.copy(alpha = (abs(deltaRow[bin]) / NUDGE_ALPHA_SCALE_NATS).coerceIn(0f, 1f))
                DiffCell.CREATED -> JarTextPrimary
                DiffCell.UNCHANGED -> null
            } ?: continue
            val y = size.height - (bin + 1) * rowHeight // bin 0 at the bottom
            drawRect(color = color, topLeft = Offset(x, y), size = Size(columnWidth, rowHeight))
        }
    }
}

/** nats → dB for a log-*magnitude* (not power) delta: `dB = 20·log10(X2/X1) = (20/ln 10)·ln(X2/X1)`.
 *  Matches design-v5.md §3.2's own worked example exactly: `0.180 nats = 1.56 dB`. */
private const val NATS_TO_DB = 8.685889638065037

private fun shownDurationSeconds(map: StegoDifferenceMap): Double =
    if (map.firstChangedFrame == -1) 0.0 else (map.lastChangedFrame + 1) * map.frameSize / map.sampleRateHz.toDouble()

/**
 * design-v5.md §3.4's readout line, e.g. `"the original SPOKEN_WORD cover, subtracted · first
 * 0.2 s of 5.0 s · 0–6 khz · largest nudge 1.56 db"`. The `0–{hi} khz` span is always the full
 * analyzed band ([StegoDifferenceMap.binCount] bins from bin 0 — what the grid actually plots,
 * per [StegoDifferenceView]'s KDoc), never [StegoDifferenceMap.changedBinRange]: that range is
 * only calibrated exact for specific strength/payload combinations (`StegoDifferenceTest`'s own
 * KDoc), so this readout states what is drawn, not a claimed exact embedded-band boundary.
 * Locale.US throughout, matching every other formatted readout in this package
 * (`JarDetailScreen.kt`'s `fireflyMediaSizeLabel`/`fireflyCapacityPercent`).
 */
fun stegoDifferenceReadout(map: StegoDifferenceMap, coverLabel: String): String {
    val shownSeconds = shownDurationSeconds(map)
    val totalSeconds = map.totalFrames * map.frameSize / map.sampleRateHz.toDouble()
    val hiKhz = map.binCount * map.sampleRateHz / map.frameSize.toDouble() / 1000.0
    val maxNudgeDb = map.maxNudgeNats * NATS_TO_DB
    return String.format(
        Locale.US,
        "the original %s cover, subtracted · first %.1f s of %.1f s · 0–%.0f khz · largest nudge %.2f db",
        coverLabel,
        shownSeconds,
        totalSeconds,
        hiKhz,
        maxNudgeDb,
    )
}

/**
 * design-v5.md §3.4's honesty caption, built from a real [StegoDifferenceMap] so every sentence
 * states a measured fact about *this* firefly rather than a general claim:
 * - The exact changed-bin span (design-v5.md §3.2) is calibrated only for specific
 *   strength/payload combinations (`StegoDifferenceTest`'s own KDoc), so this caption never
 *   states a span — only the largest nudge actually measured on this map
 *   ([StegoDifferenceMap.maxNudgeNats]) and when the clip returns to being bit-for-bit the cover.
 * - [DiffCell.CREATED] cells are described as the codec having to **add** faint sound, never as a
 *   "nudge" — design-v5.md §3.3's finding is that a silent cover has nothing to nudge, so QIM's
 *   floor forces genuinely new spectral content into the gap. That sentence only appears when
 *   [StegoDifferenceMap.createdCells] is actually greater than zero for this map
 *   (`CarrierInsightCaptionsTest`'s branch coverage). The same sentence also says that added
 *   sound is audible on headphones as a faint crackle — the owner's gate-8 listening pass heard
 *   exactly that on the near-silent fade-in of the bundled `SOFT_SYNTH` cover, so this is a
 *   measured fact, not a guess, and it's gated on the identical `createdCells > 0` condition.
 * - The last sentence is unconditional: this view only exists because the app can re-derive its
 *   own bundled cover (design-v5.md §3.1) — someone holding just the stego clip has no cover to
 *   subtract at all.
 *
 * [StegoDifferenceMap.firstChangedFrame] of `-1` (cover and stego are bit-identical — never
 * expected from a real embed, since every codec write includes header/trailer framing, but not
 * excluded by this function's contract) gets its own honest line rather than a "never more than
 * 0.00 db" sentence that would technically be true but reads as a non-answer.
 */
fun stegoDifferenceCaption(map: StegoDifferenceMap): String {
    if (map.firstChangedFrame == -1 || map.kinds.isEmpty()) {
        return "this firefly's cover matches it exactly, so there's nothing to subtract."
    }

    val maxNudgeDb = map.maxNudgeNats * NATS_TO_DB
    val shownSeconds = shownDurationSeconds(map)
    val caption = StringBuilder(
        String.format(
            Locale.US,
            "every lit cell is a frequency bin the codec nudged, never more than %.2f db. you " +
                "can't hear that, and where the cover has sound a spectrogram can't show it. " +
                "after %.1f s the clip is bit-for-bit the cover.",
            maxNudgeDb,
            shownSeconds,
        ),
    )
    if (map.createdCells > 0) {
        caption.append(
            " where the cover was silent there was nothing to nudge, so it had to add faint " +
                "sound (the pale cells). those spots do show up in the spectrogram, and on " +
                "headphones you can hear it as a faint crackle.",
        )
    }
    caption.append(
        " this only works because the app made the cover. someone holding just this clip can't " +
            "subtract anything.",
    )
    return caption.toString()
}

/**
 * gate-38 (v6 addition, closes deferred follow-up #12) — [StegoDifferenceView]'s cell-grid canvas
 * had no text of its own for TalkBack to reach: a bare [androidx.compose.foundation.Canvas] with
 * no semantics is skipped entirely, not announced as blank, so a screen-reader user swiping
 * through this view landed on the readout/caption [androidx.compose.material3.Text]s around it
 * with no idea a grid sat between them. Built from the same [StegoDifferenceMap]
 * [drawStegoDifferenceCells] paints, never a second computation on the raw cells.
 *
 * "`changedFrames` of `totalFrames` frames changed, all in the embedded region" is true by
 * construction, not an estimate: [StegoDifferenceMap.kinds] only ever holds rows for the
 * [StegoDifferenceMap.firstChangedFrame]..[StegoDifferenceMap.lastChangedFrame] window (that
 * class's own KDoc; [drawStegoDifferenceCells]'s "one frame of blank context" comment), and every
 * frame outside that window was scanned by [dev.herakles.nightjar.stegoDifference] end to end and
 * found unchanged — so every row this function counts really is "in the embedded region", and
 * [StegoDifferenceMap.totalFrames] is the honest denominator, not just the analyzed window.
 * "Changed" means "the row has a lit ([DiffCell.NUDGED] or [DiffCell.CREATED]) cell in the drawn
 * grid" — the same thing a sighted viewer sees, never a sample-domain claim the canvas doesn't
 * paint. This states a count only, nothing about location or audibility beyond what
 * [stegoDifferenceCaption] already claims (v5/v6 honesty rules, gate-19/24/26/38).
 *
 * The `firstChangedFrame == -1` case (cover and stego bit-identical; [drawStegoDifferenceCells]
 * itself draws nothing) gets its own honest line, same reasoning [stegoDifferenceCaption] already
 * applies there.
 */
internal fun stegoDifferenceContentDescription(map: StegoDifferenceMap): String {
    if (map.firstChangedFrame == -1 || map.kinds.isEmpty()) {
        return "difference view: cover and stego are identical, nothing changed."
    }
    val changedFrames = map.kinds.count { row -> row.any { it != DiffCell.UNCHANGED } }
    return "difference view: $changedFrames of ${map.totalFrames} frames changed, all in the embedded region."
}

// ---------------------------------------------------------------------------------------------
// L/R polarity view (design-v5.md §4)
// ---------------------------------------------------------------------------------------------

/**
 * The L/R polarity view for a [dev.herakles.nightjar.AudioStegoTechnique.PHASE_INVERSION]
 * firefly (design-v5.md §4.2), built entirely from a precomputed [StereoPolarity] — unlike
 * [StegoDifferenceView], there is no withheld case here: [dev.herakles.nightjar.stereoPolarity]
 * always returns a result (its own KDoc — NaN-safe, never throws), so callers (the later wiring
 * task, design-v5.md §5/§11 V5-7) only need `technique == "PHASE_INVERSION" && numChannels == 2`
 * to gate offering this view at all, then pass whatever [dev.herakles.nightjar.stereoPolarity]
 * returned straight through. Draws, top to bottom:
 * - a 64dp 20ms L/R overlay: [StereoPolarity.zoomLeft] in [accent], [StereoPolarity.zoomRight] in
 *   [JarTextSecondary], both already peak-normalized to the same value by [StereoPolarity] itself
 *   so their relative amplitude (how closely they mirror) survives unchanged here.
 * - a legend (`left · right · 20 ms`).
 * - a 32dp signed residual strip: one bar per [StereoPolarity.residualWindowMean] window, scaled
 *   so the window with the largest `|mean|` reaches full half-height — this is the decode step
 *   made visible, the same signal [dev.herakles.nightjar.AudioStegDetector] and this clip's own
 *   embedded bits are built from.
 * - the magnification label ([residualMagnificationLabel]), when there is anything to magnify.
 * - the readout line ([stereoPolarityReadout]).
 * - the honesty caption ([stereoPolarityCaption]).
 */
@Composable
fun StereoPolarityView(polarity: StereoPolarity, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                // gate-38 (v6): the overlay canvas had no semantics -- TalkBack skipped it
                // entirely. Reuses the same correlation figure stereoPolarityReadout already
                // prints as visible text, so the graphic's spoken claim can never drift from it.
                .semantics(mergeDescendants = true) {
                    contentDescription = stereoPolarityOverlayContentDescription(polarity)
                },
        ) {
            drawZoomTrace(polarity.zoomLeft, accent)
            drawZoomTrace(polarity.zoomRight, JarTextSecondary)
        }
        PolarityLegend(accent)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                // gate-38 (v6): the residual-strip canvas had no semantics either -- a second,
                // distinct description (not a repeat of the overlay's) so TalkBack doesn't hear
                // the same sentence twice back to back.
                .semantics(mergeDescendants = true) {
                    contentDescription = stereoPolarityResidualContentDescription(polarity)
                },
        ) {
            drawResidualStrip(polarity.residualWindowMean, accent)
        }
        residualMagnificationLabel(polarity.residualWindowMean)?.let { label ->
            Text(text = label, style = JarType.Footer, color = JarTextTertiary)
        }
        Text(
            text = stereoPolarityReadout(polarity),
            style = JarType.Footer,
            color = JarTextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stereoPolarityCaption(polarity),
            style = JarType.Footer,
            color = JarTextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PolarityLegend(accent: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "left", style = JarType.Footer, color = accent)
        Text(text = "·", style = JarType.Footer, color = JarTextTertiary)
        Text(text = "right", style = JarType.Footer, color = JarTextSecondary)
        Text(text = "·", style = JarType.Footer, color = JarTextTertiary)
        Text(text = "20 ms", style = JarType.Footer, color = JarTextTertiary)
    }
}

private const val ZOOM_STROKE_WIDTH_PX = 2.5f

/** [samples] are already normalized to `[-1, 1]` by [dev.herakles.nightjar.stereoPolarity] —
 *  this only maps them onto the draw scope, never renormalizes. */
private fun DrawScope.drawZoomTrace(samples: FloatArray, color: Color) {
    if (samples.size < 2) return
    val path = Path()
    val stepX = size.width / (samples.size - 1)
    val midY = size.height / 2f
    samples.forEachIndexed { index, value ->
        val x = index * stepX
        val y = midY - value.coerceIn(-1f, 1f) * midY
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path = path, color = color, style = Stroke(width = ZOOM_STROKE_WIDTH_PX))
}

private fun DrawScope.drawResidualStrip(means: FloatArray, color: Color) {
    if (means.isEmpty()) return
    val maxAbs = means.maxOf { abs(it) }
    if (maxAbs <= 0f) return // nothing survives the sum -- the caption says so, this draws nothing
    val columnWidth = size.width / means.size
    val strokeWidth = max(columnWidth * 0.6f, 1f)
    val midY = size.height / 2f
    means.forEachIndexed { index, mean ->
        val x = index * columnWidth + columnWidth / 2f
        val barHeight = midY * (abs(mean) / maxAbs)
        val top = if (mean >= 0f) midY - barHeight else midY
        val bottom = if (mean >= 0f) midY else midY + barHeight
        drawLine(color = color, start = Offset(x, top), end = Offset(x, bottom), strokeWidth = strokeWidth)
    }
}

/** Full 16-bit signed range — the same reference the residual strip's display gain is quoted
 *  against, design-v5.md §4.2: `"magnified ×512 = 32767/64"` (measured: real phase-inversion
 *  stego's residual windows sit at exactly `±`64 LSB, [dev.herakles.nightjar
 *  .AudioStegoCarrier]'s `MIX_AMPLITUDE`, so `32767/64` rounds to exactly `512` — the design
 *  doc's own worked number, reproduced here from measurement rather than hardcoded). */
private const val PCM16_FULL_SCALE = 32767.0

/**
 * design-v5.md §4.2's residual-strip gain label, e.g. `"magnified ×512"`. `null` when
 * [residualWindowMean] has nothing to magnify (every window is exactly zero — the pure-inversion
 * control case, and [StereoPolarityTest]'s own `pureInvertedStereoMeasuresNegativeUnityCorrelation
 * WithAnAllZeroResidual`); [StereoPolarityView] omits the label entirely in that case rather than
 * printing a meaningless "×infinity".
 */
fun residualMagnificationLabel(residualWindowMean: FloatArray): String? {
    if (residualWindowMean.isEmpty()) return null
    val maxAbs = residualWindowMean.maxOf { abs(it) }
    if (maxAbs <= 0f) return null
    val gain = (PCM16_FULL_SCALE / maxAbs).roundToInt()
    return "magnified ×$gain"
}

/**
 * design-v5.md §4.2's readout line, e.g. `"channel correlation -0.9994 · the sum is 34.8 db
 * below the difference"`. [StereoPolarity.correlation] prints at 4 decimals (never 3) so a real
 * measured `-0.9994` (the worst case `StereoPolarityTest` records) never rounds to a false-looking
 * `-1.000`. The sum/difference phrasing follows [StereoPolarity.sumToDifferenceDb]'s own sign — it
 * reads strongly negative on genuine phase-inversion stego (the sum cancels, the difference
 * doesn't), so this states "below"; a non-negative value (e.g. a dual-mono control, where the
 * difference itself is the near-zero side) states "above" instead of silently reusing the wrong
 * word for a case this function's own contract does not exclude.
 */
fun stereoPolarityReadout(polarity: StereoPolarity): String {
    val correlationText = String.format(Locale.US, "%.4f", polarity.correlation)
    val magnitudeText = String.format(Locale.US, "%.1f", abs(polarity.sumToDifferenceDb))
    val direction = if (polarity.sumToDifferenceDb <= 0.0) "below" else "above"
    return "channel correlation $correlationText · the sum is $magnitudeText db $direction the difference"
}

/**
 * design-v5.md §4.2's honesty caption, with the anti-phase caveat the owner asked for placed
 * exactly where the view could otherwise read as proof: the 20ms overlay's mirror-image shape,
 * by itself, is also what a plain polarity-flipped export looks like (design-v5.md §2.4's
 * documented false-positive class; design-v5.md risk #7) — a genuinely benign anti-phase master
 * with nothing mixed in produces the identical mirror image. What the overlay alone cannot show,
 * and the residual strip can, is whether anything survives the sum.
 * - When [StereoPolarity.residualWindowMean] has a nonzero window, the caption states the steps
 *   left over are the hidden message, right after naming the caveat.
 * - When every window is exactly zero (the residual strip and [residualMagnificationLabel] both
 *   render/return nothing in this case too), the caption states plainly that nothing is hiding in
 *   the sum — this is [StereoPolarityTest]'s own pure-inverted control case in view form.
 */
fun stereoPolarityCaption(polarity: StereoPolarity): String {
    val residualIsSilent = polarity.residualWindowMean.all { it == 0f }
    return if (residualIsSilent) {
        "the right channel is the left one turned upside down. a plain polarity-flipped " +
            "recording looks the same as this. add them and everything cancels. nothing is " +
            "hiding in the sum."
    } else {
        "the right channel is the left one turned upside down. a plain polarity-flipped " +
            "recording can look exactly like that mirror image with nothing hidden in it. add " +
            "them together and the cover cancels out; the steps left over are the hidden " +
            "message, one every 10 ms."
    }
}

/**
 * gate-38 (v6 addition, closes deferred follow-up #12) — the L/R overlay canvas's spoken label:
 * the mirror-image shape plus the same real, measured correlation
 * [stereoPolarityReadout] already prints as visible text, at the identical 4-decimal precision
 * (that function's own KDoc: a real worst-case measured correlation of `-0.9994` must never round
 * to a false-looking `-1.000`). This never states the anti-phase caveat
 * [stereoPolarityCaption] carries — that sentence is a swipe away as its own [Text] node, not
 * duplicated here — only what this specific canvas draws: the two traces' shape and the number
 * behind it.
 */
internal fun stereoPolarityOverlayContentDescription(polarity: StereoPolarity): String {
    val correlationText = String.format(Locale.US, "%.4f", polarity.correlation)
    return "the left and right channels over a short window: the right one is the left one " +
        "turned upside down. channel correlation $correlationText."
}

/**
 * gate-38 (v6 addition, closes deferred follow-up #12) — the residual-strip canvas's spoken
 * label, a distinct sentence from [stereoPolarityOverlayContentDescription] (so TalkBack doesn't
 * read the same claim twice back to back) and a paraphrase, never an overclaim, of
 * [stereoPolarityCaption]'s own silent/nonsilent branch — same [StereoPolarity.residualWindowMean]
 * field, same `all { it == 0f }` condition, same "one every 10 ms" figure that caption already
 * states unconditionally for this app's fixed [dev.herakles.nightjar.NightjarAcoustics
 * .SAMPLE_RATE_HZ] clips.
 */
internal fun stereoPolarityResidualContentDescription(polarity: StereoPolarity): String {
    val residualIsSilent = polarity.residualWindowMean.all { it == 0f }
    return if (residualIsSilent) {
        "residual after adding left and right together: flat at zero. nothing survives the sum."
    } else {
        "residual after adding left and right together: a step roughly every 10 milliseconds. " +
            "that's the hidden message."
    }
}
