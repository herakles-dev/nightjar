package dev.herakles.nightjar.modules.fireflyjar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.picker.JarRole
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.ui.theme.FireflyCreated
import dev.herakles.nightjar.ui.theme.FireflyReceived
import dev.herakles.nightjar.ui.theme.JarBgDusk
import dev.herakles.nightjar.ui.theme.JarBgHorizon
import dev.herakles.nightjar.ui.theme.JarTextPrimary
import dev.herakles.nightjar.ui.theme.JarTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Task #10 — the Firefly Jar per-module detail screen (design/screen-flow.md § Screen 7,
 * architecture.md § Firefly Jar § 6). One composable serves every [Module]: the shared shell
 * (wordmark, firefly swarm or watching-jar readout, the module's own catch/look flow) is
 * identical for all of them, driven only by [Module.jarName]/[Module.jarRole] — this file
 * never `when`s on which specific module it is. Architecture.md § 6 reserves that exhaustive
 * per-module branch to exactly two places, [FireflyGlyphs.drawJarGlyph] and [catchFlowFor]
 * (`JarCatchFlows.kt`, task #9); this screen only hosts what [catchFlowFor] hands it — it
 * never reimplements embed/extract/transmit/listen logic itself (screen-flow.md § Screen 7
 * "what this addition deliberately does not build").
 *
 * For [JarRole.CREATION] modules the shell shows a scattered swarm of firefly dots (one per
 * [FireflyRecord] from [FireflyDao.observeByModule]) above the module's catch/look flow; for
 * [JarRole.WATCHING] (today just the detector) it skips the swarm entirely — that module never
 * writes a [FireflyRecord] (architecture.md § 3), and [catchFlowFor] hosts its own re-skinned
 * confidence/history readout instead.
 */
@Composable
fun JarDetailScreen(module: Module, dao: FireflyDao, onBack: () -> Unit) {
    val fireflyFlow = remember(dao, module) { dao.observeByModule(module.name) }
    val fireflies by fireflyFlow.collectAsState(initial = emptyList())
    var selectedFirefly by remember(module) { mutableStateOf<FireflyRecord?>(null) }

    JarDetailContent(
        module = module,
        fireflies = fireflies,
        selectedFirefly = selectedFirefly,
        onSelectFirefly = { selectedFirefly = it },
        onDismissDetail = { selectedFirefly = null },
        onBack = onBack,
        moduleFlow = { catchFlowFor(module = module, dao = dao, onExit = onBack) },
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
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(JarBgDusk, JarBgHorizon))),
    ) {
        if (selectedFirefly != null) {
            FireflyDetailContent(module = module, firefly = selectedFirefly, onBack = onDismissDetail)
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
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = module.jarName,
                            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Light),
                            color = JarTextPrimary,
                        )
                        Text(
                            text = jarDetailCaption(module.jarRole),
                            style = MaterialTheme.typography.labelSmall,
                            color = JarTextSecondary,
                        )
                    }

                    if (module.jarRole == JarRole.CREATION) {
                        FireflySwarm(fireflies = fireflies, onSelect = onSelectFirefly)
                    }

                    moduleFlow()
                }
            }
        }
    }
}

@Composable
private fun BackRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = JarTextSecondary)
    }
}

/** Static, module-agnostic caption per [JarRole] — architecture.md § 6 keeps this file from
 *  branching on a specific [Module], only on the 2-case [JarRole] axis (same axis
 *  [FireflyDao]'s own logging discipline already draws — see architecture.md § 3). */
private fun jarDetailCaption(role: JarRole): String = when (role) {
    JarRole.CREATION -> "every firefly you've caught or spotted here"
    JarRole.WATCHING -> "hold it up and see if anything glows nearby"
}

@Composable
private fun FireflySwarm(fireflies: List<FireflyRecord>, onSelect: (FireflyRecord) -> Unit) {
    if (fireflies.isEmpty()) {
        Text(text = "no fireflies yet", style = MaterialTheme.typography.labelSmall, color = JarTextSecondary)
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        fireflies.forEachIndexed { index, firefly ->
            FireflyDot(record = firefly, index = index, onClick = { onSelect(firefly) })
        }
    }
}

/**
 * One firefly: a soft glow that blinks asynchronously (design/firefly-jar-identity.md §
 * Motion) — period derived from [FireflyRecord.id] into the 1.8-3.2s range the doctrine
 * specifies, phase-offset by [index] so a jar's fireflies don't flash in unison. Deterministic
 * per record (not [kotlin.random.Random]) so the same firefly blinks the same way across
 * recompositions and in `@Preview`.
 */
@Composable
private fun FireflyDot(record: FireflyRecord, index: Int, onClick: () -> Unit) {
    val color = if (record.direction == "CREATED") FireflyCreated else FireflyReceived
    val periodMillis = remember(record.id) { 1800 + (record.id % 15L).toInt() * 100 }
    val transition = rememberInfiniteTransition(label = "firefly-blink")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset((index * 137) % periodMillis),
        ),
        label = "firefly-alpha",
    )
    Canvas(
        modifier = Modifier
            .size(28.dp)
            .clickable(onClick = onClick),
    ) {
        val radius = size.minDimension * 0.22f
        drawCircle(color = color.copy(alpha = 0.28f * alpha), radius = radius * 2.2f)
        drawCircle(color = color.copy(alpha = alpha), radius = radius)
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
 */
@Composable
private fun FireflyDetailContent(module: Module, firefly: FireflyRecord, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        BackRow(label = "back to the jar", onClick = onBack)

        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = if (firefly.direction == "CREATED") "a firefly you caught" else "a firefly you spotted",
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Light),
                color = JarTextPrimary,
            )
            Text(
                text = "${formatFireflyTime(firefly.timestampMillis)}, ${module.jarName}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = JarTextSecondary,
            )
            firefly.payloadPreview?.let { preview ->
                Text(
                    text = "\"$preview\"",
                    style = MaterialTheme.typography.bodyLarge,
                    color = JarTextPrimary,
                )
            }
            Text(
                text = fireflyByteLabel(firefly.payloadSizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = JarTextSecondary,
            )
        }
    }
}

private fun formatFireflyTime(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMillis))

private fun fireflyByteLabel(bytes: Int): String = if (bytes == 1) "1 byte" else "$bytes bytes"

// --- Previews: JarDetailContent is pure, so these need no FireflyDao/CovertCarrier fake. ---

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

@Preview(showBackground = true, backgroundColor = 0xFF161229)
@Composable
private fun PreviewJarDetailCreation() {
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
        moduleFlow = {
            Text(
                text = "catch a firefly / look for fireflies",
                style = MaterialTheme.typography.labelLarge,
                color = JarTextSecondary,
            )
        },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF161229)
@Composable
private fun PreviewJarDetailEmpty() {
    JarDetailContent(
        module = Module.IMAGE_STEGANOGRAPHY,
        fireflies = emptyList(),
        selectedFirefly = null,
        onSelectFirefly = {},
        onDismissDetail = {},
        onBack = {},
        moduleFlow = {},
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF161229)
@Composable
private fun PreviewJarDetailWatching() {
    JarDetailContent(
        module = Module.DETECTOR,
        fireflies = emptyList(),
        selectedFirefly = null,
        onSelectFirefly = {},
        onDismissDetail = {},
        onBack = {},
        moduleFlow = {
            Text(text = "watch", style = MaterialTheme.typography.labelLarge, color = JarTextSecondary)
        },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF161229)
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
