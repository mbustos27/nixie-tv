// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design rule 11 (docs/DESIGN-RULES.md: the user is not the server's admin), as the desktop's `ServerWordsTests`: no
 * text the app shows says "your server" (the server is someone else's: "the server" or "the server you use"), and none
 * says "check that the server is running", a step only the server's admin can take. The repository's publish check
 * found six such strings (docs/PUBLISH-CHECKLIST.md, item 5).
 *
 * Read: every module's string resources in English, German and Spanish (their comments left out), the string literals
 * of the Kotlin sources the app is built from (comments left out, so a comment may quote the old words), and the
 * documents users read: README.md, PRIVACY.md and the issue forms.
 */
class ServerWordsTest {

    private val repo = File("").absoluteFile.let { if (File(it, "settings.gradle.kts").isFile) it else it.parentFile }

    @Test
    fun `no text the app shows says your server or check that the server is running`() {
        val found = mutableListOf<String>()
        val resources = resourceFiles()
        check(resources.size > 20) { "the string resources were not found from ${repo.path}" }
        for (file in resources) {
            val words = wordsFor(file.parentFile?.name.orEmpty()) ?: continue
            found += offences(pathOf(file), withoutXmlComments(file.readText()), words)
        }
        val sources = kotlinSources()
        check(sources.size > 100) { "the Kotlin sources were not found from ${repo.path}" }
        for (file in sources) found += offences(pathOf(file), onlyStrings(file.readText()), ENGLISH)
        for (file in documents()) found += offences(pathOf(file), withoutXmlComments(file.readText()), ENGLISH)
        assertEquals(RULE, emptyList<String>(), found)
    }

    private fun pathOf(file: File) = file.relativeTo(repo).invariantSeparatorsPath

    @Test
    fun `the scan reads strings and markup, not comments`() {
        assertTrue(ENGLISH.containsMatchIn("Marked on your server"))
        assertTrue(ENGLISH.containsMatchIn("Enter the address of your Jellyfin server"))
        assertTrue(ENGLISH.containsMatchIn("posters come from your own server"))
        assertTrue(ENGLISH.containsMatchIn("Check that the server is running, then try again."))
        assertTrue(!ENGLISH.containsMatchIn("Connect to the Jellyfin server you use. Your library, your account."))
        assertTrue(GERMAN.containsMatchIn("Auf deinem Server markiert"))
        assertTrue(GERMAN.containsMatchIn("die Adresse deines Jellyfin-Servers"))
        assertTrue(GERMAN.containsMatchIn("an Ihren Server"))
        assertTrue(!GERMAN.containsMatchIn("Auf dem Server markiert, den du nutzt"))
        assertTrue(SPANISH.containsMatchIn("Marcado en tu servidor"))
        assertTrue(SPANISH.containsMatchIn("Comprueba que el servidor esté funcionando"))
        assertTrue(!SPANISH.containsMatchIn("Conecta este dispositivo al servidor Jellyfin que usas"))

        val code = """
            // it said "your server"
            /* your server /* nested your server */ a comment */ val a = "https://x.example/"; val b = "your server"
            /** Check that the server is running */
            val c = "${'$'}{if (x) "a" else "b"} your Jellyfin server ${'$'}z"; val d = '"'
            val e = "\"// your server"
            val f = ${"\"\"\""}a "raw" // your server${"\"\"\""}
        """.trimIndent()
        val shown = onlyStrings(code)
        assertEquals(code.count { it == '\n' }, shown.count { it == '\n' }) // lines kept, for the file:line
        assertEquals(4, ENGLISH.findAll(shown).count())
        assertTrue(shown, "\"your server\"" in shown)
        assertTrue(shown, "it said" !in shown && "nested" !in shown && "running" !in shown)
        assertTrue(shown, "val b" !in shown) // code is blanked: only the literals are read

        val markup = withoutXmlComments("<!--  your server  -->\n<string name=\"a\">Marked on your server</string>")
        assertEquals(1, ENGLISH.findAll(markup).count())
        assertTrue(markup.startsWith("\n"))
    }

    /** Each module's XML files in `src/<set>/res/values[-de|-es]`, outside build folders. */
    private fun resourceFiles(): List<File> = listOf("app", "core", "theme").map { File(repo, it) }
        .flatMap { root ->
            root.walk().onEnter { it.name != "build" && !it.name.startsWith(".") }
                .filter { it.isFile && it.extension == "xml" && it.parentFile?.name?.startsWith("values") == true }
                .filter { it.parentFile?.parentFile?.name == "res" }
                .toList()
        }

    /** The Kotlin sources the app is built from: each module's `src/<set>/` but its tests. */
    private fun kotlinSources(): List<File> = listOf("app", "core", "theme").map { File(repo, it) }
        .flatMap { root ->
            root.walk().onEnter { it.name != "build" && !it.name.startsWith(".") }
                .filter { it.isFile && it.extension == "kt" && sourceSet(it)?.startsWith("test") == false }
                .filter { sourceSet(it)?.startsWith("androidTest") == false }
                .toList()
        }

    /** The source set a file is in ("main", "debug", "testDebug"...), or null outside `src`. */
    private fun sourceSet(file: File): String? {
        val parts = file.relativeTo(repo).invariantSeparatorsPath.split('/')
        return parts.zipWithNext().firstOrNull { (dir, _) -> dir == "src" }?.second
    }

    private fun documents(): List<File> {
        val forms = File(repo, ".github/ISSUE_TEMPLATE").listFiles { f -> f.extension == "yml" }.orEmpty().sorted()
        check(forms.isNotEmpty()) { ".github/ISSUE_TEMPLATE has no .yml form: the scan would cover none" }
        return listOf(File(repo, "README.md"), File(repo, "PRIVACY.md")).onEach { check(it.isFile) { it.path } } + forms
    }

    private fun wordsFor(folder: String): Regex? = when (folder) {
        "values" -> ENGLISH

        "values-de" -> GERMAN

        "values-es" -> SPANISH

        // Other qualifiers (values-night, values-v31...) hold no text in another language.
        else -> null
    }

    private fun offences(
        path: String,
        text: String,
        words: Regex,
    ): List<String> = words.findAll(text).map { match ->
        val line = text.substring(0, match.range.first).count { it == '\n' } + 1
        val start = text.lastIndexOf('\n', match.range.first) + 1
        val end = text.indexOf('\n', match.range.first).let { if (it < 0) text.length else it }
        "$path:$line: ${text.substring(start, end).trim()}"
    }.toList()

    private companion object {
        const val RULE = "Rule 11 (\"the server\" or \"the server you use\"; no step on the server)"

        val ENGLISH = Regex(
            """\byour\s+(?:own\s+)?(?:jellyfin\s+)?servers?\b|\bserver\s+is\s+running\b""",
            RegexOption.IGNORE_CASE,
        )

        /** "dein"/"Ihr" and their forms ("Ihr" capitalised: "ihr Server" is "her" or "their"), "Server läuft". */
        val GERMAN = Regex(
            """(?:\b(?i:dein(?:e[mnrs]?)?)|\bIhr(?:e[mnrs]?)?)\s+""" +
                """(?i:eigenen?\s+)?(?i:jellyfin-?\s*)?(?i:servers?)\b|\b(?i:server)\s+läuft\b""",
        )

        /** "tu"/"tus" ("su" is also "its" or "their"), and "el servidor esté/está funcionando". */
        val SPANISH = Regex(
            """\btus?\s+(?:propios?\s+)?servidor(?:es)?\b|\bservidor\s+est[éá]\s+(?:funcionando|en\s+marcha)\b""",
            RegexOption.IGNORE_CASE,
        )

        private val XML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

        fun withoutXmlComments(markup: String): String =
            XML_COMMENT.replace(markup) { m -> "\n".repeat(m.value.count { it == '\n' }) }

        /**
         * The string literals of Kotlin [code], the rest blanked (comments too); line breaks stay, so a match's line is
         * the file's. Reads plain and raw strings, their templates' code (which may hold strings of its own), character
         * literals and nested block comments.
         */
        fun onlyStrings(code: String): String {
            val shown = StringBuilder(code.length)
            KotlinStrings(code, shown).code(0, hole = false)
            return shown.toString()
        }
    }

    /** A small Kotlin lexer: string literals' text into [shown], everything else as spaces (line breaks kept). */
    private class KotlinStrings(private val s: String, private val shown: StringBuilder) {

        private fun blank(from: Int, to: Int) {
            for (i in from until to) shown.append(if (s[i] == '\n') '\n' else ' ')
        }

        /** Code from [start]; in a template's hole, up to its closing brace (returned, blanked). */
        @Suppress("CyclomaticComplexMethod") // A lexer: one case per kind of token, spelled out.
        fun code(start: Int, hole: Boolean): Int {
            var i = start
            var depth = 0
            while (i < s.length) {
                val c = s[i]
                val next = s.getOrElse(i + 1) { '\u0000' }
                when {
                    c == '/' && next == '/' -> {
                        val end = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                        blank(i, end)
                        i = end
                    }

                    c == '/' && next == '*' -> i = blockComment(i)

                    c == '"' -> i = string(i)

                    c == '\'' -> {
                        var j = i + 1
                        while (j < s.length && s[j] != '\'' && s[j] != '\n') j += if (s[j] == '\\') 2 else 1
                        j = minOf(j + 1, s.length)
                        blank(i, j)
                        i = j
                    }

                    else -> {
                        if (c == '{') depth++
                        if (c == '}' && depth-- == 0 && hole) return i
                        blank(i, i + 1)
                        i++
                    }
                }
            }
            return i
        }

        /** Kotlin's block comments nest. */
        private fun blockComment(start: Int): Int {
            var i = start + 2
            var depth = 1
            while (i < s.length && depth > 0) {
                when {
                    s.startsWith("/*", i) -> {
                        depth++
                        i += 2
                    }

                    s.startsWith("*/", i) -> {
                        depth--
                        i += 2
                    }

                    else -> i++
                }
            }
            blank(start, minOf(i, s.length))
            return minOf(i, s.length)
        }

        /** A string from its opening quote at [start]; returns the index after its closing quote. */
        @Suppress("CyclomaticComplexMethod") // The same.
        private fun string(start: Int): Int {
            val raw = s.startsWith("\"\"\"", start)
            val quote = if (raw) "\"\"\"" else "\""
            shown.append(quote)
            var i = start + quote.length
            while (i < s.length) {
                val c = s[i]
                when {
                    raw && s.startsWith("\"\"\"", i) -> {
                        // A raw string ends at the last of a run of quotes.
                        var end = i + 3
                        while (end < s.length && s[end] == '"') end++
                        shown.append(s, i, end)
                        return end
                    }

                    !raw && c == '"' -> {
                        shown.append(c)
                        return i + 1
                    }

                    // An unterminated line: leave it to the code.
                    !raw && c == '\n' -> return i

                    !raw && c == '\\' && i + 1 < s.length -> {
                        shown.append(s, i, i + 2)
                        i += 2
                    }

                    c == '$' && s.getOrElse(i + 1) { ' ' } == '{' -> {
                        // A template's code, which is not text (it may hold strings of its own).
                        blank(i, i + 2)
                        val close = code(i + 2, hole = true)
                        if (close < s.length) blank(close, close + 1)
                        i = close + 1
                    }

                    else -> {
                        shown.append(c)
                        i++
                    }
                }
            }
            return i
        }
    }
}
