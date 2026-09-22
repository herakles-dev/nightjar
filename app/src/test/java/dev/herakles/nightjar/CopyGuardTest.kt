package dev.herakles.nightjar

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No user-facing string may say "watching jar" or "framed jar" — both are
 * pre-v6 internal names (the detector is now "the meadow", image steganography is "the art
 * jar") that leaked into a few KDoc/comment lines while the rename landed, but must never
 * reach a string literal or an Android string resource a person actually reads.
 *
 * This scans every `.kt` file's string literals (never its comments -- several already
 * legitimately say "watching jar" in a KDoc, e.g. `Color.kt`'s `JarWatchingDim` doc, precisely
 * because the internal name stays the same) plus every XML file under a `res/values` (or
 * `values`-variant) directory, so a future edit that reintroduces either phrase into real copy
 * fails the build rather than shipping.
 */
class CopyGuardTest {

    private val bannedPhrases = listOf("watching jar", "framed jar")

    @Test
    fun `no Kotlin string literal says watching jar or framed jar`() {
        val violations = mutableListOf<String>()
        for (file in kotlinSourceFiles()) {
            val text = file.readText()
            for (literal in extractStringLiterals(text)) {
                val lower = literal.text.lowercase()
                for (phrase in bannedPhrases) {
                    if (lower.contains(phrase)) {
                        violations += "${file.path}:${literal.line}: literal contains \"$phrase\" -- \"${literal.text}\""
                    }
                }
            }
        }
        assertTrue(
            "found banned copy in Kotlin string literals (\"the meadow\"/\"the art jar\" " +
                "replaced these):\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `no Android string resource says watching jar or framed jar`() {
        val violations = mutableListOf<String>()
        for (file in resourceXmlFiles()) {
            val withoutComments = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            val lower = withoutComments.lowercase()
            for (phrase in bannedPhrases) {
                if (lower.contains(phrase)) {
                    violations += "${file.path}: contains \"$phrase\""
                }
            }
        }
        assertTrue(
            "found banned copy in a values*/*.xml resource:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    // ==========================================================================================
    // File discovery -- Gradle unit tests run with the module dir (app/) as the working
    // directory, but may also run with the project root as cwd (see AudioStegDetectorTest's own
    // detectorSourceFile() precedent); try both.
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
    // Kotlin string-literal extraction -- a small character-level scanner rather than a line
    // filter, so a banned phrase sitting only in a `//` or `/** ... */` (incl. KDoc) comment is
    // never mistaken for a real user-facing string, and so a phrase split across a `${...}`
    // template expression's own nested string literal (e.g. `"...${"%.2f".format(x)}"`, several
    // of which already exist in this codebase) isn't folded into the outer literal's text.
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
                            // `${...}` template expression -- skip it without folding its own
                            // text (which may contain a nested string literal, e.g. a format
                            // string) into this literal's content.
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
