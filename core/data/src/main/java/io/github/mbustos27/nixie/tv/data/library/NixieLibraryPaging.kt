// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.library

import io.github.glacier_jellyfin.androidtv.core.data.media.AlphabetLetters
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryScope
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import java.util.UUID
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

/**
 * TV-S1 (DESIGN-RULES 10): a page from the server, timed for the detailed log: [requestMs] until its answer was in,
 * [parseMs] from there to its cards (the JSON, then each title made a card and checked against the age limit).
 */
data class PageTiming(val requestMs: Long, val parseMs: Long) {
    companion object {
        private const val NANOS_PER_MS = 1_000_000

        /** From [sent] to [received] (0 when no answer was marked: all of it is the request) to [done], in nanoseconds. */
        fun of(
            sent: Long,
            received: Long,
            done: Long,
        ): PageTiming {
            val answered = if (received == 0L) done else received
            return PageTiming((answered - sent) / NANOS_PER_MS, (done - answered) / NANOS_PER_MS)
        }
    }
}

/** The server's order for a list: what to sort by, each with its direction. */
data class ServerSort(val by: List<ItemSortBy>, val order: List<SortOrder>)

/**
 * TV-S1 (the owner's TV session, 2026-11-21): a big library's first load. Glacier lists every title of a library (its
 * index) before it shows the first page, because it puts the titles in order itself; on a server with tens of
 * thousands of films that took the grid a long while to fill. Desktop Nixie reads a big library a page at a time
 * (1.4.1). Here, while a library's index is not in memory (fetching it, or reading it from the device, takes seconds),
 * its pages come straight from the server in the server's order, a page at a time with only the fields a card needs,
 * and the index follows in the background with fewer requests at once (it is still needed by the A–Z rail and for the
 * next open).
 *
 * Only where the server's order is the app's order, or close to it: movies and shows by title (either way), or by date
 * added, year, rating or length (either way: the titles that lack a value come last going down and first going up, as
 * the server has them and ListArrangement too). Not music (a song's sort name starts with its disc and track; artists
 * and albums go in groups by name). Unwatched and favourites too: the server filters them (TV-S1 on a real server: an
 * Unwatched or oldest-first view of about 25,000 films took 15 s on the test instance as Glacier listed it whole, half
 * a minute on a slow box, every time; from the server 0.2 to 0.4 s).
 */
object NixieLibraryPaging {

    /**
     * Titles per request of an index someone waits for (a jump, a list in the app's order): small, so each answer comes
     * quickly on a big server and the count on screen moves.
     */
    const val WAITED_PAGE = 500

    /** Requests at once for an index someone waits for. */
    const val WAITED_PARALLEL = 4

    /** Titles per request of an index nobody waits for (the background index): big, so the server is asked less often. */
    const val BACKGROUND_PAGE = 2000

    /** Requests at once while an index comes in the background: the pages the grid asks for meanwhile stay quick. */
    const val BACKGROUND_PARALLEL = 2

    /**
     * What an A–Z jump to [letter] asks the server (see LibraryRepository.serverPosition): the titles whose sort name
     * comes before [Bounds.below] (none asked when null), counted from the start, or, [Bounds.fromTheEnd], taken from
     * the list's size (Z to A). The server's sort names are lower case, so the bounds are too.
     */
    fun letterBounds(letter: Char, descending: Boolean): Bounds {
        val rank = AlphabetLetters.indexOf(letter.uppercaseChar()).coerceAtLeast(0)
        // '#' (rank 0) is everything before "a"; a letter's titles start at it and end before the next one.
        val from = if (rank == 0) null else letter.lowercaseChar().toString()
        val next = AlphabetLetters.getOrNull(rank + 1)?.lowercaseChar()?.toString() ?: AFTER_Z
        return if (descending) Bounds(below = next, fromTheEnd = true) else Bounds(below = from, fromTheEnd = false)
    }

    /**
     * Where the row that holds [index] starts in a grid of [columns], in a list from the server (by title: no groups, so
     * every row holds [columns] titles from the top). TV-S13: with a [phase], rows start at the titles whose place is
     * [phase] more than a multiple of [columns] (a list that started at a letter's first title, see [letterStart]); the
     * first row then holds [phase] titles.
     */
    fun rowStartOf(
        index: Int,
        columns: Int,
        phase: Int = 0,
    ): Int {
        val perRow = columns.coerceAtLeast(1)
        val shift = phase.mod(perRow)
        val at = index.coerceAtLeast(0)
        if (at < shift) return 0
        return (at - shift) / perRow * perRow + shift
    }

    /** See [letterBounds]. */
    data class Bounds(val below: String?, val fromTheEnd: Boolean)

