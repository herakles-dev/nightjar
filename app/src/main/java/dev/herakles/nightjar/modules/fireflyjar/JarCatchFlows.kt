package dev.herakles.nightjar.modules.fireflyjar

import androidx.compose.runtime.Composable
import dev.herakles.nightjar.incoming.IncomingOutcome
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
 *
 * [onIncomingOutcome] (v6, task W2-1, gate-31) is the "catch from a photo or file" row's result
 * callback -- threaded to the three [dev.herakles.nightjar.picker.JarRole.CREATION] flows only,
 * per design/screen-flow.md's v6 "Receiving" section ("the meadow does not get this action --
 * it isn't a creating jar and never lands a caught firefly").
 */
@Composable
fun catchFlowFor(
    module: Module,
    repository: FireflyRepository,
    onExit: () -> Unit,
    onIncomingOutcome: (IncomingOutcome) -> Unit,
) {
    when (module) {
        Module.ACOUSTIC_MODEM -> acousticJarCatchFlow(repository = repository, onExit = onExit, onIncomingOutcome = onIncomingOutcome)
        Module.IMAGE_STEGANOGRAPHY -> imageStegoJarCatchFlow(repository = repository, onExit = onExit, onIncomingOutcome = onIncomingOutcome)
        Module.AUDIO_STEGANOGRAPHY -> audioStegoJarCatchFlow(repository = repository, onExit = onExit, onIncomingOutcome = onIncomingOutcome)
        Module.DETECTOR -> jarWatchFlow(repository = repository, onExit = onExit)
    }
}
