// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the server had for an item: what an Undo puts back, and how newer progress is told apart. */
data class ResumeSnapshot(
    val positionTicks: Long,
    val played: Boolean,
    val playCount: Int,
    val lastPlayed: LocalDateTime?,
)

/** The server side of a removal from Continue Watching; [ContinueWatchingServer] is the real one. */
interface RemovalServer {
    suspend fun snapshot(itemId: UUID): ResumeSnapshot

    suspend fun setPosition(itemId: UUID, positionTicks: Long)
}

/** An item taken out of Continue Watching that can still be put back. */
data class PendingRemoval(
    val item: MediaItem,
    /** Where it was in the row. */
    val index: Int,
    /** Who removed it: an Undo after a profile switch does nothing. */
    val profile: String,
    /** What the server had before; null until it answered. */
    val before: ResumeSnapshot? = null,
)

/** What Home says after a removal or an Undo that did not go as asked. */
enum class RemovalNotice {
    /** The server did not take the removal: the card is back. */
    NotRemoved,

    /** It was played or marked since: the Undo leaves it as the server has it now. */
    ChangedSince,

    /** The server did not take the Undo. */
    NotPutBack,
}

/**
 * Nixie: Remove from Continue Watching, with an Undo (desktop Nixie 1.2.0). Jellyfin has no
 * "hide" for its resume list, which is every item with a resume point, so a removal sets the
 * position to 0, as jellyfin-web's own reset does. For [UNDO_MS] the row shows an Undo card in
 * the item's place; Undo puts the old position back unless newer progress came in, which
 * [undoAllowed] tells from the server's answer at that moment.
 */
