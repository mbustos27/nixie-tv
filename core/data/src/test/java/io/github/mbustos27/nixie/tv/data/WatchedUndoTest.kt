// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.settings.Choice
import io.github.glacier_jellyfin.androidtv.core.data.settings.HomeRow
import io.github.glacier_jellyfin.androidtv.core.data.settings.HomeRowChoice
import io.github.glacier_jellyfin.androidtv.core.data.settings.HomeSettings
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nixie: Undo after marking a show or season watched, and the libraries in the server's order. */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchedUndoTest {

    private val show = UUID(9, 9)
    private val then = LocalDateTime.of(2026, 11, 1, 20, 0)
    private val marked = LocalDateTime.of(2026, 11, 21, 20, 0)

    private fun ep(n: Long) = UUID(5, n)

    private val before = mapOf(
        ep(1) to ResumeSnapshot(0, played = true, playCount = 1, lastPlayed = then),
        ep(2) to ResumeSnapshot(9_000_000_000, played = false, playCount = 0, lastPlayed = then),
        ep(3) to ResumeSnapshot(0, played = false, playCount = 0, lastPlayed = null),
    )

    /** What Jellyfin makes of them when the show is marked watched. */
    private val afterMarking = before.mapValues { (_, it) ->
        ResumeSnapshot(0, played = true, playCount = it.playCount + 1, lastPlayed = marked)
    }

    private class FakeServer(var state: Map<UUID, ResumeSnapshot>) : EpisodeStates {
        val restored = mutableMapOf<UUID, ResumeSnapshot>()

        override suspend fun episodes(parentId: UUID) = state

        override suspend fun restore(itemId: UUID, to: ResumeSnapshot) {
            restored[itemId] = to
        }
    }

    @Test
    fun `Undo puts every episode back as it was, resume points too, all through its time`() = runTest {
        val server = FakeServer(before)
        val undo = WatchedUndo(backgroundScope, server)
        val snapshot = undo.snapshot(show)!!
        server.state = afterMarking
        undo.open(show, snapshot)
        // Still open just before its time is up.
        runCurrent()
        advanceTimeBy(WatchedUndo.UNDO_MS - 1)
        assertTrue(undo.isOpen(show))
        assertEquals(WatchedUndoResult.Restored, undo.undo(show))
        assertEquals(before, server.restored)
        assertFalse(undo.isOpen(show))
    }

    @Test
    fun `an episode played since the marking keeps its new state`() = runTest {
        val server = FakeServer(afterMarking + (ep(3) to ResumeSnapshot(1_000, true, 1, marked.plusSeconds(5))))
        val undo = WatchedUndo(backgroundScope, server)
        undo.open(show, before)
        assertEquals(WatchedUndoResult.Partly(skipped = 1), undo.undo(show))
        assertEquals(setOf(ep(1), ep(2)), server.restored.keys)
    }

    @Test
    fun `the Undo closes after about 10 s`() = runTest {
        val server = FakeServer(afterMarking)
        val undo = WatchedUndo(backgroundScope, server)
        undo.open(show, before)
        advanceTimeBy(WatchedUndo.UNDO_MS + 1)
        assertFalse(undo.isOpen(show))
        assertEquals(WatchedUndoResult.Gone, undo.undo(show))
        assertTrue(server.restored.isEmpty())
    }

    @Test
    fun `restores only what the marking changed`() {
        val unchanged = mapOf(ep(1) to before.getValue(ep(1)))
        assertEquals(emptyMap<UUID, ResumeSnapshot>(), WatchedUndo.restores(unchanged, unchanged))
        assertEquals(before, WatchedUndo.restores(before, afterMarking))
    }

    @Test
    fun `Home's libraries follow the server's order, rows keep their places and switches`() {
        val settings = HomeSettings(
            rows = listOf(
                HomeRowChoice(HomeRow.ContinueWatching),
                HomeRowChoice(HomeRow.Latest, library = "shows", shown = false),
                HomeRowChoice(HomeRow.Favorites),
                HomeRowChoice(HomeRow.Latest, library = "movies"),
                HomeRowChoice(HomeRow.Latest),
            ),
            libraryCards = listOf(Choice("shows"), Choice("music", shown = false), Choice("movies")),
        )
        val server = listOf("movies", "music", "shows")
        val followed = with(ServerOrder) { settings.following(server) }
        assertEquals(
            listOf("ContinueWatching", "Latest:movies", "Favorites", "Latest:shows", "Latest"),
            followed.rows.map { it.id },
        )
        assertEquals(listOf(true, true, true, false, true), followed.rows.map { it.shown })
        assertEquals(listOf("movies", "music", "shows"), followed.libraryCards.map { it.id })
        assertEquals(listOf(true, false, true), followed.libraryCards.map { it.shown })
    }

    @Test
    fun `the server order is followed when it changes, noted first, never written for a profile without one`() {
        val own = HomeSettings(libraryCards = listOf(Choice("shows"), Choice("movies")))
        val server = listOf("movies", "shows")
        assertEquals(null, ServerOrder.update(HomeSettings(), NixieHomeSettings(), server))
        val (noted, nixie) = ServerOrder.update(own, NixieHomeSettings(), server)!!
        assertEquals(own, noted)
        assertEquals(server, nixie.serverOrder)
        assertEquals(null, ServerOrder.update(own, nixie, server))
        val (followed, _) = ServerOrder.update(own, NixieHomeSettings(serverOrder = server.reversed()), server)!!
        assertEquals(listOf("movies", "shows"), followed.libraryCards.map { it.id })
    }
}
