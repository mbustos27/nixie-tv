// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nixie: Remove from Continue Watching, with an Undo for about 10 s unless newer progress came in. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContinueWatchingRemovalsTest {

    private val lastPlayed = LocalDateTime.of(2026, 11, 20, 21, 0)

    private class FakeServer(var state: MutableMap<UUID, ResumeSnapshot>) : RemovalServer {
        val calls = mutableListOf<Pair<UUID, Long>>()
        var fail = false

        /** A slow server: while set, a position waits for it before the server takes it. */
        var slow: CompletableDeferred<Unit>? = null

        override suspend fun snapshot(itemId: UUID): ResumeSnapshot = state.getValue(itemId)

        override suspend fun setPosition(itemId: UUID, positionTicks: Long) {
            slow?.await()
            if (fail) error("server down")
            calls += itemId to positionTicks
            state[itemId] = state.getValue(itemId).copy(positionTicks = positionTicks)
        }
    }

    private fun item(n: Int) = MediaItem(
        id = UUID(0, n.toLong()),
        kind = ItemKind.Movie,
        title = "movie$n",
        sortName = null,
        year = null,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = null,
        genres = emptyList(),
        overview = null,
        parentTitle = null,
        seasonNumber = null,
        episodeNumber = null,
        progress = 0.5f,
        remainingMinutes = 40,
        unwatchedCount = null,
        isFavorite = false,
        played = false,
        childCount = null,
        quality = null,
        posterUrl = null,
        thumbUrl = null,
        backdropUrl = null,
    )

    private val a = item(1)
    private val b = item(2)
    private val c = item(3)
    private var profile: String? = "s1/anna"

    private fun TestScope.setup(): Pair<FakeServer, ContinueWatchingRemovals> {
        val server = FakeServer(
            mutableMapOf(
                a.id to ResumeSnapshot(36_000_000_000, false, 0, lastPlayed),
                b.id to ResumeSnapshot(12_000_000_000, false, 1, lastPlayed),
            ),
        )
        return server to ContinueWatchingRemovals(backgroundScope, server, { profile })
    }

    @Test
    fun `remove clears the resume point and shows an Undo card in the item's place`() = runTest {
        val (server, removals) = setup()
        removals.remove(b, index = 1)
        runCurrent()
        assertEquals(listOf(b.id to 0L), server.calls)
        assertTrue(b.id in removals.pending.value)
        val asked = removals.stamp()
        assertEquals(listOf(a, b, c), removals.visible(listOf(a, c), asked))
    }

    @Test
    fun `the Undo card goes after about 10 s and the item stays out`() = runTest {
        val (_, removals) = setup()
        val askedBefore = removals.stamp()
        removals.remove(b, index = 1)
        runCurrent()
        advanceTimeBy(ContinueWatchingRemovals.UNDO_MS + 1)
        assertFalse(b.id in removals.pending.value)
        // An answer asked for before the removal took still has it: it stays out.
        assertEquals(listOf(a, c), removals.visible(listOf(a, b, c), askedBefore))
        // A later answer is the server's word.
        assertEquals(listOf(a, b), removals.visible(listOf(a, b), removals.stamp()))
    }

    @Test
    fun `Undo puts the old resume point back`() = runTest {
        val (server, removals) = setup()
        removals.remove(a, index = 0)
        runCurrent()
        removals.undo(a.id)
        runCurrent()
        assertEquals(listOf(a.id to 0L, a.id to 36_000_000_000), server.calls)
        assertFalse(a.id in removals.pending.value)
        assertEquals(listOf(a, c), removals.visible(listOf(a, c), removals.stamp()))
    }

    @Test
    fun `a card put back stays in its place until an answer asked after the Undo`() = runTest {
        val (_, removals) = setup()
        removals.remove(b, index = 1)
        runCurrent()
        val askedWhileRemoved = removals.stamp()
        removals.undo(b.id)
        runCurrent()
        assertEquals(listOf(a, b, c), removals.visible(listOf(a, c), askedWhileRemoved))
        assertEquals(listOf(a, c), removals.visible(listOf(a, c), removals.stamp()))
    }

    @Test
    fun `remove, Undo and remove again on a slow server keeps one card and the first resume point`() = runTest {
        val (server, removals) = setup()
        removals.remove(a, index = 0)
        runCurrent()
        // The Undo's trip hangs on the slow server, and the card is removed again meanwhile.
        val slow = CompletableDeferred<Unit>()
        server.slow = slow
        removals.undo(a.id)
        runCurrent()
        removals.remove(a, index = 0)
        runCurrent()
        slow.complete(Unit)
        server.slow = null
        runCurrent()
        // Once in the row (as its Undo card), never twice: the row's keys must stay unique.
        assertEquals(listOf(a, c), removals.visible(listOf(c), removals.stamp()))
        assertEquals(listOf(a, c), removals.visible(listOf(a, c), 0))
        // The second Undo puts back the resume point the item had before the first removal.
        removals.undo(a.id)
        runCurrent()
        assertEquals(a.id to 36_000_000_000, server.calls.last())
        assertEquals(36_000_000_000, server.state.getValue(a.id).positionTicks)
    }

    @Test
    fun `Undo leaves it alone when newer progress came in`() = runTest {
        val (server, removals) = setup()
        val notices = mutableListOf<RemovalNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { removals.notices.toList(notices) }
        removals.remove(a, index = 0)
        runCurrent()
        // Played on another device meanwhile.
        server.state[a.id] = ResumeSnapshot(1_000_000_000, false, 0, lastPlayed.plusMinutes(5))
        removals.undo(a.id)
        runCurrent()
        assertEquals(listOf(a.id to 0L), server.calls)
        assertEquals(listOf(RemovalNotice.ChangedSince), notices)
    }

    @Test
    fun `Undo after the time is up does nothing`() = runTest {
        val (server, removals) = setup()
        removals.remove(a, index = 0)
        runCurrent()
        advanceTimeBy(ContinueWatchingRemovals.UNDO_MS + 1)
        removals.undo(a.id)
        runCurrent()
        assertEquals(listOf(a.id to 0L), server.calls)
    }

    @Test
    fun `Undo as another profile does nothing`() = runTest {
        val (server, removals) = setup()
        removals.remove(a, index = 0)
        runCurrent()
        profile = "s1/jonas"
        removals.undo(a.id)
        runCurrent()
        assertEquals(listOf(a.id to 0L), server.calls)
    }

    @Test
    fun `a removal the server does not take puts the card back and says so`() = runTest {
        val (server, removals) = setup()
        val notices = mutableListOf<RemovalNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { removals.notices.toList(notices) }
        server.fail = true
        removals.remove(a, index = 0)
        runCurrent()
        assertFalse(a.id in removals.pending.value)
        assertEquals(listOf(a, c), removals.visible(listOf(a, c), removals.stamp()))
        assertEquals(listOf(RemovalNotice.NotRemoved), notices)
    }

    @Test
    fun `newer progress reported by the server ends the Undo at once`() = runTest {
        val (_, removals) = setup()
        removals.remove(a, index = 0)
        runCurrent()
        // The removal's own echo leaves the Undo.
        removals.onUserData(UserDataChange(a.id, false, 0, null, false, null, lastPlayed))
        assertTrue(a.id in removals.pending.value)
        // Played on another device.
        removals.onUserData(UserDataChange(a.id, false, 5_000, 1.0, false, null, lastPlayed))
        assertFalse(a.id in removals.pending.value)
    }

    @Test
    fun `undo is allowed only while the server has the item as the removal left it`() {
        val before = ResumeSnapshot(100, played = false, playCount = 2, lastPlayed = lastPlayed)
        val cleared = before.copy(positionTicks = 0)
        assertTrue(ContinueWatchingRemovals.undoAllowed(before, cleared))
        assertFalse(ContinueWatchingRemovals.undoAllowed(before, cleared.copy(positionTicks = 50)))
        assertFalse(ContinueWatchingRemovals.undoAllowed(before, cleared.copy(played = true)))
        assertFalse(ContinueWatchingRemovals.undoAllowed(before, cleared.copy(playCount = 3)))
        assertFalse(ContinueWatchingRemovals.undoAllowed(before, cleared.copy(lastPlayed = lastPlayed.plusHours(1))))
    }
}
