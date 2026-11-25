// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.library

import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryScope
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tv-home-two-rows (TV-S11): the top bar's Collections page lists every collection on the server (box sets), A to Z,
 * from the server's own order a page at a time, as a library's grid does: its first request asks only for its first
 * screen, uncounted, and the next pages follow it. The real [io.github.glacier_jellyfin.androidtv.core.data.media
 * .LibraryRepository] against a fake server with 492 collections, the test instance's number.
 */
class CollectionsPageTest {

    private val cache: File = Files.createTempDirectory("nixie-collections").toFile()

    @After
    fun tearDown() {
        cache.deleteRecursively()
    }

    private val requests: MutableList<Map<String, Any?>> = Collections.synchronizedList(mutableListOf())

    private val sets = (1..SETS).map { n -> UUID.nameUUIDFromBytes("set-$n".toByteArray()) to "Set %03d".format(n) }

    private val server = FakeLibraryServer(cache) { query ->
        requests += query
        val start = query["startIndex"] as? Int ?: 0
        val limit = query["limit"] as? Int ?: SETS
        val page = sets.drop(start).take(limit).joinToString(",") { (id, name) ->
            """{"Id":"$id","Name":"$name","SortName":"${name.lowercase()}","Type":"BoxSet","ChildCount":4}"""
        }
        val total = if (query["enableTotalRecordCount"] == true) SETS else 0
        """{"Items":[$page],"TotalRecordCount":$total,"StartIndex":$start}"""
    }

    @Test
    fun `the Collections page is every collection on the server, A to Z, with its own sort and toggles`() {
        val query = LibraryQuery.nixieCollections()
        assertEquals(LibraryScope.Collections, query.scope)
        assertNull("every library's: no parent", query.libraryId)
        assertEquals(LibrarySort.Title, query.sort)
        assertEquals(false, query.descending)
        assertEquals(LibraryQuery.NIXIE_COLLECTIONS_KEY, query.sortKey)
        assertTrue("not a movie library's sort", query.sortKey != LibraryQuery(LibraryKind.Movies).sortKey)
        assertTrue("its pages come from the server", NixieLibraryPaging.pagesOnServer(query))
    }

    @Test
    fun `its first page asks the server for the first screen only, and the next page follows its order`() =
        runBlocking {
            val repository = server.newRepository()
            val query = LibraryQuery.nixieCollections()
            val first = repository.firstPage(query, limit = FIRST)

            assertTrue(first.serverOrder)
            assertEquals(sets.take(FIRST).map { it.first }, first.items.map { it.id })
            assertNull("uncounted", first.total)
            val asked = requests.single()
            assertEquals(listOf(BaseItemKind.BOX_SET), asked["includeItemTypes"])
            assertNull("every collection on the server", asked["parentId"])
            assertEquals(true, asked["recursive"])
            assertEquals(FIRST, asked["limit"])
            assertEquals(0, asked["startIndex"])
            assertEquals(false, asked["enableTotalRecordCount"])
            assertEquals(listOf(ItemSortBy.SORT_NAME, ItemSortBy.DATE_CREATED), asked["sortBy"])
            assertEquals(listOf(SortOrder.ASCENDING, SortOrder.ASCENDING), asked["sortOrder"])
            assertNull("collections are not grouped into collections", asked["collapseBoxSetItems"])

            val next = repository.page(query, start = FIRST, limit = PAGE, serverOrder = true, total = null)
            val second = requests.last()
            assertEquals("from a few titles back", FIRST - NixieLibraryPaging.OVERLAP, second["startIndex"])
            assertEquals(false, second["enableTotalRecordCount"])
            assertEquals(sets[FIRST - NixieLibraryPaging.OVERLAP].first, next.items.first().id)
            assertEquals(2, requests.size)
        }

    private companion object {
        /** Collections on the test instance. */
        const val SETS = 492

        /** The page's first request (NixieCollectionsPage.FIRST_PAGE) and the next ones. */
        const val FIRST = 24
        const val PAGE = 70
    }
}
