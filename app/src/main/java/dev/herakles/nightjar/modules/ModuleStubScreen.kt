package dev.herakles.nightjar.modules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.ui.theme.TextPrimary
import dev.herakles.nightjar.ui.theme.TextSecondary

/**
 * Placeholder destination for a module not yet built. Real content lands in later
 * tasks: acoustic modem (#7), image steganography (#9/#10), detector (#11/#12/#13).
 * Task #4 only wires the module name and back navigation — no feature UI.
 */
@Composable
fun ModuleStubScreen(module: Module, onBack: () -> Unit) {
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
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = module.label,
                style = MaterialTheme.typography.displayLarge,
                color = TextPrimary,
            )
            Text(
                text = "not built yet",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
        }
    }
}
