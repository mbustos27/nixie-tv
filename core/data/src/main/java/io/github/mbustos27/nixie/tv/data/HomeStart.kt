// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.media.HomeContent
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.model.api.BaseItemDto

/** Nixie (tv-home-start): the libraries as the top bar shows them; [liveTv] when the server offers Live TV to the profile. */
data class HomeViews(
    val libraries: List<Library>,
    val latestLibraries: List<Library> = emptyList(),
    val liveTv: Boolean = false,
)

/**
 * Nixie (tv-home-start, tv-home-two-rows): what Home is made of, each part asked for on its own ([HomeStart.rows]):
 * Continue watching and Up next, each a small page with only what Home shows of it and no total count, and, once they
 * are in, the libraries the top bar lists.
 */
interface HomeParts {
    /** The libraries, for the top bar's entries; no counts. */
    suspend fun views(): HomeViews

    /** The server's resume list, as it answered. */
    suspend fun resume(): List<BaseItemDto>

    /** The server's Next Up list, as it answered. */
    suspend fun nextUp(): List<BaseItemDto>

    /** Server items as Home's cards: titles above the profile's age limit left out or marked. */
    suspend fun items(dtos: List<BaseItemDto>): List<MediaItem>
}

/** Home as its parts answer ([HomeStart.rows]). */
data class HomeProgress(
    val content: HomeContent,
    /** Home may draw now ([HomeStart.drawable]); once true it stays true. */
    val drawable: Boolean,
    /** Both rows answered or failed; the top bar's libraries may still come. */
    val settled: Boolean,
    /** Both rows failed: the server could not be asked. */
    val failed: Boolean,
    /** Continue watching answered (or failed). */
    val continueWatchingIn: Boolean,
    /** Up next answered (or failed). */
    val upNextIn: Boolean,
)

/**
 * Nixie (tv-home-start, tv-home-two-rows): Home is two rows, Continue watching and then Up next (the owner, 2026-11-22),
 * and asks the server for those two only; the libraries the top bar lists come after them. Home draws as soon as
 * Continue watching is in, Up next fills in on its own as it answers, and one slow or failed row never holds the other
 * (desktop Nixie's Home; DESIGN-RULES 1). Before stream H, Home waited for every row, the libraries' counts and the
 * hero, one after the other: about a minute on a big server; before this stream it still asked for six more rows, a
 * count per library and the hero's titles.
 */
object HomeStart {
    /** How long Home waits for Continue watching, the top row, before it draws with what is in. */
    const val FIRST_ROW_GRACE_MS = 1_000L

    /**
     * Home draws once Continue watching answered and a row has titles (the focus lands where it belongs, at the
     * top); after [FIRST_ROW_GRACE_MS] once a row has titles or Continue watching answered, even empty; and once both
     * rows answered, if only to say they are empty.
     */
    fun drawable(
        continueWatchingIn: Boolean,
        anyRow: Boolean,
        graceOver: Boolean,
        settled: Boolean,
    ): Boolean = settled || (anyRow && (continueWatchingIn || graceOver)) || (continueWatchingIn && graceOver)

    /**
     * Home's two rows from [parts], each as it answers, over [previous] (what Home shows already, or what was saved on
     * the device): a row keeps what [previous] had until its own answer comes, and keeps it if its request fails. The
     * top bar's libraries are asked last, once both rows are in, unless [topBar] is off (the top bar's Home back to a
     * Home that has them: tv-home-two-rows). [timing] hears when each part landed, in ms since the start, for the
     * detailed log.
     */
    fun rows(
        parts: HomeParts,
        previous: HomeContent?,
        settings: NixieHomeSettings,
        now: LocalDateTime,
        timing: (part: String, ms: Long, ok: Boolean) -> Unit = { _, _, _ -> },
        clock: () -> Long = System::currentTimeMillis,
        topBar: Boolean = true,
    ): Flow<HomeProgress> = channelFlow {
        val loading = Loading(this, parts, Board(previous), settings, now, timing, clock)
        val grace = launch { loading.graceAfter(FIRST_ROW_GRACE_MS) }
        loading.start()
        loading.settled.await()
        grace.cancel()
        if (topBar) loading.topBar()
    }