class ContinueWatchingRemovals(
    private val scope: CoroutineScope,
    private val server: RemovalServer,
    /** The signed-in profile, see [PendingRemoval.profile]. */
    private val profile: () -> String?,
    private val undoMs: Long = UNDO_MS,
) {
    private val _pending = MutableStateFlow<Map<UUID, PendingRemoval>>(emptyMap())

    /** The items showing an Undo card, by id. */
    val pending: StateFlow<Map<UUID, PendingRemoval>> = _pending.asStateFlow()

    private val _changes = MutableStateFlow(0)

    /** Counts every change to what [visible] makes of an answer: the row is to be made again. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val _notices = MutableSharedFlow<RemovalNotice>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notices: SharedFlow<RemovalNotice> = _notices.asSharedFlow()

    private val _undone = MutableSharedFlow<UUID>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** An Undo is through, whatever the server said: the rows are to be asked for again. */
    val undone: SharedFlow<UUID> = _undone.asSharedFlow()

    // Nixie (tv-watchnext): only an Undo the server took, with no newer removal of the item under way.
    private val _putBack = MutableSharedFlow<UUID>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** An item an Undo put back on the server, and not removed again since (the home screen's row follows). */
    val putBack: SharedFlow<UUID> = _putBack.asSharedFlow()

    /** Each removal's trip to the server: what it had before, or null when it did not take it. */
    private val trips = HashMap<UUID, Deferred<ResumeSnapshot?>>()
    private val timers = HashMap<UUID, Job>()

    /** Undos on their way to the server, with the removal trip each undoes. */
    private val undoing = HashMap<UUID, Pair<Job, Deferred<ResumeSnapshot?>>>()

    /** Removals the server took, by id, with the [stamp] at which it took them. */
    private val done = HashMap<UUID, Long>()

    /** Items an Undo puts back, with the [stamp] at which the server had them back (MAX_VALUE until then). */
    private val restoring = HashMap<UUID, Pair<PendingRemoval, Long>>()
    private var counter = 0L

    /** A mark to take before asking the server for the rows; see [visible]. */
    fun stamp(): Long = ++counter

    /**
     * The Continue Watching row of an answer asked for at [askedAt]: less what was removed
     * after it was asked (the server may not have had the removal yet), with the Undo cards
     * in their places, and items an Undo put back after it was asked in theirs.
     */
    fun visible(items: List<MediaItem>, askedAt: Long): List<MediaItem> {
        val pending = _pending.value
        val back = restoring.values.filter { (_, at) -> askedAt <= at }.map { it.first }
        val kept = items.filterNot { item -> item.id in pending || (done[item.id] ?: Long.MIN_VALUE) >= askedAt }
        // Each id once: the row keys its cards by id.
        return kept.withPending((pending.values + back).distinctBy { it.item.id }).distinctBy { it.id }
    }

    fun remove(item: MediaItem, index: Int) {
        val who = profile() ?: return
        if (item.id in _pending.value) return
        restoring.remove(item.id)
        setPending { it + (item.id to PendingRemoval(item, index, who)) }
        // Removed again while the Undo of the last removal is on its way: this trip waits for it.
        val earlier = undoing[item.id]
        val trip = scope.async {
            earlier?.first?.join()
            runSuspendCatching {
                val now = server.snapshot(item.id)
                // The server still has it as the last removal left it (that Undo did not take):
                // the resume point to keep is the one from before that removal.
                val original = earlier?.second?.await()
                val before = if (original != null && undoAllowed(original, now)) original else now
                server.setPosition(item.id, 0)
                before
            }.getOrNull()
        }
        trips[item.id] = trip
        timers[item.id] = scope.launch {
            val before = trip.await()
            if (before == null) {
                setPending { it - item.id }
                _notices.tryEmit(RemovalNotice.NotRemoved)
                return@launch
            }
            done[item.id] = ++counter
            setPending { map -> map[item.id]?.let { map + (item.id to it.copy(before = before)) } ?: map }
            delay(undoMs)
            setPending { it - item.id }
        }
    }

    /** Puts [itemId] back, see the class. Nothing happens once its time is up. */
    fun undo(itemId: UUID) {
        val removal = _pending.value[itemId] ?: return
        timers.remove(itemId)?.cancel()
        val trip = trips.remove(itemId) ?: return
        restoring[itemId] = removal to Long.MAX_VALUE
        setPending { it - itemId }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // A removal still on its way to the server: Undo waits for it.
            val before = trip.await()
            val back = before != null && removal.profile == profile() && runSuspendCatching {
                if (undoAllowed(before, server.snapshot(itemId))) {
                    server.setPosition(itemId, before.positionTicks)
                    true
                } else {
                    _notices.tryEmit(RemovalNotice.ChangedSince)
                    false
                }
            }.getOrElse {
                _notices.tryEmit(RemovalNotice.NotPutBack)
                false
            }
            // Removed again meanwhile: the new removal decides, nothing is put back in the row.
            val again = itemId in _pending.value
            if (back) done.remove(itemId)
            if (back && !again) restoring[itemId] = removal to ++counter else restoring.remove(itemId)
            if (undoing[itemId]?.first === coroutineContext[Job]) undoing.remove(itemId)
            _changes.update { it + 1 }
            _undone.tryEmit(itemId)
            if (back && !again) _putBack.tryEmit(itemId) // Nixie (tv-watchnext)
        }
        undoing[itemId] = job to trip
        job.start()
    }

    /**
     * The server reported a change to an item: newer progress (a resume point again, or a changed
     * watched mark) ends its Undo; the removal's own echo, position 0, does not.
     */
    fun onUserData(change: UserDataChange) {
        val before = _pending.value[change.itemId]?.before ?: return
        if (change.positionTicks != 0L || change.played != before.played) settle(change.itemId)
    }

    /** Newer progress came in for [itemId] (played elsewhere, marked watched): its Undo goes. */
    fun settle(itemId: UUID) {
        if (_pending.value[itemId]?.before == null) return
        timers.remove(itemId)?.cancel()
        trips.remove(itemId)
        setPending { it - itemId }
    }

    private fun setPending(change: (Map<UUID, PendingRemoval>) -> Map<UUID, PendingRemoval>) {
        _pending.update(change)
        _changes.update { it + 1 }
    }

    companion object {
        /** How long the Undo card stays. */
        const val UNDO_MS = 10_000L

        /**
         * Undo may put the old resume point back only while the server has the item as the
         * removal left it: no position, not marked, not played since.
         */
        fun undoAllowed(before: ResumeSnapshot, now: ResumeSnapshot): Boolean = now.positionTicks == 0L &&
            now.played == before.played &&
            now.playCount == before.playCount &&
            now.lastPlayed == before.lastPlayed
    }
}

/** [this] with the [pending] items back at their places, those it lacks. */
internal fun List<MediaItem>.withPending(pending: Collection<PendingRemoval>): List<MediaItem> {
    val ids = mapTo(HashSet()) { it.id }
    val list = toMutableList()
    pending.filter { it.item.id !in ids }.sortedBy { it.index }.forEach {
        list.add(it.index.coerceIn(0, list.size), it.item)
    }
    return list
}
