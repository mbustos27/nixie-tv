// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.channels

import android.annotation.SuppressLint
import android.content.Context
import io.github.glacier_jellyfin.androidtv.channels.HomeChannels
import io.github.glacier_jellyfin.androidtv.channels.HomeChannelsSync
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeScreenContent
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.mbustos27.nixie.tv.data.watchnext.WatchNextOwner
import io.github.mbustos27.nixie.tv.data.watchnext.WatchNextProfiles
import io.github.mbustos27.nixie.tv.data.watchnext.WatchNextProgress
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Nixie (tv-watchnext): Continue watching on the TV's home screen (Android's Watch Next), for
 * home screens that show it (Projectivy, Plume, LtvLauncher; Google TV's own filters apps it has
 * not certified). One profile owns the rows ([WatchNextOwner]); they follow the server's Continue
 * watching through Glacier's background work ([HomeChannelsSync]) and change on the spot here:
 * a position the server took, a removal from Nixie's Continue watching and its Undo, a sign-out
 * or another profile, the setting switched off. Nothing is sent anywhere.
 */
@Singleton
class NixieWatchNext internal constructor(
    private val context: Context,
    private val profiles: WatchNextProfiles,
    private val clock: () -> Long,
    private val provider: () -> Boolean,
    private val refresh: () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
        profiles: WatchNextProfiles,
    ) : this(
        context.applicationContext,
        profiles,
        clock = System::currentTimeMillis,
        provider = { HomeChannels(context).available },
        refresh = { HomeChannelsSync.refresh(context) },
    )

    /** Every write to the home screen's rows takes it, the background work's too: one writes at a time. */
    val lock = Mutex()

    // Watch Next must never take Nixie down: whatever escapes a guard below is logged here.
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> Log.w(TAG, "Watch Next failed", e) },
    )

    /** Titles just removed from Continue watching, by id, until when: a write from before the removal must not bring them back. */
    private val hidden = HashMap<String, Long>()

    /** Each title's removals so far, counted on the caller's thread: an Undo puts back only after the latest. */
    private val removals = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The rows those removals took, for their Undo. */
    private val taken = HashMap<String, androidx.tvprovider.media.tv.WatchNextProgram>()

    /** The title playing now, and what of it is not written yet ([progressNow]). */
    private class Playing(val itemId: String) {
        var writtenAt = Long.MIN_VALUE / 2
        var positionMs = 0L
        var dirty = false
    }

    private var playing: Playing? = null
    private var flush: Job? = null
    private var flushAt = Long.MAX_VALUE

    private fun store() = if (provider()) WatchNextStore(context.contentResolver) else null

    /** Watch Next must never take Nixie down: a provider error (or any other) is logged, and the work goes on. */
    @Suppress("TooGenericExceptionCaught") // Any failure, by design (review m5).
    private inline fun <T> safely(what: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what failed", e)
        null
    }

    private fun refreshSafely() {
        safely("Asking for the rows") { refresh() }
    }
    // ---- The background work (HomeChannelsWorker) ----

    /**
     * Writes [content] with [write] (Glacier's HomeChannels.write) unless it is no longer the owner's: loaded
     * for a profile that has since signed out or given way to another. Titles just removed stay out; with
     * the setting off, Continue watching is empty.
     */
    suspend fun publish(content: HomeScreenContent?, write: (HomeScreenContent?) -> Unit) = lock.withLock {
        val owner = profiles.owner()
        val ownerKey = owner?.let(WatchNextOwner::key)
        if (content?.let(WatchNextIds::owner) != ownerKey) {
            Log.i(TAG, "Rows loaded for a profile that no longer owns them; not written")
            return@withLock
        }
        if (content == null || owner == null) {
            // Watch Next first and on its own: a channel error in [write] must not keep the rows.
            emptyWatchNext()
            write(null)
            return@withLock
        }
        val show = profiles.settingsOf(owner).nixieWatchNext.showOnHomeScreen
        val now = clock()
        hidden.values.removeAll { it <= now }
        val shown = if (show) content.continueWatching.filterNot { it.item.id.toString() in hidden } else emptyList()
        write(content.copy(continueWatching = shown))
    }

    // ---- Following the app (HomeChannelsSync) ----

    /**
     * A profile that opens ([sessions]: server and user ids) owns the rows from then on. The owner as it changes
     * ([owners], [WatchNextProfiles.ownerKeys]): the first one known reconciles the table once (another profile's
     * rows go, the owner's stay); each change after empties it at once, and a new owner's rows come with a load.
     * Each position the server took ([positions]) goes to [progressNow], in order. The signed-in profile's setting
     * ([shows]): off removes every row, on loads.
     *
     * tv-home-start: the start's work waits for [ready] (Home drawn and settled, or a while), and an owner not known
     * yet decides nothing (DESIGN-RULES 4): before, a start with no profile recorded yet emptied the launcher's row,
     * and the first profile to open counted as "another profile" and emptied it again.
     */
    internal fun follow(
        sessions: Flow<Pair<String, String>>,
        owners: Flow<String?>,
        positions: Flow<WatchNextProgress>,
        shows: Flow<Boolean>,
        ready: suspend () -> Unit = {},
    ) {
        scope.launch {
            sessions.collect { (serverId, userId) ->
                safely("Recording the owner") { profiles.record(serverId, userId) }
            }
        }
        scope.launch {
            ready()
            var reconciled = false
            var known: String? = null
            owners.distinctUntilChanged().collect { key ->
                when {
                    // No owner known yet: only the rows of a recorded profile known to be gone may go.
                    !reconciled && key == null -> goneNow()

                    !reconciled -> {
                        reconciled = true
                        known = key
                        reconcileNow(key)
                    }

                    key != known -> {
                        known = key
                        ownerChangedNow(key)
                        if (key != null) refreshSafely()
                    }
                }
            }
        }
        scope.launch { positions.collect { progressNow(it) } }
        scope.launch {
            shows.distinctUntilChanged().drop(1).collect { on -> if (on) refreshSafely() else switchedOffNow() }
        }
    }

    /** Once at app start, with the owner known: another profile's rows go (see [WatchNextStore.reconcile]). */
    internal suspend fun reconcileNow(owner: String?) {
        lock.withLock {
            safely("Reconciling Watch Next") {
                val began = clock()
                val removed = store()?.reconcile(owner) ?: 0
                if (removed > 0) Log.i(TAG, "Start: $removed rows of another profile removed")
                Log.d(TAG, "Start: reconciled in ${clock() - began} ms")
            }
        }
    }

    /**
     * tv-home-start: at app start with no owner known, the rows of the recorded profile go only once that profile is
     * known to be gone (a sign-out or a removal the app did not finish emptying); nothing recorded yet removes nothing.
     */
    private suspend fun goneNow() {
        lock.withLock {
            safely("Removing a gone profile's rows") {
                val gone = profiles.gone() ?: return@withLock
                val removed = store()?.removeOwner(gone) ?: 0
                if (removed > 0) Log.i(TAG, "Start: $removed rows of a signed-out profile removed")
            }
        }
    }

    // ---- Changes on the spot ----

    /** The owner changed ([WatchNextProfiles.ownerKeys]): a sign-out, a removal, another profile. The rows go at once. */
    internal suspend fun ownerChangedNow(key: String?) {
        lock.withLock { safely("Emptying the rows") { emptyForOwner(key) } }
    }

    /** Under [lock]. */
    private fun emptyForOwner(key: String?) {
        hidden.clear()
        taken.clear()
        playing = null
        cancelFlush()
        // Watch Next first and on its own, so a channel error cannot keep the old profile's rows.
        emptyWatchNext()
        // Glacier's "nobody signed in": every row empty, the channels kept; the new owner's come with the refresh.
        if (provider()) safely("Emptying the channels") { HomeChannels(context).write(null) }
        Log.i(TAG, if (key == null) "No owner: rows emptied" else "Another profile: rows emptied")
    }

    /** Under [lock]: every Watch Next row of this app goes. */
    private fun emptyWatchNext() {
        safely("Emptying Watch Next") { store()?.clear() }
    }

    /** Settings › Home › "Show Continue watching on the home screen" turned off: every row Nixie wrote goes. */
    internal suspend fun switchedOffNow() {
        lock.withLock {
            safely("Removing every row") { store()?.clear()?.let { Log.i(TAG, "Switched off: $it rows removed") } }
        }
        // The Continue watching channel follows with the next write.
        refreshSafely()
    }

    /** Removed from Nixie's Continue watching: its row goes now, and stays out until [restored] or the server agrees. */
    fun removed(itemId: UUID) {
        removals.merge(itemId.toString(), 1, Int::plus)
        scope.launch { removedNow(itemId) }
    }

    internal suspend fun removedNow(itemId: UUID) {
        lock.withLock {
            safely("Removing a row") {
                val owner = profiles.owner()?.let(WatchNextOwner::key) ?: return@withLock
                val id = itemId.toString()
                hidden[id] = clock() + HIDE_MS
                store()?.remove(owner, id)?.let { taken[id] = it }
            }
        }
        refreshSafely()
    }

    /**
     * The server took the removal's Undo: the row comes back as it was, if the setting and the owner still allow
     * it, and no later removal of the title came since ([removed] counts them when called).
     */
    fun restored(itemId: UUID) {
        val generation = removals[itemId.toString()] ?: 0
        scope.launch { restoredNow(itemId, generation) }
    }

    @SuppressLint("RestrictedApi") // The program's public getters, as in WatchNextStore.
    internal suspend fun restoredNow(itemId: UUID, generation: Int) {
        lock.withLock {
            val id = itemId.toString()
            // An Undo of an earlier removal, arriving after a later one: the later removal stands.
            if ((removals[id] ?: 0) != generation) return@withLock
            hidden.remove(id)
            val row = taken.remove(id) ?: return@withLock
            safely("Putting a row back") {
                val owner = profiles.owner() ?: return@withLock
                if (WatchNextIds.ownerOf(row.internalProviderId) != WatchNextOwner.key(owner)) return@withLock
                if (!profiles.settingsOf(owner).nixieWatchNext.showOnHomeScreen) return@withLock
                store()?.putBack(row)
            }
        }
        refreshSafely()
    }

    /**
     * A position the server took (PlaybackRepository): the playing title's row follows, at most once a minute
     * while it plays, and at a pause, the stop and leaving the player. Seeks in between are one write. A title
     * without a row gets none until the stop's load; playback never loads the rows.
     */
    internal suspend fun progressNow(change: WatchNextProgress) {
        lock.withLock { safely("Writing the playing title's row") { followPlayback(change) } }
    }

    /** Under [lock]. */
    private suspend fun followPlayback(change: WatchNextProgress) {
        val now = clock()
        val id = change.itemId.toString()
        var track = playing
        if (track == null || track.itemId != id) {
            track?.takeIf { it.dirty }?.let { writePlaying(it, now) }
            track = Playing(id).also { playing = it }
            // Played again after a removal: it is under way again.
            hidden.remove(id)
            taken.remove(id)
        }
        track.positionMs = change.positionMs
        track.dirty = true
        when {
            change.final -> {
                writePlaying(track, now)
                playing = null
                cancelFlush()
            }

            // A pause, or buffering: written once it lasts a moment.
            change.paused -> scheduleFlush(now + PAUSE_SETTLE_MS, keepEarlier = true)

            now - track.writtenAt >= WRITE_GAP_MS -> {
                writePlaying(track, now)
                cancelFlush()
            }

            else -> scheduleFlush(track.writtenAt + WRITE_GAP_MS, keepEarlier = false)
        }
    }

    /** Under [lock]. */
    private suspend fun writePlaying(track: Playing, now: Long) {
        track.dirty = false
        track.writtenAt = now
        val owner = profiles.owner() ?: return
        if (!profiles.settingsOf(owner).nixieWatchNext.showOnHomeScreen) return
        if (store()?.progress(WatchNextOwner.key(owner), track.itemId, track.positionMs, now) == true) {
            Log.i(TAG, "Playing title's row written")
        }
    }

    /** Under [lock]: the playing title's position is written at [at], or earlier when [keepEarlier] and one is due. */
    private fun scheduleFlush(at: Long, keepEarlier: Boolean) {
        if (keepEarlier && flush?.isActive == true && flushAt <= at) return
        flush?.cancel()
        flushAt = at
        flush = scope.launch {
            delay((at - clock()).coerceAtLeast(0))
            lock.withLock {
                safely("Writing the playing title's row") {
                    playing?.takeIf { it.dirty }?.let { writePlaying(it, clock()) }
                }
            }
        }
    }

    private fun cancelFlush() {
        flush?.cancel()
        flush = null
        flushAt = Long.MAX_VALUE
    }

    /**
     * Debug builds: this app's rows in logcat, for emulator checks (the shell's own query sees only the shell's rows):
     * `adb shell am broadcast -a io.github.mbustos27.nixie.tv.WATCH_NEXT_DUMP -p <package>`. Only a sender holding
     * the signature permission DUMP (the shell, the system) reaches it, never another app; the lines go to logcat
     * only (android.util.Log), never to the log file users share.
     */
    @SuppressLint("RestrictedApi") // The program's public getters, as in WatchNextStore.
    internal fun registerDebugDump() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                scope.launch {
                    lock.withLock {
                        val rows = safely("Listing the rows") { store()?.rows() }.orEmpty()
                        android.util.Log.i(TAG, "Dump: ${rows.size} rows")
                        rows.forEach {
                            val owner = WatchNextIds.ownerOf(it.internalProviderId)?.let(::shortOwner)
                            android.util.Log.i(
                                TAG,
                                "Dump: id=${it.id} owner=$owner " +
                                    "content=${it.contentId} type=${it.watchNextType} title=${it.title} " +
                                    "episode=${it.episodeTitle} position=${it.lastPlaybackPositionMillis} " +
                                    "duration=${it.durationMillis} engaged=${it.lastEngagementTimeUtcMillis}",
                            )
                        }
                    }
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            android.content.IntentFilter(DUMP_ACTION),
            android.Manifest.permission.DUMP,
            null,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** The profile in a dump: the first characters of the server's and the user's ids. */
    private fun shortOwner(owner: String) = owner.split('/').joinToString("/") { it.take(DUMP_ID_CHARS) }

    internal companion object {
        const val TAG = "WatchNext"
        const val DUMP_ACTION = "io.github.mbustos27.nixie.tv.WATCH_NEXT_DUMP"
        private const val DUMP_ID_CHARS = 8

        /** A removal the server has not taken by then (it refused) shows again with the next write. */
        const val HIDE_MS = 60_000L

        /** While a title plays, its row is written at most this often. */
        const val WRITE_GAP_MS = 60_000L

        /** A pause (or buffering) is written once it lasted this long. */
        const val PAUSE_SETTLE_MS = 5_000L
    }
}
