package dev.herakles.nightjar.modules.fireflyjar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.R
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.trail.TrailConstellation
import dev.herakles.nightjar.trail.TrailQuestLine
import dev.herakles.nightjar.trail.TrailState
import dev.herakles.nightjar.trail.TrailStateStore
import dev.herakles.nightjar.trail.TrailStep
import dev.herakles.nightjar.trail.TrailWelcomeCard
import dev.herakles.nightjar.trail.trailHighlight
import dev.herakles.nightjar.trail.trailShelfTargetModule
import dev.herakles.nightjar.trail.trailWordmarkHintRes
import dev.herakles.nightjar.ui.theme.FireflyCreated
import dev.herakles.nightjar.ui.theme.FireflyReceived
import dev.herakles.nightjar.ui.theme.JarTextPrimary
import dev.herakles.nightjar.ui.theme.JarTextSecondary
import dev.herakles.nightjar.ui.theme.JarTextTertiary
import dev.herakles.nightjar.ui.theme.JarTileBorderCreated
import dev.herakles.nightjar.ui.theme.JarTileBorderCreatedFaint
import dev.herakles.nightjar.ui.theme.JarTileBorderReceived
import dev.herakles.nightjar.ui.theme.JarTileBorderWatching
import dev.herakles.nightjar.ui.theme.JarTileFill
import dev.herakles.nightjar.ui.theme.JarTileFillDim
import dev.herakles.nightjar.ui.theme.JarType
import dev.herakles.nightjar.ui.theme.JarWatchingDim
import kotlinx.coroutines.launch

/**
 * The Firefly Jar shelf (design-refresh DESIGN_SPEC.md §5 screen "1a — Jar Shelf"), the app's
 * default root (`Screen.JarShelf` in `MainActivity.kt`). One tile per [Module.entries] — not
 * hardcoded — each showing that module's [Module.jarName], its live
 * [JarGlyph], and a live firefly count sourced from [FireflyDao.observeByModule]. Long-pressing
 * the wordmark reveals the technical picker (`Screen.Picker`) via [onRevealTechnicalMode]; tapping
 * a tile navigates to that module's `Screen.JarDetail`.
 */
@Composable
fun JarShelfScreen(
    repository: FireflyRepository,
    trailStore: TrailStateStore,
    onSelectModule: (Module) -> Unit,
    onRevealTechnicalMode: () -> Unit,
) {
    val fireflyCounts = Module.entries.associateWith { module ->
        val records by repository.observeByModule(module.name).collectAsState(initial = emptyList())
        records.size
    }
    // Physical, deduplicated media usage -- observeTotalMediaBytes()'s own
    // KDoc (FireflyLog.kt) covers why this is a DISTINCT-mediaPath sum, not a naive per-row one.
    // `initial = 0L` is safe post-fix: the DAO query now COALESCEs an empty table to 0 rather
    // than emitting null.
    val totalMediaBytes by repository.observeTotalMediaBytes().collectAsState(initial = 0L)
    val coroutineScope = rememberCoroutineScope()
    val trailState by trailStore.state.collectAsState()

    JarShelfContent(
        fireflyCounts = fireflyCounts,
        totalMediaBytes = totalMediaBytes,
        onSelectModule = onSelectModule,
        // The workshop step's target is the wordmark's
        // own long-press reveal, not a jar tile -- a successful long-press advances it, then
        // still performs the reveal itself, in that order.
        onRevealTechnicalMode = {
            if (trailState.currentStep == TrailStep.WORKSHOP) {
                trailStore.advance(TrailStep.WORKSHOP)
            }
            onRevealTechnicalMode()
        },
        // Clear-history now goes through FireflyRepository so files and rows
        // are deleted together -- see FireflyRepository.clearAll for the file-before-row ordering.
        onClearHistory = { coroutineScope.launch { repository.clearAll() } },
        trailState = trailState,
        onSkipTrail = { trailStore.skip() },
        // Welcome card `begin`, the
        // constellation's one-shot pulse acknowledgement, and the finale panel's `close`.
        onBeginTrail = { trailStore.markWelcomeSeen() },
        onCompletionAcknowledged = { trailStore.acknowledgeCompletion() },
        onCloseFinale = { trailStore.dismissFinale() },
    )
}

