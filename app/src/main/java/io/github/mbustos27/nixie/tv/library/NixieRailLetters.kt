// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import io.github.glacier_jellyfin.androidtv.core.data.media.AlphabetLetters
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.library.LibraryState
import io.github.mbustos27.nixie.tv.data.library.NixieLibraryPaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * TV-S1 (the review): the A–Z rail's letters for the library grid, without asking the server for a whole index on
 * every open (about 26 requests on a big library; the server usually belongs to someone else). On open they come from
 * memory or the device only. Until known, a list in the server's order offers every letter; its index comes when a
 * jump needs it (counted on screen), or in the background once the user has scrolled a few pages.
 */
class NixieRailLetters(
    private val scope: CoroutineScope,
    private val repository: LibraryRepository,
    private val state: () -> LibraryState,
    private val show: (LibraryQuery, Set<Char>) -> Unit,
) {
    private var job: Job? = null

    /** The index the jumps wait for, loaded apart from them (see [awaitIndex]). */
    private var indexLoad: Pair<LibraryQuery, Deferred<Set<Char>>>? = null

    /** Whether the rail shows the list's own letters (from its index), not every letter. */
    private var known = false

    /** A list opened anew: its letters once [firstScreen] is done (its first row drawn), from memory or the device. */
    fun open(query: LibraryQuery, firstScreen: Job?) {
        job?.cancel()
        known = false
        if (!query.railed) return
        job = scope.launch {
            firstScreen?.join()
            // TV-S13: a list in the server's order offers every letter at once; a saved index takes a slow box seconds to
            // read (6 s for 25,000 films on the emulator), and the rail stayed away meanwhile.
            if (state().serverOrder) show(query, AlphabetLetters.toSet(), known = false)
            val letters = runSuspendCatching { repository.knownLetters(query) }
                .onFailure { Log.w(TAG, "Loading A-Z letters failed", it) }
                .getOrNull()
            when {
                letters != null -> show(query, letters, known = true)

                state().serverOrder -> show(query, AlphabetLetters.toSet(), known = false)

                // The first page in the app's order brought the index: nothing more to fetch.
                else -> load(query, background = false)
            }
        }
    }

    /** After a few pages in the server's order, the rail's index comes in the background (big pages, uncounted). */
    fun whenScrolledFar() {
        val current = state()
        if (known || !current.serverOrder || job?.isActive == true) return
        if (!current.query.railed || current.items.size < NixieLibraryPaging.INDEX_AFTER_TITLES) return
        job = scope.launch { load(current.query, background = true) }
    }

    /**
     * A jump needs [query]'s index now: a fetch of it in the background gives way to the jump's, counted on screen, and
     * the jumps share one load of it, whichever of them is cancelled (a newer letter does not start it again).
     */
    suspend fun awaitIndex(query: LibraryQuery) {
        job?.takeIf { it.isActive && !known }?.cancelAndJoin()
        val load = indexLoad?.takeIf { it.first == query && !it.second.isCancelled }?.second
            ?: scope.async { repository.availableLetters(query) }.also { indexLoad = query to it }
        runSuspendCatching { load.await() }
    }

    /** The list was opened anew, or left: the index its jumps waited for stops loading. */
    fun reset() {
        indexLoad?.second?.cancel()
        indexLoad = null
    }

    /** After a jump the index is in memory: the rail shows the list's own letters. */
    fun afterJump(query: LibraryQuery) {
        if (!known) job = scope.launch { load(query, background = false) }
    }

    private suspend fun load(query: LibraryQuery, background: Boolean) {
        runSuspendCatching { repository.availableLetters(query, background) }
            .onSuccess { show(query, it, known = true) }
            .onFailure { Log.w(TAG, "Loading A-Z letters failed", it) }
    }

    private fun show(
        query: LibraryQuery,
        letters: Set<Char>,
        known: Boolean,
    ) {
        if (state().query != query) return
        this.known = known
        show(query, letters)
    }

    private companion object {
        const val TAG = "Library"
    }
}