    /**
     * TV-S13 (a remote server, about 1 s a request): an A–Z jump's first row from one request, the page that starts at
     * [letter] itself: the titles whose sort name is [from] or after it (A to Z), or comes before [below] (Z to A, in the
     * list's own order), as the server filters them; their place in the list ([letterBounds]) is asked beside it and
     * comes after the first row. Null where the letter's titles start the list (# going up, Z going down).
     */
    fun letterStart(letter: Char, descending: Boolean): LetterStart? {
        val bounds = letterBounds(letter, descending)
        return when {
            !descending -> bounds.below?.let { LetterStart(from = it, below = null) }
            bounds.below == AFTER_Z -> null
            else -> LetterStart(from = null, below = bounds.below)
        }
    }

    /** See [letterStart]: one of the two is set. */
    data class LetterStart(val from: String?, val below: String?)

    /** Sorts after every lower-case letter ('{' follows 'z'). */
    private const val AFTER_Z = "{"

    /**
     * TV-S13: titles asked by their ids in one request, when a page comes in the app's order (its index in memory): the
     * grid's page of 70, or a page before a jump's (up to a row more), is one request, not two at once (Glacier: 40). About
     * 2.7 kB of address, well under what servers and proxies take.
     */
    const val IDS_PER_REQUEST = 80

    /** Indexes kept in memory for the session, the last used first (about 2.5 MB each for 25,000 films). */
    const val INDEXES_KEPT = 4

    /** Titles shown in the server's order after which the A–Z rail's index is fetched in the background. */
    const val INDEX_AFTER_TITLES = 280

    /**
     * Where a jump to [letter] lands when no title is filed under it: the first title after it in the list's order
     * ([letterAt] the rail letter of each of the [size] titles, [descending] Z to A), else the last title.
     */
    fun nearestLetter(
        letter: Char,
        descending: Boolean,
        size: Int,
        letterAt: (Int) -> Char?,
    ): Int {
        val wanted = AlphabetLetters.indexOf(letter)
        val after = (0 until size).firstOrNull { i ->
            val rank = letterAt(i)?.let(AlphabetLetters::indexOf) ?: return@firstOrNull false
            if (descending) rank < wanted else rank > wanted
        }
        return after ?: (size - 1).coerceAtLeast(0)
    }

    /** The server's order for [query], or null where it is not the app's (see the class). */
    fun serverSort(query: LibraryQuery): ServerSort? {
        if (query.kind != LibraryKind.Movies && query.kind != LibraryKind.Shows) return null
        if (query.scope != LibraryScope.All && query.scope != LibraryScope.Collections) return null
        val direction = if (query.orderDescending) SortOrder.DESCENDING else SortOrder.ASCENDING
        // Every order ends with the sort name, then the date added (as Glacier's own pages do): titles that tie keep
        // one order from page to page, so a page boundary never repeats or skips one. The server sorts by no id.
        if (query.sort == LibrarySort.Title) {
            return ServerSort(listOf(ItemSortBy.SORT_NAME, ItemSortBy.DATE_CREATED), listOf(direction, direction))
        }
        // Grouped, either way; inside a value, A to Z.
        val by = when (query.sort) {
            LibrarySort.DateAdded -> ItemSortBy.DATE_CREATED
            LibrarySort.Year -> ItemSortBy.PRODUCTION_YEAR
            LibrarySort.Rating -> ItemSortBy.COMMUNITY_RATING
            LibrarySort.Runtime -> ItemSortBy.RUNTIME
            else -> return null
        }
        return ServerSort(
            listOf(by, ItemSortBy.SORT_NAME, ItemSortBy.DATE_CREATED),
            listOf(direction, SortOrder.ASCENDING, SortOrder.ASCENDING),
        )
    }

    /** Whether [query]'s pages may come from the server while its index is not in memory. */
    fun pagesOnServer(query: LibraryQuery): Boolean = serverSort(query) != null

    /**
     * The list's size after a page from the server: [known] (the first page's count), or where the list ends when the
     * page came back short ([start] + [received] of [asked]): titles removed meanwhile, so the grid stops asking there.
     */
    fun totalAfter(
        known: Int,
        start: Int,
        asked: Int,
        received: Int,
    ): Int = if (received < asked) start + received else maxOf(known, start + received)

    /**
     * Titles a page from the server starts before the end of what is loaded: the server's list moves while the grid
     * pages through it, and a title removed before the end would make the next page skip one.
     */
    const val OVERLAP = 5

    /**
     * [loaded] and then [page], without the titles [loaded] already holds: a title added before the end moves the rest
     * down a place, and the page's overlap ([OVERLAP]) repeats titles. The grid's keys must stay unique.
     */
    fun appended(loaded: List<MediaItem>, page: List<MediaItem>): List<MediaItem> {
        if (page.isEmpty()) return loaded
        val ids = HashSet<UUID>(loaded.size * 2)
        loaded.forEach { ids += it.id }
        val fresh = page.filter { ids.add(it.id) }
        return if (fresh.isEmpty()) loaded else loaded + fresh
    }
}