/**
 * Pure/previewable content: no [FireflyDao], no `Flow` collection — takes the count per module
 * already resolved, same stateful-root/pure-content split every other screen in this app uses
 * (see e.g. `DetectorScreen`'s `DetectorContent`). Design refresh: the flat gradient
 * background is now [JarNightSky] (starfield + distant fireflies), jar art is the live [JarGlyph]
 * render engine, and type/color come from [JarType] and the Cozy Pixel Night palette rather than
 * `MaterialTheme.typography` — this screen is under the disguise surface, not the technical one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JarShelfContent(
    fireflyCounts: Map<Module, Int>,
    // Default keeps the one existing @Preview call site compiling unchanged -- same reasoning
    // JarDetailContent's own `loadMedia` default already documents.
    totalMediaBytes: Long = 0L,
    onSelectModule: (Module) -> Unit,
    onRevealTechnicalMode: () -> Unit,
    onClearHistory: () -> Unit = {},
    // Null means "no trail progress to show" --
    // the default wordmark subtitle renders with no highlight and no skip link, same as before
    // this task, so the one existing @Preview call site keeps compiling unchanged.
    trailState: TrailState? = null,
    onSkipTrail: () -> Unit = {},
    // Defaults keep every existing
    // @Preview call site compiling unchanged, same "pure/previewable" reasoning this file's
    // other optional trail params already follow.
    onBeginTrail: () -> Unit = {},
    onCompletionAcknowledged: () -> Unit = {},
    onCloseFinale: () -> Unit = {},
) {
    var showClearConfirm by remember { mutableStateOf(false) }
    // The tile-glow/wordmark-hint/skip-link chrome below only starts once the welcome
    // card's own `begin` action has fired (TrailState.welcomeSeen) -- before that, this local
    // `trailStep` reads as null exactly like "no trail progress to show" already does, so every
    // one of those existing `trailStep != null` branches suppresses itself for free.
    val trailStarted = trailState?.welcomeSeen == true
    val trailStep = if (trailStarted) trailState?.currentStep else null
    val trailHighlightedModule = trailShelfTargetModule(trailStep)

    JarNightSky(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 24.dp, start = 20.dp, end = 20.dp, bottom = 0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = onRevealTechnicalMode)
                    // The workshop step's own target -- there's no jar tile left
                    // to point at once every creating jar/the meadow is done, so the long-press
                    // area itself glows instead.
                    .trailHighlight(
                        active = trailStep == TrailStep.WORKSHOP,
                        description = "hold to open workshop",
                    )
                    .padding(bottom = 12.dp),
            ) {
                Text(text = "night jar", style = JarType.Wordmark, color = FireflyCreated)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = trailStep?.let { stringResource(trailWordmarkHintRes(it)) } ?: "hold to open workshop",
                        style = JarType.WordmarkSubtitle,
                        color = JarTextTertiary,
                    )
                    // Skip: only while a trail step is active -- not shown once the trail
                    // is complete or already skipped (trailStep == null covers both).
                    if (trailStep != null) {
                        Text(
                            text = stringResource(R.string.trail_skip),
                            style = JarType.WordmarkSubtitle,
                            color = JarTextTertiary,
                            modifier = Modifier.clickable(onClick = onSkipTrail),
                        )
                    }
                }
                // The workshop step's own quest
                // line lives on the shelf, above its action (the long-press area above) -- this
                // sits just below the wordmark subtitle it already shares the same Column with.
                if (trailStep == TrailStep.WORKSHOP) {
                    TrailQuestLine(TrailStep.WORKSHOP, modifier = Modifier.padding(top = 4.dp))
                }
            }

            if (trailState != null) {
                TrailConstellation(
                    state = trailState,
                    onCompletionAcknowledged = onCompletionAcknowledged,
                    onCloseFinale = onCloseFinale,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                // "above the jars", in place --
                // a no-op composable while the card isn't due (trailWelcomeCardVisible).
                TrailWelcomeCard(
                    state = trailState,
                    onBegin = onBeginTrail,
                    onSkip = onSkipTrail,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Module.entries.forEach { module ->
                    JarTile(
                        module = module,
                        fireflyCount = fireflyCounts[module] ?: 0,
                        onClick = { onSelectModule(module) },
                        highlighted = module == trailHighlightedModule,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Usage readout beside "clear history", advisory-only
                // warmer copy above the threshold -- never a dialog, never gates catching
                // (retention stays user-managed, no automatic eviction).
                Text(
                    text = jarStorageUsageLabel(totalMediaBytes),
                    style = JarType.Footer,
                    color = if (jarStorageUsageIsWarning(totalMediaBytes)) FireflyCreated else JarWatchingDim,
                )
                Text(
                    text = "clear history",
                    style = JarType.Footer,
                    color = JarWatchingDim,
                    modifier = Modifier.clickable { showClearConfirm = true },
                )
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all firefly history?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    onClearHistory()
                }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun JarTile(module: Module, fireflyCount: Int, onClick: () -> Unit, highlighted: Boolean = false) {
    val tint = tileTint(module)
    val description = "${module.jarName}, ${fireflyCountLabel(module, fireflyCount)}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(tint.fill)
            .border(width = 1.dp, color = tint.border, shape = RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            // Glows this tile as the trail's current shelf-level target.
            // When active, trailHighlight's own clearAndSetSemantics supersedes the plain
            // `.semantics{}` call below entirely (skipped in that branch) so the two can't both
            // try to own this element's contentDescription -- see trail/TrailHighlight.kt's KDoc.
            .trailHighlight(active = highlighted, description = description)
            .then(
                if (highlighted) {
                    Modifier
                } else {
                    // A bare Row's title/caption Text children would otherwise read as two
                    // separate TalkBack stops -- same "no text a screen reader can read" reasoning
                    // JarDetailScreen's FireflyDot semantics block already documents, applied to
                    // the tile as a whole.
                    Modifier.semantics(mergeDescendants = true) { contentDescription = description }
                },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JarGlyph(
            module = module,
            fireflies = tileFireflies(module, fireflyCount),
            modifier = Modifier.size(width = 72.dp, height = 86.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = module.jarName, style = JarType.TileTitle, color = tint.titleColor)
            Text(text = fireflyCountLabel(module, fireflyCount), style = JarType.TileCaption, color = tint.captionColor)
        }
    }
}

private data class TileTint(val fill: Color, val border: Color, val titleColor: Color, val captionColor: Color)

/** DESIGN_SPEC.md §1's card/row tint table + §5 1a's shelf-tile description, one row per
 *  [Module]. The watching jar is the exception to the cream/tertiary text pairing every other
 *  tile uses — its dimmer secondary/watching-dim pair is what makes it read as asleep. */
