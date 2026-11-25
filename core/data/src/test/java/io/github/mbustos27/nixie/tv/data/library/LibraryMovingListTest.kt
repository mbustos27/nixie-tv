// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.library

import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The review's Blocker (TV-S1): a list paged from the server while the server's list moves. Between pages a film is
 * added at the top and titles are removed above the page boundary; titles that tie on everything the server is asked to
 * sort by come back in a new order with every request (a database's order of equal rows is not fixed). For every
 * order paged on the server, the grid's list must hold each title once (its keys) and skip none that stayed.
 * The real repository, paged as the view model pages (NixieLibraryPaging.appended).
 */
class LibraryMovingListTest {

    private val cache: File = Files.createTempDirectory("nixie-moving").toFile()

    @After
    fun tearDown() {
        cache.deleteRecursively()
    }

    private class Film(
        val id: UUID,
        val name: String,
        val created: Instant,
        val year: Int?,
        val rating: Float?,
        val runtimeMinutes: Int?,
    )

    private val random = Random(20261122)
    private val base = Instant.parse("2020-01-01T00:00:00Z")

    /**
     * 300 films: 160 alike in everything but their id and when they were added (one long tie in every order), the rest
     * with names, years, ratings and lengths that often tie or are missing.
     */
    private val films: MutableList<Film> = MutableList(FILMS) { n ->
        val created = base.plusSeconds(n * 5_821L)
        if (n % 15 < 8) {
            Film(id(n), "Same Title", created, 2001, 6f, 95)
        } else {
            Film(
                id(n),
                "Film %03d".format(n % 40),
                created,
                (1990 + random.nextInt(8)).takeIf { random.nextInt(5) > 0 },
                (2 + random.nextInt(7)).toFloat().takeIf { random.nextInt(6) > 0 },
                (80 + random.nextInt(5) * 10).takeIf { random.nextInt(8) > 0 },
            )
        }
    }

    private fun id(n: Int) = UUID.nameUUIDFromBytes("film-$n".toByteArray())

    private var requests = 0

    private val server = FakeLibraryServer(cache) { query -> answer(query) }

    /** Orders [films] as asked, equal ones in a new order each time, and answers the page asked for. */
    private fun answer(query: Map<String, Any?>): String {
        requests++
        val by = (query["sortBy"] as? Collection<*>).orEmpty().map { it as ItemSortBy }
        val order = (query["sortOrder"] as? Collection<*>).orEmpty().map { it as SortOrder }
        val noise = films.associate { it.id to random.nextDouble() }
        var comparator: Comparator<Film> = Comparator { _, _ -> 0 }
        by.forEachIndexed { i, key ->
            // As SQLite: a missing value is the smallest, so first going up and last going down.
            val up: Comparator<Film> = when (key) {
                ItemSortBy.SORT_NAME -> compareBy { it.name.lowercase() }
                ItemSortBy.DATE_CREATED -> compareBy { it.created }
                ItemSortBy.PRODUCTION_YEAR -> compareBy(nullsFirst()) { it.year }
                ItemSortBy.COMMUNITY_RATING -> compareBy(nullsFirst()) { it.rating }
                ItemSortBy.RUNTIME -> compareBy(nullsFirst()) { it.runtimeMinutes }
                else -> error("no sort by $key in this fake")
            }
            comparator = comparator.then(if (order.getOrNull(i) == SortOrder.DESCENDING) up.reversed() else up)
        }
        val sorted = films.sortedWith(comparator.thenBy { noise.getValue(it.id) })
        val start = query["startIndex"] as? Int ?: 0
        val limit = query["limit"] as? Int ?: sorted.size
        val items = sorted.drop(start).take(limit).joinToString(",") { film ->
            buildString {
                append("""{"Id":"${film.id}","Name":"${film.name}","SortName":"${film.name.lowercase()}",""")
                append(""""Type":"Movie","DateCreated":"${film.created}"""")
                film.year?.let { append(""","ProductionYear":$it""") }
                film.rating?.let { append(""","CommunityRating":$it""") }
                film.runtimeMinutes?.let { append(""","RunTimeTicks":${it * 600_000_000L}""") }
                append("}")
            }
        }
        val total = if (query["enableTotalRecordCount"] == false) 0 else sorted.size
        return """{"Items":[$items],"TotalRecordCount":$total,"StartIndex":$start}"""
    }

    /** Pages through [query] as the grid does, moving the server's list after the first and second pages. */
    private fun pageThrough(query: LibraryQuery): Pair<List<MediaItem>, Set<UUID>> = runBlocking {
        val repository = server.newRepository()
        val first = repository.firstPage(query, limit = PAGE)
        assertTrue(first.serverOrder)
        var loaded = first.items
        // The first page comes uncounted; the list is counted apart, as the grid does after its first row.
        var total = first.total ?: repository.serverPosition(query)
        val removed = mutableSetOf<UUID>()
        var pages = 1
        while (loaded.size < total) {
            when (pages) {
                // A new film at the top of every order (the end of Z to A), and one gone from above the boundary.
                1 -> {
                    films += Film(id(FILMS), "!New", base.plusSeconds(10_000_000L), 2030, 10f, 300)
                    removed += loaded[10].id
                }

                // Two gone from above the boundary: the rest moves up two places.
                2 -> removed += listOf(loaded[20].id, loaded[100].id)
            }
            films.removeAll { it.id in removed }
            val page = repository.page(query, start = loaded.size, limit = PAGE, serverOrder = true, total = total)
            val next = NixieLibraryPaging.appended(loaded, page.items)
            total = page.total ?: total
            pages++
            if (next.size == loaded.size) break
            loaded = next
        }
        loaded to removed
    }

    private fun check(query: LibraryQuery) {
        val stayed = films.map { it.id }.toSet()
        val (loaded, removed) = pageThrough(query)
        val ids = loaded.map { it.id }
        assertEquals("${query.sort} repeated a title", ids.size, ids.toSet().size)
        val missing = stayed - removed - ids.toSet()
        assertTrue("${query.sort} (descending ${query.descending}) skipped ${missing.size} titles", missing.isEmpty())
    }

    private val movies = LibraryQuery(LibraryKind.Movies)

    @Test
    fun `by title, A to Z`() = check(movies)

    @Test
    fun `by title, Z to A`() = check(movies.copy(descending = true))

    @Test
    fun `newest first`() = check(movies.copy(sort = LibrarySort.DateAdded, descending = true))

    @Test
    fun `latest year first, with years missing and tied`() =
        check(movies.copy(sort = LibrarySort.Year, descending = true))

    @Test
    fun `highest rating first`() = check(movies.copy(sort = LibrarySort.Rating, descending = true))

    @Test
    fun `longest first`() = check(movies.copy(sort = LibrarySort.Runtime, descending = true))

    // Nixie (TV-S1 on a real server): the oldest, lowest and shortest first page on the server too.

    @Test
    fun `oldest added first`() = check(movies.copy(sort = LibrarySort.DateAdded))

    @Test
    fun `oldest year first, with years missing and tied`() = check(movies.copy(sort = LibrarySort.Year))

    @Test
    fun `lowest rating first`() = check(movies.copy(sort = LibrarySort.Rating))

    @Test
    fun `shortest first`() = check(movies.copy(sort = LibrarySort.Runtime))

    @Test
    fun `unwatched, by title`() = check(movies.copy(unwatchedOnly = true))

    private companion object {
        const val FILMS = 300
        const val PAGE = 70
    }
}
