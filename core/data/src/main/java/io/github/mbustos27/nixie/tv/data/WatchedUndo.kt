// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto

/** The episodes of a show or season and what they were at; [WatchedServer] is the real one. */
interface EpisodeStates {
    suspend fun episodes(parentId: UUID): Map<UUID, ResumeSnapshot>

    suspend fun restore(itemId: UUID, to: ResumeSnapshot)
}

/** How an Undo after marking watched went. */
sealed interface WatchedUndoResult {
    /** Every episode is back as it was. */
    data object Restored : WatchedUndoResult

    /** Back as it was, apart from [skipped] episodes played or changed since the marking. */
    data class Partly(val skipped: Int) : WatchedUndoResult

    /** The server did not answer: the episodes stay watched. */
    data object Failed : WatchedUndoResult

    /** No Undo was open for it (its time was up). */
    data object Gone : WatchedUndoResult
}

/**
 * Nixie: Undo after marking a show or season watched (desktop Nixie 1.2.0). Marking a whole show
 * watched loses which episodes were seen and where each stopped; so before marking, the page
 * takes [snapshot] of its episodes, and for [UNDO_MS] after the server took the marking, [undo]
 * puts each one back as it was, unless it was played or changed since ([restores]).
 */
class WatchedUndo(
    private val scope: CoroutineScope,
    private val server: EpisodeStates,
    private val undoMs: Long = UNDO_MS,
) {
    private val open = HashMap<UUID, Map<UUID, ResumeSnapshot>>()
    private val timers = HashMap<UUID, Job>()

    /** What [parentId]'s episodes are at now; null when the server cannot say (no Undo then). */
    suspend fun snapshot(parentId: UUID): Map<UUID, ResumeSnapshot>? =
        runSuspendCatching { server.episodes(parentId) }.getOrNull()

    /** The marking of [parentId] took: its Undo is open for [UNDO_MS]. */
    fun open(parentId: UUID, before: Map<UUID, ResumeSnapshot>) {
        open[parentId] = before
        timers.remove(parentId)?.cancel()
        timers[parentId] = scope.launch {
            delay(undoMs)
            open.remove(parentId)
        }
    }

    fun isOpen(parentId: UUID): Boolean = parentId in open

    suspend fun undo(parentId: UUID): WatchedUndoResult {
        val before = open.remove(parentId) ?: return WatchedUndoResult.Gone
        timers.remove(parentId)?.cancel()
        return runSuspendCatching {
            val now = server.episodes(parentId)
            val back = restores(before, now)
            coroutineScope {
                back.entries.chunked(PARALLEL).forEach { chunk ->
                    chunk.map { (id, to) -> async { server.restore(id, to) } }.awaitAll()
                }
            }
            val skipped = before.count { (id, was) -> now[id]?.let { it != was && id !in back } == true }
            if (skipped == 0) WatchedUndoResult.Restored else WatchedUndoResult.Partly(skipped)
        }.getOrElse { WatchedUndoResult.Failed }
    }

    companion object {
        const val UNDO_MS = 10_000L
        private const val PARALLEL = 6

        /**
         * The episodes to put back, with what to put back: those the marking changed and that are
         * still as the marking left them, watched with no resume point. One played since keeps
         * its new state.
         */
        fun restores(before: Map<UUID, ResumeSnapshot>, now: Map<UUID, ResumeSnapshot>): Map<UUID, ResumeSnapshot> =
            before.filter { (id, was) ->
                val current = now[id] ?: return@filter false
                current != was && current.played && current.positionTicks == 0L
            }
    }
}

/** Nixie: the signed-in profile's episodes and their user data, for [WatchedUndo]. */
@Singleton
class WatchedServer @Inject constructor(private val sessions: SessionManager) : EpisodeStates {

    override suspend fun episodes(parentId: UUID): Map<UUID, ResumeSnapshot> = withContext(Dispatchers.IO) {
        val session = checkNotNull(sessions.session.value) { "No profile is signed in" }
        session.api.libraryApi.getItems(
            userId = UUID.fromString(session.user.userId),
            parentId = parentId,
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.EPISODE),
            enableUserData = true,
            enableImages = false,
        ).content.items.mapNotNull { item -> item.userData?.let { item.id to it.toSnapshot() } }.toMap()
    }

    override suspend fun restore(itemId: UUID, to: ResumeSnapshot) {
        withContext(Dispatchers.IO) {
            val session = checkNotNull(sessions.session.value) { "No profile is signed in" }
            session.api.userDataApi.updateItemUserData(
                itemId = itemId,
                data = UpdateUserItemDataDto(
                    played = to.played,
                    playbackPositionTicks = to.positionTicks,
                    playCount = to.playCount,
                    lastPlayedDate = to.lastPlayed,
                ),
            )
        }
    }
}