private fun tileTint(module: Module): TileTint = when (module) {
    Module.ACOUSTIC_MODEM -> TileTint(JarTileFill, JarTileBorderCreated, JarTextPrimary, JarTextTertiary)
    Module.IMAGE_STEGANOGRAPHY -> TileTint(JarTileFill, JarTileBorderReceived, JarTextPrimary, JarTextTertiary)
    Module.AUDIO_STEGANOGRAPHY -> TileTint(JarTileFill, JarTileBorderCreatedFaint, JarTextPrimary, JarTextTertiary)
    Module.DETECTOR -> TileTint(JarTileFillDim, JarTileBorderWatching, JarTextSecondary, JarWatchingDim)
}

/** Live fireflies for a shelf tile, alternating gold/cyan per DESIGN_SPEC.md §5 1a's "2 gold + 1
 *  cyan" singing-jar mockup. The watching jar never holds fireflies — it only watches. */
private fun tileFireflies(module: Module, count: Int): List<FireflyVisual> {
    if (module == Module.DETECTOR) return emptyList()
    return List(count) { index ->
        FireflyVisual(
            id = module.ordinal * 10 + index,
            color = if (index % 2 == 0) FireflyCreated else FireflyReceived,
        )
    }
}

private fun fireflyCountLabel(module: Module, count: Int): String = when {
    // P4 (on-device review, honesty): "always listening" overclaimed -- DetectorController's
    // mic capture (modules/detector/DetectorScreen.kt) only starts from the watching jar's own
    // "watch" button tap (jarWatchFlow's onToggleWatch), never on opening the jar, and
    // LifecycleEventEffect(ON_STOP) stops it again the moment the app is backgrounded. "watch" is
    // the same verb the jar's own button already uses (DetectorContent), so this stays honest
    // without introducing new vocabulary.
    module == Module.DETECTOR -> "off until you tap watch"
    count == 0 -> "no fireflies yet"
    count == 1 -> "1 firefly"
    else -> "$count fireflies"
}

/**
 * Storage-usage threshold: above this, [jarStorageUsageLabel] switches
 * to warmer, advisory copy and [jarStorageUsageIsWarning] flips its color to [FireflyCreated].
 * 250 MB is not picked arbitrarily -- audio dominates usage at ~480 KB mono / ~960 KB stereo
 * per 5s clip (up to ~1.9 MB for a full 20s acoustic capture), so 250 MB is roughly 250-500
 * audio catches, comfortably past what a demo session produces but a real signal that the jar
 * has been used a lot. Advisory only -- crossing it never gates catching, shows a dialog, or
 * evicts anything (retention stays user-managed; nothing about that changes).
 */
internal const val STORAGE_WARNING_THRESHOLD_BYTES = 250L * 1_000_000L

/** True once [totalBytes] reaches [STORAGE_WARNING_THRESHOLD_BYTES] -- drives both
 *  [jarStorageUsageLabel]'s copy and the readout's accent color, from one shared boundary check
 *  so the two can't independently drift. */
internal fun jarStorageUsageIsWarning(totalBytes: Long): Boolean = totalBytes >= STORAGE_WARNING_THRESHOLD_BYTES

/**
 * The shelf's storage-usage line: "your jars are holding 42 MB" below
 * the threshold, "the jars are getting heavy — 280 MB" at or above it. Reuses
 * [fireflyMediaSizeLabel] (JarDetailScreen.kt)
 * rather than a second byte formatter, so the shelf's total and a single firefly's own capacity
 * line (`fireflyCapacityLine`) never disagree on what "42 MB" means.
 */
internal fun jarStorageUsageLabel(totalBytes: Long): String {
    val sizeLabel = fireflyMediaSizeLabel(totalBytes)
    return if (jarStorageUsageIsWarning(totalBytes)) {
        "the jars are getting heavy — $sizeLabel"
    } else {
        "your jars are holding $sizeLabel"
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161229)
@Composable
private fun PreviewJarShelf() {
    JarShelfContent(
        fireflyCounts = mapOf(
            Module.ACOUSTIC_MODEM to 3,
            Module.IMAGE_STEGANOGRAPHY to 1,
            Module.AUDIO_STEGANOGRAPHY to 0,
            Module.DETECTOR to 0,
        ),
        onSelectModule = {},
        onRevealTechnicalMode = {},
    )
}