    /** One load of Home's rows into [board], sent to [out] as each part lands. */
    private class Loading(
        private val out: ProducerScope<HomeProgress>,
        private val parts: HomeParts,
        private val board: Board,
        private val settings: NixieHomeSettings,
        private val now: LocalDateTime,
        private val timing: (part: String, ms: Long, ok: Boolean) -> Unit,
        private val clock: () -> Long,
    ) {
        private val started = clock()
        private val lock = Mutex()

        // Continue watching and Up next.
        private val outstanding = AtomicInteger(ROWS)
        private val resumeAnswer = CompletableDeferred<List<BaseItemDto>?>()
        val settled = CompletableDeferred<Unit>()

        suspend fun graceAfter(ms: Long) {
            delay(ms)
            lock.withLock {
                if (!board.settled) {
                    board.graceOver = true
                    out.send(board.progress())
                }
            }
        }

        fun start() {
            out.launch {
                part("continue watching", {
                    val raw = parts.resume()
                    resumeAnswer.complete(raw)
                    parts.items(ContinueWatchingRules.rows(raw, emptyList(), settings, now).continueWatching)
                }, always = { continueWatchingIn = true }) { content = content.copy(continueWatching = it) }
                resumeAnswer.complete(null)
            }
            out.launch {
                part("up next", {
                    val next = parts.nextUp()
                    val resume = resumeAnswer.await().orEmpty()
                    parts.items(ContinueWatchingRules.rows(resume, next, settings, now).upNext)
                }, always = { upNextIn = true }) { content = content.copy(upNext = it) }
            }
        }

        /** Last, once both rows are in: the libraries the top bar lists (one request, no counts). */
        suspend fun topBar() {
            val views = runSuspendCatching { parts.views() }
            timing("top bar's libraries", clock() - started, views.isSuccess)
            views.exceptionOrNull()?.let { Log.w(TAG, "Home's top bar libraries failed", it) }
            val known = views.getOrNull() ?: return
            lock.withLock {
                board.content = board.content.copy(libraries = known.libraries)
                out.send(board.progress())
            }
        }

        private suspend fun <T> part(
            name: String,
            ask: suspend () -> T,
            always: Board.() -> Unit = {},
            land: Board.(T) -> Unit,
        ): T? {
            val result = runSuspendCatching { ask() }
            timing(name, clock() - started, result.isSuccess)
            result.exceptionOrNull()?.let { Log.w(TAG, "Home's $name failed", it) }
            lock.withLock {
                result.onSuccess {
                    board.successes++
                    board.land(it)
                }
                board.always()
                if (outstanding.decrementAndGet() == 0) board.settled = true
                out.send(board.progress())
                if (board.settled) settled.complete(Unit)
            }
            return result.getOrNull()
        }
    }

    /** Home's rows: Continue watching and Up next. */
    private const val ROWS = 2
    private const val TAG = "Home"

    /** What Home has so far, under the rows' lock. */
    private class Board(previous: HomeContent?) {
        // Home shows its two rows only: whatever else a page saved before this stream had is left behind.
        var content = HomeContent(
            libraries = previous?.libraries.orEmpty(),
            continueWatching = previous?.continueWatching.orEmpty(),
            upNext = previous?.upNext.orEmpty(),
            latest = emptyList(),
        )
        var successes = 0
        var continueWatchingIn = false
        var upNextIn = false
        var graceOver = false
        var settled = false
        private var drawn = false

        private fun anyRow() = content.continueWatching.isNotEmpty() || content.upNext.isNotEmpty()

        fun progress(): HomeProgress {
            drawn = drawn || HomeStart.drawable(continueWatchingIn, anyRow(), graceOver, settled)
            return HomeProgress(
                content = content,
                drawable = drawn,
                settled = settled,
                failed = settled && successes == 0,
                continueWatchingIn = continueWatchingIn,
                upNextIn = upNextIn,
            )
        }
    }
}
