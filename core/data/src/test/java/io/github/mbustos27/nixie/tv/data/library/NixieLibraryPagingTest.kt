// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.library

import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryScope
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TV-S1: which lists page on the server while their index is not on the device, and in what order. */
class NixieLibraryPagingTest {

    private val movies = LibraryQuery(LibraryKind.Movies)

    @Test
    fun `movies and shows by title page on the server by sort name, either way`() {
        assertEquals(
            ServerSort(
                listOf(ItemSortBy.SORT_NAME, ItemSortBy.DATE_CREATED),
                listOf(SortOrder.ASCENDING, SortOrder.ASCENDING),
            ),
            NixieLibraryPaging.serverSort(movies),
        )
        assertEquals(
            ServerSort(
                listOf(ItemSortBy.SORT_NAME, ItemSortBy.DATE_CREATED),
                listOf(SortOrder.DESCENDING, SortOrder.DESCENDING),
            ),
            NixieLibraryPaging.serverSort(LibraryQuery(LibraryKind.Shows, descending = true)),
        )
        assertTrue(NixieLibraryPaging.pagesOnServer(movies.copy(scope = LibraryScope.Collections)))
        assertTrue(NixieLibraryPaging.pagesOnServer(movies.copy(favoritesOnly = true)))
    }

    @Test
    fun `newest, latest, highest and longest first page on the server, A to Z inside a value`() {
        val expected = mapOf(
            LibrarySort.DateAdded to ItemSortBy.DATE_CREATED,
            LibrarySort.Year to ItemSortBy.PRODUCTION_YEAR,
            LibrarySort.Rating to ItemSortBy.COMMUNITY_RATING,
            LibrarySort.Runtime to ItemSortBy.RUNTIME,
        )
        expected.forEach { (sort, by) ->
            assertEquals(
                ServerSort(
                    listOf(by, ItemSortBy.SORT_NAME, ItemSortBy.DATE_CREATED),
                    listOf(SortOrder.DESCENDING, SortOrder.ASCENDING, SortOrder.ASCENDING),
                ),
                NixieLibraryPaging.serverSort(movies.copy(sort = sort, descending = true)),
            )
        }
    }

    @Test
    fun `oldest, lowest and shortest first page on the server too, as it has them`() {
        val expected = mapOf(
            LibrarySort.DateAdded to ItemSortBy.DATE_CREATED,
            LibrarySort.Year to ItemSortBy.PRODUCTION_YEAR,
            LibrarySort.Rating to ItemSortBy.COMMUNITY_RATING,
            LibrarySort.Runtime to ItemSortBy.RUNTIME,
        )
        expected.forEach { (sort, by) ->
            assertEquals(
                ServerSort(
                    listOf(by, ItemSortBy.SORT_NAME, ItemSortBy.DATE_CREATED),
                    listOf(SortOrder.ASCENDING, SortOrder.ASCENDING, SortOrder.ASCENDING),
                ),
                NixieLibraryPaging.serverSort(movies.copy(sort = sort, descending = false)),
            )
        }
    }

    @Test
    fun `music and genres wait for the index, unwatched movies page on the server`() {
        assertFalse(NixieLibraryPaging.pagesOnServer(LibraryQuery(LibraryKind.Music)))
        assertFalse(NixieLibraryPaging.pagesOnServer(LibraryQuery(LibraryKind.Music, scope = LibraryScope.Songs)))
        assertFalse(NixieLibraryPaging.pagesOnServer(movies.copy(scope = LibraryScope.Genres)))
        // The server filters the watched ones itself, grouping into collections on or off (TV-S1 on a real server).
        assertTrue(NixieLibraryPaging.pagesOnServer(movies.copy(unwatchedOnly = true)))
        assertTrue(NixieLibraryPaging.pagesOnServer(movies.copy(unwatchedOnly = true, groupCollections = false)))
        assertTrue(NixieLibraryPaging.pagesOnServer(movies.copy(unwatchedOnly = true, sort = LibrarySort.Year)))
    }

