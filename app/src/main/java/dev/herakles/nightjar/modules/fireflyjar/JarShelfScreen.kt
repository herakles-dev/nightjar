package dev.herakles.nightjar.modules.fireflyjar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.ui.theme.JarBgDusk
import dev.herakles.nightjar.ui.theme.JarBgHorizon
import dev.herakles.nightjar.ui.theme.JarGlassOutline
import dev.herakles.nightjar.ui.theme.JarTextPrimary
import dev.herakles.nightjar.ui.theme.JarTextSecondary
import kotlinx.coroutines.launch

/**
 * Task #7 — the Firefly Jar shelf (screen-flow.md Screen 6), the app's new default root
 * (`Screen.JarShelf` in `MainActivity.kt`, wired for task #3). One tile per
 * [Module.entries] — not hardcoded, matches architecture.md § 6 — each showing that
 * module's [Module.jarName], its [drawJarGlyph] icon, and a live firefly count sourced
 * from [FireflyDao.observeByModule]. Long-pressing the wordmark reveals the technical
 * picker (`Screen.Picker`) via [onRevealTechnicalMode]; tapping a tile navigates to that
 * module's `Screen.JarDetail` (task #10, still a placeholder as of this task).
 *
 * Visual language is design/firefly-jar-identity.md, not identity.md — see that doc for
 * why gradients, glow, and warm/cute copy are in bounds here when they're not on the
 * technical screens underneath the reveal.
 */
@Composable
fun JarShelfScreen(
    dao: FireflyDao,
    onSelectModule: (Module) -> Unit,
    onRevealTechnicalMode: () -> Unit,
) {
    val fireflyCounts = Module.entries.associateWith { module ->
        val records by dao.observeByModule(module.name).collectAsState(initial = emptyList())
        records.size
    }
    val coroutineScope = rememberCoroutineScope()

    JarShelfContent(
        fireflyCounts = fireflyCounts,
        onSelectModule = onSelectModule,
        onRevealTechnicalMode = onRevealTechnicalMode,
        onClearHistory = { coroutineScope.launch { dao.clearAll() } },
    )
}

/**
 * Pure/previewable content: no [FireflyDao], no `Flow` collection — takes the count per
 * module already resolved, same stateful-root/pure-content split every other screen in
 * this app uses (see e.g. `DetectorScreen`'s `DetectorContent`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JarShelfContent(
    fireflyCounts: Map<Module, Int>,
    onSelectModule: (Module) -> Unit,
    onRevealTechnicalMode: () -> Unit,
    onClearHistory: () -> Unit = {},
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(JarBgDusk, JarBgHorizon)))
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = onRevealTechnicalMode)
                .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "firefly jar",
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Light),
                color = JarTextPrimary,
            )
            Text(
                text = "long-press to open the workshop",
                style = MaterialTheme.typography.labelSmall,
                color = JarTextSecondary,
            )
        }

        Module.entries.forEach { module ->
            JarTile(
                module = module,
                fireflyCount = fireflyCounts[module] ?: 0,
                onClick = { onSelectModule(module) },
            )
        }

        Text(
            text = "clear history",
            style = MaterialTheme.typography.labelSmall,
            color = JarTextSecondary,
            modifier = Modifier
                .align(Alignment.End)
                .clickable { showClearConfirm = true }
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
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
private fun JarTile(module: Module, fireflyCount: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(56.dp)) {
            drawJarGlyph(module = module, stroke = JarGlassOutline)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = module.jarName,
                style = MaterialTheme.typography.bodyLarge,
                color = JarTextPrimary,
            )
            Text(
                text = fireflyCountLabel(fireflyCount),
                style = MaterialTheme.typography.labelSmall,
                color = JarTextSecondary,
            )
        }
    }
}

private fun fireflyCountLabel(count: Int): String = when (count) {
    0 -> "no fireflies yet"
    1 -> "1 firefly"
    else -> "$count fireflies"
}

@Preview(showBackground = true, backgroundColor = 0xFF161229)
@Composable
private fun PreviewJarShelf() {
    JarShelfContent(
        fireflyCounts = mapOf(
            Module.ACOUSTIC_MODEM to 3,
            Module.IMAGE_STEGANOGRAPHY to 1,
            Module.DETECTOR to 0,
            Module.AUDIO_STEGANOGRAPHY to 0,
        ),
        onSelectModule = {},
        onRevealTechnicalMode = {},
    )
}
