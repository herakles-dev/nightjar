package dev.herakles.nightjar.trail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INV-11: "no riddle text is shown from anywhere but a successful decode" -- static source check,
 * no Robolectric/Android needed, mirroring
 * [dev.herakles.nightjar.AudioStegDetectorTest]'s `detectorSourceNeverReferencesTheCarrierOrDecode`
 * source-scan pattern (read the real `.kt` file as text, assert on what it does/doesn't contain).
 *
 * Reads `strings_trail.xml`'s own `trail_riddle_*` values (the riddle and fallback-riddle text
 * INV-11 actually governs -- NOT the glosses/hints/skip/restart copy, which are ordinary UI
 * strings this package's KDoc comments legitimately reference by name/quote) as plain text (not
 * through Android resource resolution -- this test has no Robolectric context) and asserts none
 * of that text appears hardcoded in this package's main Kotlin source, plus a forward-looking
 * guard that no public function in this package is even declared to return a `String` at all.
 */
class TrailSourceScanTest {

    // ==============================================================================
    // Riddle/fallback text never appears as a Kotlin string literal in trail/ main source.
    // ==============================================================================

    @Test
    fun noTrailKotlinSourceFileHardcodesAnyRiddleText() {
        val riddleValues = extractStringResourceValues(stringsTrailXmlFile())
            .filterKeys { it.startsWith("trail_riddle_") }
        assertTrue("expected to find trail_riddle_* strings in strings_trail.xml", riddleValues.isNotEmpty())

        for (sourceFile in trailMainSourceFiles()) {
            val sourceText = sourceFile.readText()
            for ((name, value) in riddleValues) {
                assertFalse(
                    "${sourceFile.name} must not hardcode string resource \"$name\"'s text " +
                        "(\"$value\") -- INV-11: riddle text may only reach the app through a " +
                        "string resource lookup, never a Kotlin literal in this package",
                    sourceText.contains(value),
                )
            }
        }
    }

    // ==============================================================================
    // No public API in this package declares a String return type -- a forward-looking guard:
    // even a future public function that happened to return riddle text would trip this before
    // it could ship, independent of whatever text it actually returned.
    // ==============================================================================

    @Test
    fun noPublicFunctionInTrailIsDeclaredToReturnAString() {
        val publicFunReturningString = Regex("""^\s*(override\s+)?(suspend\s+)?fun\s+\w+\([^)]*\)\s*:\s*String\b""")
        for (sourceFile in trailMainSourceFiles()) {
            for (line in sourceFile.readLines()) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("private ") || trimmed.startsWith("internal ")) continue
                assertFalse(
                    "${sourceFile.name} declares a public function returning String: \"$trimmed\" " +
                        "-- INV-11 forbids trail/'s public API from ever returning riddle text",
                    publicFunReturningString.containsMatchIn(trimmed),
                )
            }
        }
    }

    // ==============================================================================
    // Helpers
    // ==============================================================================

    private fun trailMainSourceFiles(): List<File> {
        val dir = trailMainSourceDirectory()
        assertTrue("expected to find the trail/ main source directory at $dir", dir.exists())
        return dir.listFiles { file -> file.isFile && file.extension == "kt" }?.toList().orEmpty()
    }

    private fun trailMainSourceDirectory(): File {
        val relative = "app/src/main/java/dev/herakles/nightjar/trail"
        val direct = File(relative)
        if (direct.exists()) return direct
        // Gradle unit tests typically run with the module dir (app/) as the working directory;
        // fall back to a module-relative path if the project root was used instead (same
        // fallback AudioStegDetectorTest's detectorSourceFile() already uses).
        return File("src/main/java/dev/herakles/nightjar/trail")
    }

    private fun stringsTrailXmlFile(): File {
        val relative = "app/src/main/res/values/strings_trail.xml"
        val direct = File(relative)
        if (direct.exists()) return direct
        return File("src/main/res/values/strings_trail.xml")
    }

    /** Crude but sufficient `<string name="...">value</string>` extractor -- every entry in
     *  strings_trail.xml is a single line with no nested markup/CDATA, so a line-oriented regex
     *  is enough (no full XML parser needed for a source-scan test). Un-escapes the `\'` Android
     *  string-resource escape back to a plain apostrophe so the comparison matches what a
     *  hardcoded Kotlin string literal would actually look like. */
    private fun extractStringResourceValues(file: File): Map<String, String> {
        val pattern = Regex("""<string name="([^"]+)">(.*)</string>""")
        return file.readLines()
            .mapNotNull { line -> pattern.find(line) }
            .associate { match ->
                val (name, rawValue) = match.destructured
                name to rawValue.replace("\\'", "'")
            }
    }
}