    @Test
    fun `a short page ends the list where it came back short`() {
        assertEquals(10_000, NixieLibraryPaging.totalAfter(10_000, start = 70, asked = 70, received = 70))
        assertEquals(9_990, NixieLibraryPaging.totalAfter(10_000, start = 9_940, asked = 70, received = 50))
        assertEquals(140, NixieLibraryPaging.totalAfter(10_000, start = 140, asked = 70, received = 0))
        // Titles added meanwhile are read too.
        assertEquals(10_070, NixieLibraryPaging.totalAfter(10_000, start = 10_000, asked = 70, received = 70))
    }

    @Test
    fun `an A-Z jump asks for the titles before its letter, lower case as the server's sort names`() {
        fun bounds(letter: Char, descending: Boolean) = NixieLibraryPaging.letterBounds(letter, descending)
        assertEquals(NixieLibraryPaging.Bounds(below = null, fromTheEnd = false), bounds('#', false))
        assertEquals(NixieLibraryPaging.Bounds(below = "a", fromTheEnd = false), bounds('A', false))
        assertEquals(NixieLibraryPaging.Bounds(below = "m", fromTheEnd = false), bounds('m', false))
        // Z to A: the titles from the next letter on come first, so it lands after them (counted from the end).
        assertEquals(NixieLibraryPaging.Bounds(below = "n", fromTheEnd = true), bounds('M', true))
        assertEquals(NixieLibraryPaging.Bounds(below = "{", fromTheEnd = true), bounds('Z', true))
        assertEquals(NixieLibraryPaging.Bounds(below = "a", fromTheEnd = true), bounds('#', true))
    }

    @Test
    fun `a page's time splits into its request and its parsing`() {
        val ms = 1_000_000L
        assertEquals(PageTiming(640, 120), PageTiming.of(sent = 10 * ms, received = 650 * ms, done = 770 * ms))
        // No answer marked: all of it is the request.
        assertEquals(PageTiming(760, 0), PageTiming.of(sent = 10 * ms, received = 0, done = 770 * ms))
    }

    @Test
    fun `a row of a list by title starts at a multiple of the columns`() {
        assertEquals(0, NixieLibraryPaging.rowStartOf(-20, 7))
        assertEquals(0, NixieLibraryPaging.rowStartOf(6, 7))
        assertEquals(700, NixieLibraryPaging.rowStartOf(706, 7))
        assertEquals(528, NixieLibraryPaging.rowStartOf(530, 6))
    }

    @Test
    fun `TV-S13 - after a jump, rows start at its letter, and the list's first row holds the rest`() {
        // M at 1,200 in rows of 7: 1,200 is 3 past a multiple of 7, so rows start at 3, 10, ... 1,193, 1,200.
        assertEquals(1130, NixieLibraryPaging.rowStartOf(1130, 7, phase = 3))
        assertEquals(1193, NixieLibraryPaging.rowStartOf(1199, 7, phase = 3))
        assertEquals(1200, NixieLibraryPaging.rowStartOf(1200, 7, phase = 3))
        assertEquals(3, NixieLibraryPaging.rowStartOf(5, 7, phase = 3))
        // The titles before the phase are the list's first row.
        assertEquals(0, NixieLibraryPaging.rowStartOf(2, 7, phase = 3))
        assertEquals(0, NixieLibraryPaging.rowStartOf(-40, 7, phase = 3))
        assertEquals(700, NixieLibraryPaging.rowStartOf(706, 7, phase = 7))
    }

    @Test
    fun `TV-S13 - a jump's page starts at the letter going up, before the next one going down`() {
        fun start(letter: Char, descending: Boolean) = NixieLibraryPaging.letterStart(letter, descending)
        assertEquals(NixieLibraryPaging.LetterStart(from = "m", below = null), start('M', false))
        assertEquals(NixieLibraryPaging.LetterStart(from = null, below = "n"), start('M', true))
        assertEquals(NixieLibraryPaging.LetterStart(from = null, below = "a"), start('#', true))
        // Where the list starts with the letter's titles there is nothing to ask by letter.
        assertEquals(null, NixieLibraryPaging.letterStart('#', false))
        assertEquals(null, NixieLibraryPaging.letterStart('Z', true))
    }
}
