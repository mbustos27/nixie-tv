// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv

import android.app.Application
import android.content.res.Configuration
import io.github.glacier_jellyfin.androidtv.R
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Nixie for TV must not send its users to Glacier: no Glacier name or URL in the app's strings as the nixie flavor
 * builds them (every language), nor in a string constant of the Kotlin sources, apart from the credit in About and the
 * internal names listed below with their reasons. aapt2's strings check sees resources only; this sees the code too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NoGlacierNamesTest {

    @Test
    fun `no string the app shows names Glacier, in any language, but the credit`() {
        val app = RuntimeEnvironment.getApplication()
        val found = mutableListOf<String>()
        for (locale in listOf(Locale.ENGLISH, Locale.GERMAN, Locale("es"))) {
            val resources = app.createConfigurationContext(Configuration().apply { setLocale(locale) }).resources
            for (field in R.string::class.java.fields) {
                val name = field.name
                val text = resources.getText(field.getInt(null)).toString()
                if (GLACIER.containsMatchIn(text) && name !in STRINGS_ALLOWED) found += "$locale string $name: $text"
            }
            for (field in R.plurals::class.java.fields) {
                for (count in listOf(1, 2)) {
                    val text = resources.getQuantityText(field.getInt(null), count).toString()
                    if (GLACIER.containsMatchIn(text)) found += "$locale plurals ${field.name}: $text"
                }
            }
        }
        assertEquals(emptyList<String>(), found.distinct())
    }

    // tv-1.0-r1: Download log's step may name Nixie's own Report a problem (ReportProblemTest), never an address.
    @Test
    fun `sharing the log names no tracker's address, only Nixie's own ways`() {
        val app = RuntimeEnvironment.getApplication()
        for (locale in listOf(Locale.ENGLISH, Locale.GERMAN, Locale("es"))) {
            val resources = app.createConfigurationContext(Configuration().apply { setLocale(locale) }).resources
            for (id in listOf(R.string.diag_share_step_attach, R.string.player_error_log_body)) {
                val text = resources.getString(id)
                assertFalse("$locale: $text", text.contains("github", ignoreCase = true) || text.contains("%"))
                assertTrue("$locale: $text", text.contains("Nixie"))
            }
        }
    }

    @Test
    fun `no string constant in the code names Glacier but the credit and internal names`() {
        val repo = File("").absoluteFile.let { if (File(it, "settings.gradle.kts").isFile) it else it.parentFile }
        val main = "${File.separator}src${File.separator}main${File.separator}"
        val sources = listOf("app", "core", "theme").map { File(repo, it) }
            .flatMap { root -> root.walk().filter { it.isFile && it.extension == "kt" && main in it.path }.toList() }
        check(sources.size > 100) { "the sources were not found from ${File("").absolutePath}" }
        val found = sources.flatMap { glacierLiterals(it.relativeTo(repo).invariantSeparatorsPath, it.readLines()) }
        assertEquals(emptyList<String>(), found)
    }

    /** "path:line: literal" for each string literal of [lines] that names Glacier and is not allowed. */
    private fun glacierLiterals(path: String, lines: List<String>): List<String> = lines.flatMapIndexed { index, line ->
        if (NOT_CODE.any { line.trim().startsWith(it) }) return@flatMapIndexed emptyList()
        LITERAL.findAll(line).map { it.groupValues[1] }
            // Template expressions name code (`${GlacierPlayback.x}`), not text.
            .filter { literal -> GLACIER.containsMatchIn(literal.replace(TEMPLATE, "")) }
            .filterNot { literal -> allowed(path, literal) }
            .map { "$path:${index + 1}: \"$it\"" }
            .toList()
    }

    private fun allowed(path: String, literal: String) =
        CONSTANTS_ALLOWED.any { (file, start) -> path.endsWith(file) && literal.startsWith(start) }

    private companion object {
        val GLACIER = Regex("glacier", RegexOption.IGNORE_CASE)
        val LITERAL = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        val TEMPLATE = Regex("\\$\\{[^}]*}")

        /** Lines that hold no code: package and import lines, comments. */
        val NOT_CODE = listOf("package ", "import ", "*", "//", "/*")

        /** The credit in Settings › About, and the original look's name in the theme picker (debug builds only). */
        val STRINGS_ALLOWED = setOf("settings_about_sub", "nixie_based_on", "nixie_based_on_sub", "nixie_theme_glacier")

        /** Literals allowed in the code (the file, how the literal starts), each a credit or a name no user reads. */
        val CONSTANTS_ALLOWED = listOf(
            // Settings › About: "Based on Glacier", with the source's address (the licence's credit).
            "SettingsScreen.kt" to "github.com/Glacier-Jellyfin/glacier-androidtv",
            // Intent actions in Glacier's package namespace, the code's own; Android shows them to no one.
            ".kt" to "io.github.glacier_jellyfin.androidtv.",
            // The Android keystore key that encrypts the sign-ins: renaming it would sign everyone out.
            "DataModule.kt" to "glacier.accounts",
            // The log's files and thread on the box; the file a user downloads is named Nixie's (Diagnostics).
            "LogFile.kt" to "glacier-log",
            "LogFile.kt" to "glacier.log",
            "LogFile.kt" to "glacier.1.log",
            // The music player's placeholder address for a video in the queue, never loaded.
            "MusicController.kt" to "glacier-pending://",
            // The trailer page's JavaScript bridge, inside the app's own page.
            "YouTubePlayer.kt" to "Glacier",
            // Glacier's own release feed: Nixie's flavor always sets its own (BuildConfig.UPDATE_FEED), so it is never
            // asked.
            "ReleaseFeed.kt" to "https://api.github.com/repos/Glacier-Jellyfin/glacier-androidtv/releases",
            // Glacier's release asset names, which the updater never picks (ReleaseSelector.nixieApkName takes only
            // Nixie-TV-<version>.apk).
            "ReleaseSelector.kt" to "glacier-androidtv",
        )
    }
}
