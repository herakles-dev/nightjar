package dev.herakles.nightjar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.herakles.nightjar.modules.ModuleStubScreen
import dev.herakles.nightjar.modules.acoustic.AcousticModemScreen
import dev.herakles.nightjar.modules.audiostego.AudioStegoScreen
import dev.herakles.nightjar.modules.detector.DetectorScreen
import dev.herakles.nightjar.modules.fireflyjar.FireflyDatabase
import dev.herakles.nightjar.modules.fireflyjar.JarDetailScreen
import dev.herakles.nightjar.modules.fireflyjar.JarShelfScreen
import dev.herakles.nightjar.modules.imagestego.ImageStegoScreen
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.picker.ModulePicker
import dev.herakles.nightjar.ui.theme.BgBase
import dev.herakles.nightjar.ui.theme.NightjarTheme

/**
 * Home screen entry point: module-picker → one of the real module screens. Task #4 built
 * this navigation shell against `ModuleStubScreen`; task #24 wired in the first 3 real
 * screens (`AcousticModemScreen`, `DetectorScreen`, `ImageStegoScreen`, tasks #7/#10/#13
 * against concrete carriers/detectors from tasks #5/#6/#9/#11/#12); a later task added
 * `AudioStegoScreen` (Module 2, `AudioStegoCarrier`). `ModuleStubScreen` stays imported and
 * reachable via [Screen.ModuleStub] for any future module that lands without a real screen
 * yet — no androidx.navigation dependency, a plain sealed-state switch is enough, matching
 * whisper-voice-app's MainActivity pattern.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NightjarTheme {
                NightjarApp()
            }
        }
    }
}

private sealed interface Screen {
    /** App root (task #3, "Firefly Jar" v3). Long-press reveal (later task) reaches [Picker]. */
    data object JarShelf : Screen
    data object Picker : Screen
    data object AcousticModem : Screen
    data object ImageSteganography : Screen
    data object Detector : Screen
    data object AudioSteganography : Screen

    /** Firefly detail view for a jar on [JarShelf] (task #3 routing; real screen landed task
     *  #10 — see `JarDetailScreen.kt`). */
    data class JarDetail(val module: Module) : Screen

    /** Reachable only for a future module without a real screen yet (see MainActivity.kt KDoc). */
    data class ModuleStub(val module: Module) : Screen
}

/** Label the `COVERT_DEBUG` probe (task #18) reports for each [Screen] value. */
private val Screen.debugLabel: String
    get() = when (this) {
        is Screen.JarShelf -> "jar_shelf"
        is Screen.Picker -> "picker"
        is Screen.AcousticModem -> "module:${Module.ACOUSTIC_MODEM.name}"
        is Screen.ImageSteganography -> "module:${Module.IMAGE_STEGANOGRAPHY.name}"
        is Screen.Detector -> "module:${Module.DETECTOR.name}"
        is Screen.AudioSteganography -> "module:${Module.AUDIO_STEGANOGRAPHY.name}"
        is Screen.JarDetail -> "jar_detail:${module.name}"
        is Screen.ModuleStub -> "module_stub:${module.name}"
    }

@Composable
fun NightjarApp() {
    var screen: Screen by remember { mutableStateOf<Screen>(Screen.JarShelf) }

    val context = LocalContext.current
    val fireflyDao = remember { FireflyDatabase.getInstance(context).fireflyDao() }

    // Task #18 probe wiring: every screen change (including the initial composition,
    // since LaunchedEffect runs immediately too) is reported to DebugProbe, so
    // `hek logcat --filter COVERT_DEBUG` shows the current module-picker screen without
    // falling back to a screenshot. No-op in release builds (see DebugProbe.kt).
    LaunchedEffect(screen) {
        DebugProbe.reportScreen(screen.debugLabel)
    }

    // System back / predictive-back gesture returns to JarShelf (the new root) from
    // Picker or a jar detail view, or to Picker from any module/stub screen reached
    // through it. Disabled on JarShelf itself so back there falls through to the
    // default (exit app) behavior.
    BackHandler(enabled = screen !is Screen.JarShelf) {
        screen = when (screen) {
            is Screen.Picker, is Screen.JarDetail -> Screen.JarShelf
            else -> Screen.Picker
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        when (val current = screen) {
            is Screen.JarShelf -> JarShelfScreen(
                dao = fireflyDao,
                onSelectModule = { module -> screen = Screen.JarDetail(module) },
                onRevealTechnicalMode = { screen = Screen.Picker },
            )
            is Screen.JarDetail -> JarDetailScreen(
                module = current.module,
                dao = fireflyDao,
                onBack = { screen = Screen.JarShelf },
            )
            is Screen.Picker -> ModulePicker(
                onSelect = { module ->
                    screen = when (module) {
                        Module.ACOUSTIC_MODEM -> Screen.AcousticModem
                        Module.IMAGE_STEGANOGRAPHY -> Screen.ImageSteganography
                        Module.DETECTOR -> Screen.Detector
                        Module.AUDIO_STEGANOGRAPHY -> Screen.AudioSteganography
                    }
                },
            )
            is Screen.AcousticModem -> AcousticModemScreen(
                // Task #30: protocol/symbol-rate selector on the screen rebuilds the carrier
                // through this factory rather than routing to one fixed AcousticCarrier()
                // instance — see AcousticModemScreen.kt's file KDoc.
                carrierFactory = { protocol, symbolRate ->
                    AcousticCarrier(protocol = protocol, symbolRate = symbolRate)
                },
                onBack = { screen = Screen.Picker },
            )
            is Screen.ImageSteganography -> ImageStegoScreen(
                carrierFactory = { bitmap -> ImageStegoCarrier(bitmap) },
                detector = ImageSteganalysis(),
                onBack = { screen = Screen.Picker },
            )
            is Screen.Detector -> DetectorScreen(
                detector = AcousticDetector(),
                onBack = { screen = Screen.Picker },
            )
            is Screen.AudioSteganography -> AudioStegoScreen(
                carrierFactory = { cover, technique -> AudioStegoCarrier(cover, technique) },
                onBack = { screen = Screen.Picker },
            )
            is Screen.ModuleStub -> ModuleStubScreen(
                module = current.module,
                onBack = { screen = Screen.Picker },
            )
        }
    }
}
