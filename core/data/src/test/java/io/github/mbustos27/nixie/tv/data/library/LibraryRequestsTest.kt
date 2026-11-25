// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.library

import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TV-S13 (a remote server answering in about a second): what the library's paths ask the server, request by request.
 * A page in the app's order (its index in memory) comes by its ids in one request; an A–Z jump's page comes from the
 * letter itself, in one request, with no place in the list asked first. The real LibraryRepository over a fake library
 * of 2,600 films, a hundred under each letter.
 */
class LibraryRequestsTest {

    private val cache: File = Files.createTempDirectory("nixie-requests").toFile()

    @After
    fun tearDown() {
        cache.deleteRecursively()
    }

    private class Film(n: Int) {
        val id: UUID = UUID.nameUUIDFromBytes("film-$n".toByteArray())
        val name = "%c%s %04d".format('A' + n / PER_LETTER, "pple", n)
        val sort = name.lowercase()
    }

    private val films = (0 until FILMS).map(::Film)

    private val requests: MutableList<Map<String, Any?>> = Collections.synchronizedList(mutableListOf())

    private val server = FakeLibraryServer(cache) { query -> answer(query) }

    private val movies = LibraryQuery(LibraryKind.Movies)

    @Test
    fun `with the index in memory, the first page comes by its ids in one request`() = runBlocking {
        val repository = server.newRepository()
        repository.availableLetters(movies) // the index, as a jump loads it
        requests.clear()
        val page = repository.firstPage(movies, limit = PAGE)
        assertFalse(page.serverOrder)
        assertEquals(films.take(PAGE).map { it.id }, page.items.map { it.id })
        val only = requests.single()
        assertEquals(PAGE, (only["ids"] as Collection<*>).size)
    }

    @Test
    fun `a jump's page comes from the letter in one request, uncounted, nothing asked before it`() = runBlocking {
        val repository = server.newRepository()
        val page = repository.page(movies, 0, PAGE, fromLetter = 'M')
        val only = requests.single()
        assertEquals("m", only["nameStartsWithOrGreater"])
        assertNull(only["nameLessThan"])
        assertEquals(0, only["startIndex"])
        assertEquals(false, only["enableTotalRecordCount"])
        assertEquals(films.drop(LETTER_M).take(PAGE).map { it.id }, page.items.map { it.id })
        assertNull(page.total)
        assertTrue(page.serverOrder)
        // The next page of it goes on from the letter, not from the list's start.
        requests.clear()
        val next = repository.page(movies, PAGE, PAGE, fromLetter = 'M')
        assertEquals(PAGE, requests.single()["startIndex"])
        assertEquals(films.drop(LETTER_M + PAGE).take(PAGE).map { it.id }, next.items.map { it.id })
    }

    @Test
    fun `Z to A, a jump's page is the titles before the next letter, going down`() = runBlocking {
        val repository = server.newRepository()
        val page = repository.page(movies.copy(descending = true), 0, PAGE, fromLetter = 'M')
        val only = requests.single()
        assertEquals("n", only["nameLessThan"])
        assertNull(only["nameStartsWithOrGreater"])
        assertEquals(SortOrder.DESCENDING, (only["sortOrder"] as Collection<*>).first())
        assertEquals(films[LETTER_M + PER_LETTER - 1].id, page.items.first().id)
    }

    /** The library as the server filters, sorts and pages it (by sort name, as SQLite compares). */
    private fun answer(query: Map<String, Any?>): String {
        requests += query
        val ids = (query["ids"] as? Collection<*>).orEmpty().map { it.toString() }.toSet()
        var list = if (ids.isNotEmpty()) films.filter { it.id.toString() in ids } else films
        (query["nameLessThan"] as? String)?.let { below -> list = list.filter { it.sort < below } }
        (query["nameStartsWithOrGreater"] as? String)?.let { from -> list = list.filter { it.sort >= from } }
        list = list.sortedBy { it.sort }
        if ((query["sortOrder"] as? Collection<*>)?.firstOrNull() == SortOrder.DESCENDING) list = list.reversed()
        val start = query["startIndex"] as? Int ?: 0
        val limit = query["limit"] as? Int ?: list.size
        val items = list.drop(start).take(limit).joinToString(",") { film ->
            """{"Id":"${film.id}","Name":"${film.name}","SortName":"${film.sort}","Type":"Movie"}"""
        }
        val total = if (query["enableTotalRecordCount"] == false) 0 else list.size
        return """{"Items":[$items],"TotalRecordCount":$total,"StartIndex":$start}"""
    }

    private companion object {
        const val FILMS = 2_600
        const val PER_LETTER = 100
        const val PAGE = 70

        /** Titles before the first M title (A to L). */
        const val LETTER_M = 1_200
    }
}
