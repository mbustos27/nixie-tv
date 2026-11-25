// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryPage
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.library.LibraryState
import io.github.mbustos27.nixie.tv.data.library.NixieLibraryPaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * TV-S1 on a real server (2026-11-22): what a list in the server's order asks the server apart from its pages, so no
 * whole-library work stands between a key press and its answer (DESIGN-RULES 3). Its first page comes uncounted and is
 * counted after it; an A–Z jump asks the server where the letter starts (limit 0) and loads the page there, in place of
 * the whole index (tens of pages on a server with tens of thousands of films). Against a real Jellyfin of about 25,000
 * films: the first page 821 ms counted, 436 ms not; a first jump 13.6 s with the index, 1.2 s by offset.
 *
 * TV-S13 (a remote server, about a second a request): a jump waited on two requests in a row, its place and then its
 * page (over 2 s on a box). It now lands from one, the letter's own page ([jump]); its place and the rows above it come
 * after the landing.
 */
class NixieServerList(
    private val scope: CoroutineScope,
    private val repository: LibraryRepository,
    private val state: MutableStateFlow<LibraryState>,
    // TV-S13: the grid's page, a jump's first.
    private val pageSize: Int,
) {
    private var countJob: Job? = null

    /** Once [firstScreen] is done (its first row drawn), a list from the server is counted, for the header and the end. */
    fun countAfter(firstScreen: Job?) {
        countJob?.cancel()
        val query = state.value.query
        countJob = scope.launch {
            firstScreen?.join()
            val current = state.value
            if (current.query != query || !current.serverOrder || current.total != null) return@launch
            val count = runSuspendCatching { repository.serverPosition(query) }
                .onFailure { Log.w(TAG, "Counting the library failed", it) }
                .getOrNull() ?: return@launch
            state.update { if (it.query == query && it.total == null) it.copy(total = count) else it }
        }
    }

    /** The list was opened anew: its count, and a jump's place, are no longer wanted. */
    fun cancel() {
        countJob?.cancel()
        placeJob?.cancel()
    }

    private var placeJob: Job? = null

    /**
     * TV-S13: the list starts anew at [letter]'s first title, from one request (the letter's page), its
     * place not known yet ([LibraryState.anchor]); the grid lands on that title. True when it landed; false when no title
     * is filed from the letter on (nothing changed); null when the page did not come.
     */
    private suspend fun landOn(letter: Char): Boolean? {
        val query = state.value.query
        state.update { it.copy(loading = true, failed = false) }
        val page = runSuspendCatching { repository.page(query, 0, pageSize, fromLetter = letter) }
            .onFailure { Log.w(TAG, "Loading library failed", it) }
            .getOrNull()
        state.update {
            when {
                it.query != query -> it

                page == null -> it.copy(loading = false, failed = true)

                page.items.isEmpty() -> it.copy(loading = false)

                else -> it.copy(
                    items = page.items,
                    offset = 0,
                    anchor = letter,
                    rowPhase = 0,
                    loading = false,
                    serverOrder = true,
                    jumpTo = 0,
                )
            }
        }
        return page?.items?.isNotEmpty()
    }

    /**
     * TV-S13: once a jump's page has landed ([LibraryState.anchor] = [letter]), where it sits in the list, asked of the
     * server apart (limit 0): the list then knows its [LibraryState.offset], loads the titles before it, and its rows
     * stay where they are (they start at the letter: [LibraryState.rowPhase] for a grid of [columns]); then [placed].
     */
    private fun place(
        letter: Char,
        columns: Int,
        placed: () -> Unit = {},
    ) {
        placeJob?.cancel()
        val query = state.value.query
        placeJob = scope.launch {
            val position = letterPosition(query, letter) ?: return@launch
            var known = false
            state.update {
                if (it.query == query && it.anchor == letter) {
                    known = true
                    it.copy(offset = position, anchor = null, rowPhase = position.mod(columns.coerceAtLeast(1)))
                } else {
                    it
                }
            }
            if (known) placed()
        }
    }

    /**
     * TV-S13: whether [now] is still the list [before] was (a page asked for [before] may join it): the same query and
     * start, or a jump's page that learned its place meanwhile ([place]).
     */
    fun sameList(before: LibraryState, now: LibraryState): Boolean {
        if (now.query != before.query) return false
        if (now.offset == before.offset && now.anchor == before.anchor) return true
        val placed = before.anchor != null && now.anchor == null
        return placed && now.items.firstOrNull()?.id == before.items.firstOrNull()?.id
    }

    /** Whether [current] is in the server's order and its index is not in memory (so finding a place would fetch it). */
    suspend fun withoutIndex(current: LibraryState): Boolean =
        current.serverOrder && !runSuspendCatching { repository.indexReady(current.query) }.getOrDefault(false)

    /** Whether an A–Z jump in [current] goes by the server's offset: a list by title without its index in memory. */
    suspend fun jumpsByOffset(current: LibraryState): Boolean =
        current.query.sort == LibrarySort.Title && withoutIndex(current)

    /** Where a jump to [letter] lands in [query]'s list in the server's order; null when the server did not say. */
    private suspend fun letterPosition(query: LibraryQuery, letter: Char): Int? =
        runSuspendCatching { repository.serverPosition(query, letter, state.value.total) }
            .onFailure { Log.w(TAG, "A-Z jump failed", it) }
            .getOrNull()

    /**
     * TV-S13: an A–Z jump in a list in the server's order, its index not in memory, for a grid of [columns]: the
     * letter's own page, in one request, run by [asLoad] as the list's load; then its place and, once known, the rows
     * above it ([above]). The list's start is its first page ([load] from a place); a letter with no title from it on
     * goes by the server's offset, as before (it lands on the last title). How it landed, for the log; null when it did
     * not.
     */
    suspend fun jump(
        letter: Char,
        columns: Int,
        load: suspend (Int) -> Boolean,
        asLoad: suspend (suspend () -> Unit) -> Unit,
        above: () -> Unit,
    ): String? {
        val query = state.value.query
        if (NixieLibraryPaging.letterStart(letter, query.orderDescending) == null) {
            return if (holds(0) || load(0)) "to the list's start".also { land(0) } else null
        }
        var landed: Boolean? = null
        asLoad { landed = landOn(letter) }
        if (landed == true) {
            place(letter, columns, above)
            return "by the letter's page"
        }
        val position = if (landed == false) letterPosition(query, letter) else null
        val start = NixieLibraryPaging.rowStartOf((position ?: 0) - ROWS_BEFORE * columns, columns)
        val loaded = position != null && (holds(position) || load(start))
        return if (loaded) "by the server's offset".also { land(checkNotNull(position)) } else null
    }

    /** The grid lands on place [index] of the list (a jump's landing; the screen scrolls there). */
    private fun land(index: Int) = state.update {
        it.copy(jumpTo = (index - it.offset).coerceIn(0, (it.items.size - 1).coerceAtLeast(0)))
    }

    /**
     * TV-S1 (LibraryViewModel's until TV-S13): the list starts anew at [start] (a jump's place); [fromServer], from the
     * server in its order (a jump by the server's offset), with no overlap; its rows from the top. False when the page
     * did not come.
     */
    suspend fun loadFrom(start: Int, fromServer: Boolean): Boolean {
        val before = state.value
        state.update { it.copy(loading = true, failed = false) }
        val page = runSuspendCatching {
            repository.page(before.query, start, pageSize, fromServer, before.total, overlap = false)
        }.onFailure { Log.w(TAG, "Loading library failed", it) }.getOrNull()
        state.update {
            when {
                page == null -> it.copy(loading = false, failed = true)

                it.query != before.query -> it

                else -> it.copy(
                    items = page.items,
                    offset = start,
                    total = page.total ?: it.total,
                    loading = false,
                    serverOrder = page.serverOrder,
                    anchor = null,
                    rowPhase = 0,
                )
            }
        }
        return page != null
    }

    /** TV-S13: the next page of a jump's list whose place is not known yet, from its letter; null for any other list. */
    suspend fun nextFromLetter(current: LibraryState): LibraryPage? {
        val letter = current.anchor ?: return null
        return repository.page(current.query, current.items.size, pageSize, fromLetter = letter)
    }

    /** Whether the list holds position [index] now (a jump there needs no page); TV-S13: not before its place is known. */
    private fun holds(index: Int): Boolean =
        state.value.let { it.anchor == null && index >= it.offset && index < it.offset + it.items.size }

    /**
     * Where the titles before [current]'s first one start, [limit] back at most, at the start of a row of [columns]: in
     * the server's order rows go from the top (no groups by title), or from a jump's letter (TV-S13: its phase); in the
     * app's, the index says.
     */
    suspend fun rowStartBefore(
        current: LibraryState,
        limit: Int,
        columns: Int,
    ): Int = if (current.serverOrder) {
        NixieLibraryPaging.rowStartOf(current.offset - limit, columns, current.rowPhase)
    } else {
        repository.rowStart(current.query, (current.offset - limit).coerceAtLeast(0), columns)
    }

    private companion object {
        const val TAG = "Library"

        /** Rows kept above the title a jump by the server's offset lands on (LibraryViewModel's JUMP_ROWS_BEFORE). */
        const val ROWS_BEFORE = 3
    }
}
