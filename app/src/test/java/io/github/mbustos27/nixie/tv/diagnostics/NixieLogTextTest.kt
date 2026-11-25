// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import io.github.glacier_jellyfin.androidtv.navigation.DetailRoute
import io.github.glacier_jellyfin.androidtv.navigation.FolderRoute
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.PersonRoute
import io.github.glacier_jellyfin.androidtv.navigation.SearchRoute
import io.github.glacier_jellyfin.androidtv.navigation.SignInRoute
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tv-1.0-fixes (the prep stream): with Detailed logging on, every page that opened was logged with the names its route
 * carries: a library's, a folder's, a person's, the film the person was opened from. A shared log may go into a public
 * issue: pages are logged by their ids, the names only by their length. Made-up names only.
 */
class NixieLogTextTest {

    private val film = "The Lighthouse Keeper's Daughter"
    private val person = "Marguerite Okonkwo-Lindqvist"

    @Test
    fun `a page's names are logged by their length, its ids as they are`() {
        val lines = listOf(
            NixieLogText.route(LibraryRoute("Movies", libraryId = "lib-7", title = "Grandma's Films")),
            NixieLogText.route(FolderRoute("folder-9", title = "Summer at the Lake 2019")),
            NixieLogText.route(PersonRoute("person-3", fromTitle = film, role = "Head Keeper", name = person)),
            NixieLogText.route(SignInRoute("s1", username = "marguerite")),
            NixieLogText.route(SearchRoute(query = "lighthouse")),
        )
        val all = lines.joinToString("\n")

        for (name in listOf(film, person, "Grandma", "Summer at the Lake", "Head Keeper", "marguerite", "lighthouse")) {
            assertFalse("\"$name\" in the log:\n$all", all.contains(name, ignoreCase = true))
        }
        val library = "LibraryRoute(kind=Movies, libraryId=lib-7, title=<15 chars>, genreId=null, collections=false, " +
            "fromBar=false)"
        assertEquals(library, lines[0])
        assertTrue(lines[2], "personId=person-3" in lines[2] && "fromTitle=<32 chars>" in lines[2])
        assertEquals("DetailRoute(itemId=i1, themeArea=null, seasonId=null)", NixieLogText.route(DetailRoute("i1")))
    }

    @Test
    fun `every route's text field that is not an id is one the log leaves out`() {
        val fields = sources().flatMap { file ->
            ROUTE.findAll(file.readText()).flatMap { route ->
                FIELD.findAll(route.groupValues[2]).map { it.groupValues[1] }
                    .filterNot { it.endsWith("Id") || it in ID_FIELDS }
                    .map { "${route.groupValues[1]}.$it" }
            }
        }.toSortedSet()

        assertEquals(
            "a route's text field the navigation log would write as it is: leave it out in NixieLogText.route",
            MASKED,
            fields,
        )
    }

    @Test
    fun `the navigation logs a page only through NixieLogText`() {
        val host = sources().single { it.name == "GlacierNavHost.kt" }.readText()

        assertTrue("NixieLogText.route(event.route)" in host)
        assertFalse(Regex("""Log\.\w\([^\n]*\$\{?event\.route\b""").containsMatchIn(host))
    }

    // tv-1.0-privacy: a public id of a title (TMDB, TVDB, IMDb, a YouTube video) says what was watched or looked at.
    @Test
    fun `no log line writes a title's public id`() {
        val repo = File("").absoluteFile.let { if (File(it, "settings.gradle.kts").isFile) it else it.parentFile }
        val files = listOf("app", "core").flatMap { module ->
            File(repo, module).walk().onEnter { it.name != "build" }
                .filter { it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }.toList()
        }
        check(files.size > 100) { "the sources were not found from ${File("").absolutePath}" }

        val lines = files.flatMap { file ->
            val calls = LOG_CALL.findAll(file.readText()).map { it.value }
            calls.filter { PUBLIC_ID.containsMatchIn(it) }.map { "${file.name}: $it" }.toList()
        }

        assertEquals("log lines with a title's public id", emptyList<String>(), lines)
    }

    private fun sources(): List<File> {
        val repo = File("").absoluteFile.let { if (File(it, "settings.gradle.kts").isFile) it else it.parentFile }
        val files = File(repo, "app/src/main").walk().filter { it.extension == "kt" }.toList()
        check(files.size > 100) { "the sources were not found from ${File("").absolutePath}" }
        return files
    }

    private companion object {
        /** A route's parameters, comments in them with their own parentheses included. */
        val ROUTE = Regex("""data class (\w+Route)\(((?:[^()]|\([^()]*\))*)\)""")
        val FIELD = Regex("""val (\w+): String\??""")

        /** A call of a log, its arguments with one level of parentheses inside. */
        val LOG_CALL = Regex("""\b(?:Log\.[dviwe]|log)\((?:[^()]|\([^()]*\))*\)""")

        /** A title's public id or a lookup's address written into a line. */
        val PUBLIC_ID = Regex("""\$\{?[\w.?!]*(?:videoId|tmdbId|tvdbId|imdbId|youTubeId|youtubeId)\b""")

        /** String fields that hold an id or a fixed word, never a name. */
        val ID_FIELDS = setOf("kind", "type", "seerrType", "themeArea", "focus", "queueOf")

        val MASKED = sortedSetOf(
            "FolderRoute.title",
            "LibraryRoute.title",
            "PersonRoute.fromTitle",
            "PersonRoute.name",
            "PersonRoute.role",
            "SearchRoute.query",
            "SignInRoute.username",
        )
    }
}
