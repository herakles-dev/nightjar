package dev.herakles.nightjar.modules.fireflyjar

import androidx.compose.runtime.Composable
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.modules.acoustic.jarCatchFlow as acousticJarCatchFlow
import dev.herakles.nightjar.modules.audiostego.jarCatchFlow as audioStegoJarCatchFlow
import dev.herakles.nightjar.modules.detector.jarWatchFlow
import dev.herakles.nightjar.modules.imagestego.jarCatchFlow as imageStegoJarCatchFlow

/**
 * Firefly Jar shelf dispatcher (task #9): an exhaustive `when(module)` routing to each module's
 * own jar-framed flow. No catch/decode logic lives in this file — each branch calls a stub
 * composable that lives in that module's own screen file (`AcousticModemScreen.kt`,
 * `ImageStegoScreen.kt`, `AudioStegoScreen.kt`, `DetectorScreen.kt`); 4 later tasks each
 * independently fill in the real implementation inside their own stub without touching this
 * dispatcher again. See design/firefly-jar-identity.md.
 *
 * [Module.DETECTOR] is [dev.herakles.nightjar.picker.JarRole.WATCHING] — passive, no
 * encode/transmit verb — so it routes to `jarWatchFlow`, not `jarCatchFlow`, matching the
 * distinction [dev.herakles.nightjar.picker.JarRole] already draws.
 */
@Composable
fun catchFlowFor(module: Module, dao: FireflyDao, onExit: () -> Unit) {
    when (module) {
        Module.ACOUSTIC_MODEM -> acousticJarCatchFlow(dao = dao, onExit = onExit)
        Module.IMAGE_STEGANOGRAPHY -> imageStegoJarCatchFlow(dao = dao, onExit = onExit)
        Module.AUDIO_STEGANOGRAPHY -> audioStegoJarCatchFlow(dao = dao, onExit = onExit)
        Module.DETECTOR -> jarWatchFlow(dao = dao, onExit = onExit)
    }
}
