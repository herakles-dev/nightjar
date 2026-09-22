package dev.herakles.nightjar

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v6 verb sweep, per the "Verb rule (owner direction,
 * applies app-wide)": you **create** fireflies (embedding) and **catch** other people's
 * (decoding/receiving) -- "catch" never describes making one. The embed verb "catch a firefly"
 * became "create a firefly", and creation-success copy reads "you created one -- N bytes";
 * "look for fireflies", "catch from a photo or file", and "you caught one" (receiving) stay
 * exactly as they were.
 *
 * Two checks, in the same style [CopyGuardTest] already established (scan every
 * `.kt` file's string literals -- never comments, several of which legitimately quote the old
 * embed copy as historical record, e.g. `ui/theme/Type.kt`'s size-anchor KDoc -- plus every
 * XML string in a `values`-variant resource directory):
 *
 * 1. No user-facing string anywhere says "catch a firefly".
 * 2. Every "you caught one .../you created one ..." creation-success literal -- the specific
 *    shape `jar-mode embed success` has always used (a byte count after a dash) -- says
 *    "created", never "caught". This is a narrower net than banning "you caught one" outright:
 *    that exact phrase legitimately stays as its own, separate, unadorned string for receiving
 *    (`strings_receive.xml`'s `receive_caught_title`) precisely because it is never followed by
 *    its own inline byte count the way the embed-success shape always has been.
 */
class VerbGuardTest {

    private val bannedEmbedPhrase = "catch a firefly"

    // Matches the creation-success shape wherever it appears, e.g. "you caught one — 4 bytes" /
    // "you created one — 4 byte" / "you caught one — $bytes bytes" (a `${...}` template
    // expression's own nested string literal is never folded into the outer literal by
    // extractStringLiterals below, so this only ever sees the literal text around the hole).
    // The shape requires the dash that introduces the byte count: a bare "you caught one" is
    // receiving copy (IncomingScreen's title, the acoustic modem's receive result card) and
    // must not trip this guard.
    private val creationSuccessShape = Regex("you (caught|created) one\\s*[—–-]")

    @Test
    fun `no user-facing string says catch a firefly`() {
        val violations = mutableListOf<String>()
        for (file in kotlinSourceFiles()) {
            val text = file.readText()
            for (literal in extractStringLiterals(text)) {
                if (literal.text.lowercase().contains(bannedEmbedPhrase)) {
                    violations += "${file.path}:${literal.line}: literal contains \"$bannedEmbedPhrase\" -- \"${literal.text}\""
                }
            }
        }
        for (file in resourceXmlFiles()) {
            val withoutComments = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            if (withoutComments.lowercase().contains(bannedEmbedPhrase)) {
                violations += "${file.path}: contains \"$bannedEmbedPhrase\""
            }
        }
        assertTrue(
            "found the old embed verb \"catch a firefly\" -- " +
                "it's \"create a firefly\" now:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `creation-success copy says created, never caught`() {
        val violations = mutableListOf<String>()
        for (file in kotlinSourceFiles()) {
            val text = file.readText()
            for (literal in extractStringLiterals(text)) {
                val match = creationSuccessShape.find(literal.text) ?: continue
                if (match.value.startsWith("you caught one")) {
                    violations += "${file.path}:${literal.line}: creation-success copy still says " +
                        "\"caught\" -- \"${literal.text}\""
                }
            }
        }
        assertTrue(
            "creation-success copy must read \"you created one\"; " +
                "\"you caught one\" on its own, with no trailing byte count, is still " +
                "correct for receiving:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    // ==========================================================================================
    // File discovery -- same dual-cwd fallback CopyGuardTest documents (Gradle unit tests run
    // with the module dir (app/) as cwd, but may also run from the project root).
    // ==========================================================================================

    private fun moduleRoot(): File {
        val fromProjectRoot = File("app")
        return if (File(fromProjectRoot, "src/main/java").isDirectory) fromProjectRoot else File(".")
    }

    private fun kotlinSourceFiles(): List<File> {
        val root = File(moduleRoot(), "src/main/java")
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun resourceXmlFiles(): List<File> {
        val root = File(moduleRoot(), "src/main/res")
        if (!root.isDirectory) return emptyList()
        return root.listFiles { dir, name -> dir == root && name.startsWith("values") }
            .orEmpty()
            .filter { it.isDirectory }
            .flatMap { valuesDir -> valuesDir.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
    }

    // ==========================================================================================
    // Kotlin string-literal extraction -- verbatim copy of CopyGuardTest's own character-level
    // scanner (never a line filter, so a banned phrase sitting only in a comment is never
    // mistaken for real copy, and a `${...}` template expression's own nested string literal
    // isn't folded into the outer literal's text). Duplicated rather than shared, matching this
    // codebase's existing per-test-file file-discovery precedent (CopyGuardTest's own KDoc cites
    // AudioStegDetectorTest's `detectorSourceFile()` as the established pattern).
    // ==========================================================================================

    private data class StringLiteral(val text: String, val line: Int)

    private fun extractStringLiterals(source: String): List<StringLiteral> {
        val literals = mutableListOf<StringLiteral>()
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                c == '/' && i + 1 < n && source[i + 1] == '/' -> {
                    val end = source.indexOf('\n', i)
                    i = if (end == -1) n else end
                }
                c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    val close = source.indexOf("*/", i + 2)
                    i = if (close == -1) n else close + 2
                }
                c == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"' -> {
                    val start = i + 3
                    val close = source.indexOf("\"\"\"", start)
                    val end = if (close == -1) n else close
                    literals += StringLiteral(source.substring(start, end), lineOf(source, start))
                    i = if (close == -1) n else close + 3
                }
                c == '"' -> {
                    val startLine = lineOf(source, i)
                    val sb = StringBuilder()
                    var j = i + 1
                    while (j < n && source[j] != '"') {
                        when {
                            source[j] == '\\' && j + 1 < n -> {
                                sb.append(source[j + 1])
                                j += 2
                            }
                            source[j] == '$' && j + 1 < n && source[j + 1] == '{' -> {
                                var depth = 1
                                var k = j + 2
                                var inNestedString = false
                                while (k < n && depth > 0) {
                                    val ch = source[k]
                                    if (inNestedString) {
                                        when {
                                            ch == '\\' && k + 1 < n -> k += 1
                                            ch == '"' -> inNestedString = false
                                        }
                                    } else {
                                        when (ch) {
                                            '"' -> inNestedString = true
                                            '{' -> depth += 1
                                            '}' -> depth -= 1
                                        }
                                    }
                                    k += 1
                                }
                                j = k
                            }
                            else -> {
                                sb.append(source[j])
                                j += 1
                            }
                        }
                    }
                    literals += StringLiteral(sb.toString(), startLine)
                    i = if (j < n) j + 1 else n
                }
                else -> i += 1
            }
        }
        return literals
    }

    private fun lineOf(source: String, index: Int): Int {
        var line = 1
        for (k in 0 until index) if (source[k] == '\n') line++
        return line
    }
}
