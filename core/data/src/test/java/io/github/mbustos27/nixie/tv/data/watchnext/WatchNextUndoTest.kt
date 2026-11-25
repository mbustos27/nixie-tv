// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.watchnext

import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.mbustos27.nixie.tv.data.ContinueWatchingRemovals
import io.github.mbustos27.nixie.tv.data.RemovalServer
import io.github.mbustos27.nixie.tv.data.ResumeSnapshot
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stream W: the home screen's row comes back with an Undo only when the server took it, and not when the item was
 * removed again meanwhile (ContinueWatchingRemovals.putBack).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchNextUndoTest {

    private val lastPlayed = LocalDateTime.of(2026, 11, 20, 21, 0)
    private val id = UUID(0, 1)

    private class Server(var state: ResumeSnapshot) : RemovalServer {
        var refuse = false
        var slow: CompletableDeferred<Unit>? = null

        override suspend fun snapshot(itemId: UUID) = state

        override suspend fun setPosition(itemId: UUID, positionTicks: Long) {
            if (positionTicks != 0L) {
                slow?.await()
                if (refuse) error("server down")
            }
            state = state.copy(positionTicks = positionTicks)
        }
    }

    private val item = MediaItem(
        id = id, kind = ItemKind.Movie, title = "movie", sortName = null, year = null, communityRating = null,
        officialRating = null, runtimeMinutes = null, genres = emptyList(), overview = null, parentTitle = null,
        seasonNumber = null, episodeNumber = null, progress = 0.5f, remainingMinutes = 40, unwatchedCount = null,
        isFavorite = false, played = false, childCount = null, quality = null, posterUrl = null, thumbUrl = null,
        backdropUrl = null,
    )

    private fun TestScope.setup(): Triple<Server, ContinueWatchingRemovals, List<UUID>> {
        val server = Server(ResumeSnapshot(36_000_000_000, false, 0, lastPlayed))
        val removals = ContinueWatchingRemovals(backgroundScope, server, { "s1/anna" })
        val putBack = mutableListOf<UUID>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { removals.putBack.toList(putBack) }
        return Triple(server, removals, putBack)
    }

    @Test
    fun anUndoTheServerTook_putsTheRowBack() = runTest {
        val (_, removals, putBack) = setup()
        removals.remove(item, 0)
        runCurrent()
        removals.undo(id)
        runCurrent()
        assertEquals(listOf(id), putBack)
    }

    @Test
    fun anUndoTheServerRefused_orPlayedSince_putsNothingBack() = runTest {
        val (server, removals, putBack) = setup()
        server.refuse = true
        removals.remove(item, 0)
        runCurrent()
        removals.undo(id)
        runCurrent()
        assertEquals(emptyList<UUID>(), putBack)

        // Played on another device since the removal: the server's word stands.
        server.refuse = false
        removals.remove(item, 0)
        runCurrent()
        server.state = server.state.copy(positionTicks = 1_000, playCount = 1)
        removals.undo(id)
        runCurrent()
        assertEquals(emptyList<UUID>(), putBack)
    }

    @Test
    fun removedAgainBeforeTheUndoTook_putsNothingBack() = runTest {
        val (server, removals, putBack) = setup()
        removals.remove(item, 0)
        runCurrent()
        val slow = CompletableDeferred<Unit>().also { server.slow = it }
        removals.undo(id)
        runCurrent()
        removals.remove(item, 0)
        slow.complete(Unit)
        runCurrent()
        assertEquals(emptyList<UUID>(), putBack)
    }
}
