// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import android.os.SystemClock
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryPage
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.mbustos27.nixie.tv.data.library.PageTiming
import io.github.mbustos27.nixie.tv.timing.NixieFrameWatch
import io.github.mbustos27.nixie.tv.timing.NixieKeyClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The library's times for the detailed log (debug lines: logcat always, the log file while detailed logging is on), so
 * a check on the box gives real numbers without adb: the first page (its request and its parsing), the first row on
 * screen, the first picture of the first rows, and each A–Z jump. Durations and where a picture came from only: no
 * titles, no server address. Also [firstScreen], which background work waits for (DESIGN-RULES 1).
 *
 * TV-S13: and the whole wait from the remote's OK ([NixieKeyClock]): how long the key waited for the main thread, the
 * screen's opening, the request and parsing, the composing up to the first row on screen, the first row's pictures, and
 * how much of it the main thread was busy ([NixieFrameWatch]): "First row from the key: ..." and "A-Z jump n from the
 * key: ...". A stopwatch on a box once said nearly three times what the page's own line said.
 */
class NixieLibraryTimings(
    private val scope: CoroutineScope,
    private val pictures: NixiePictureWatch = NixieImageWatch,
    // TV-S13: the key's clock (uptime ms), the last OK, and the main thread's long frames.
    private val uptime: () -> Long = SystemClock::uptimeMillis,
    private val press: () -> NixieKeyClock.Press? = { NixieKeyClock.recent() },
    private val stalls: (Long, Long) -> NixieFrameWatch.Stalls = NixieFrameWatch::between,
    private val now: () -> Long = { System.nanoTime() },
) {
    /** When the list was opened; null once the first row's line was written (or before it opened). */
    private var openedAt: Long? = null
    private var askedAt = 0L
    private var timing: PageTiming? = null
    private var shownAt = 0L
    private var picture: Pair<Long, String>? = null
    private var screen = CompletableDeferred<Unit>()
    private var jumps = 0

    // TV-S13, in uptime ms: the screen made (this object comes with its view model), and for the list opened last its
    // OK, opening, request, page and first row; the OK of the jump under way.
    private val madeAt = uptime()
    private var opens = 0
    private var key: NixieKeyClock.Press? = null
    private var usedKey = -1
    private val marks = LongArray(MARKS)
    private var rowPicturesAt = 0L
    private var keyLineDone = true
    private var jumpKey: NixieKeyClock.Press? = null

    /**
     * Done once the list's first row is drawn and its first picture has come, or [PICTURE_WAIT_MS] after the row: the
     * count, the A–Z rail's letters and the like wait for it, so they never compete with the first screen.
     */
    val firstScreen: Job get() = screen

    /** A list opened anew (the screen, or a new sort or filter). */
    fun opened() {
        openedAt = now()
        askedAt = 0L
        timing = null
        shownAt = 0L
        picture = null
        jumps = 0
        screen.cancel()
        screen = CompletableDeferred()
        pictures.stop()
        // TV-S13: the OK that opened it, once (a list opened again without a key has none).
        opens++
        marks.fill(0L)
        marks[OPENED] = uptime()
        rowPicturesAt = 0L
        key = press()?.takeIf { it.seq != usedKey }
        key?.let { usedKey = it.seq }
        keyLineDone = key == null
    }

    /** The list's first page, as [load] asks for it: timed from the request to its answer, and its pictures watched. */
    suspend fun firstPage(load: suspend () -> LibraryPage): LibraryPage {
        val asked = now()
        val askedUp = uptime()
        val page = load()
        val split = page.timing?.let { ": request ${it.requestMs} ms, parse ${it.parseMs} ms" }.orEmpty()
        val from = if (page.serverOrder) "from the server" else "from the index"
        Log.d(TAG, "First page: ${ms(asked)} ms ($from$split)")
        if (openedAt != null && askedAt == 0L) {
            askedAt = asked
            timing = page.timing
            marks[ASKED] = askedUp
            marks[PAGE] = uptime()
            pictures.watch(page.items.take(FIRST_ROWS_TITLES).map { it.id }) { source ->
                val at = now()
                // Coil tells on its own thread; the rest happens on the screen's.
                scope.launch {
                    picture = at to source
                    firstRowDone()
                }
            }
            // TV-S13: the first row's own pictures, every one.
            val row = page.items.take(FIRST_ROW_TITLES).filter { it.posterUrl != null || it.thumbUrl != null }
            pictures.watchAll(row.map { it.id }) {
                val at = uptime()
                scope.launch {
                    rowPicturesAt = at
                    keyLine()
                }
            }
        }
        return page
    }

    /** The grid drew its first row of this list. */
    fun firstRowShown() {
        val opened = openedAt ?: return
        if (shownAt != 0L) return
        shownAt = now()
        marks[ROW] = uptime()
        Log.d(TAG, "First row on screen: ${ms(opened)} ms after opening")
        val waiting = screen
        scope.launch {
            delay(PICTURE_WAIT_MS)
            waiting.complete(Unit)
            keyLine(gaveUp = true) // TV-S13: a row whose pictures did not all come is told as it stands
        }
        firstRowDone()
        keyLine()
    }

    /** Once the row is on screen and its first picture has come: the line that says where the time went. */
    private fun firstRowDone() {
        val opened = openedAt ?: return
        val (pictureAt, source) = picture ?: return
        if (shownAt == 0L) return
        openedAt = null
        screen.complete(Unit)
        val split = timing?.let { ", request ${it.requestMs} ms, parse ${it.parseMs} ms" }.orEmpty()
        Log.d(
            TAG,
            "First row: before the request ${(askedAt - opened) / NANOS_PER_MS} ms$split, " +
                "on screen at ${(shownAt - opened) / NANOS_PER_MS} ms, " +
                "first picture at ${(pictureAt - opened) / NANOS_PER_MS} ms ($source)",
        )
    }

    /**
     * TV-S13: once the first row is on screen with all its pictures (or [PICTURE_WAIT_MS] after the row, [gaveUp]), the
     * whole wait from the OK, split: "First row from the key: 3120 ms (the key waited 4 ms for the main thread and came
     * up after 90 ms; to the screen 120 ms, to the list's opening 40 ms, ...)".
     */
    private fun keyLine(gaveUp: Boolean = false) {
        val press = key ?: return
        if (keyLineDone || marks[ROW] == 0L) return
        if (rowPicturesAt == 0L && !gaveUp) return
        keyLineDone = true
        val down = press.downAt
        val steps = buildList {
            add("the key waited ${press.queuedMs} ms for the main thread")
            press.upAt?.let { add("came up at ${it - down} ms") }
            // The screen's first list: the navigation and the screen's making count too.
            if (opens == 1) {
                press.navigatedAt?.let { add("the page opened at ${it - down} ms") }
                add("the screen made at ${madeAt - down} ms")
            }
            add("the list opened at ${marks[OPENED] - down} ms")
            add("the list asked at ${marks[ASKED].since(down)} ms")
            timing?.let { add("request ${it.requestMs} ms, parse ${it.parseMs} ms") }
            add("page in at ${marks[PAGE].since(down)} ms")
            add("row on screen at ${marks[ROW] - down} ms")
            add(if (rowPicturesAt != 0L) "its pictures at ${rowPicturesAt - down} ms" else "its pictures not all in")
        }
        val end = maxOf(marks[ROW], rowPicturesAt)
        Log.d(
            TAG,
            "First row from the key: ${marks[ROW] - down} ms (${steps.joinToString()}); main thread from the key to " +
                "the row: ${stalls(down, marks[ROW])}, to the pictures: ${stalls(down, end)}",
        )
    }

    /** An A–Z jump landed: [startedAt] when the letter was pressed, [how] it found the place; numbered from opening. */
    fun jumped(startedAt: Long, how: String) {
        jumps++
        Log.d(TAG, "A-Z jump $jumps: ${ms(startedAt)} ms ($how)")
        jumpLanded = uptime() // TV-S13
        jumpLine()
    }

    // TV-S13: the jump under way, in uptime ms: landed (its titles in the list), and on screen.
    private var jumpLanded = 0L
    private var jumpOnScreen = 0L

    /** A–Z jump pressed: its start, for [jumped]. TV-S13: and its OK, for the line from the key. */
    fun mark(): Long {
        jumpKey = press()?.takeIf { it.seq != usedKey }?.also { usedKey = it.seq } // TV-S13
        jumpLanded = 0L
        jumpOnScreen = 0L
        return now()
    }

    /** TV-S13: the grid shows where the jump landed (scrolled there and drawn). */
    fun jumpShown() {
        if (jumpKey == null || jumpOnScreen != 0L) return
        jumpOnScreen = uptime()
        jumpLine()
    }

    /** TV-S13: once the jump landed and is on screen, its whole wait from the OK. */
    private fun jumpLine() {
        val press = jumpKey ?: return
        if (jumpLanded == 0L || jumpOnScreen == 0L) return
        jumpKey = null
        val down = press.downAt
        val shown = maxOf(jumpLanded, jumpOnScreen)
        Log.d(
            TAG,
            "A-Z jump $jumps from the key: ${shown - down} ms (the key waited ${press.queuedMs} ms for the main " +
                "thread, landed at ${jumpLanded - down} ms, on screen at ${shown - down} ms); main thread: " +
                stalls(down, shown),
        )
    }

    private fun ms(since: Long) = (now() - since) / NANOS_PER_MS

    private fun Long.since(down: Long): String = if (this == 0L) "-" else (this - down).toString()

    private companion object {
        const val TAG = "Library"
        const val NANOS_PER_MS = 1_000_000

        /** The titles of the first two rows, whose first picture is watched. */
        const val FIRST_ROWS_TITLES = 14

        /** TV-S13: the titles of the first row (seven posters), whose pictures are all watched. */
        const val FIRST_ROW_TITLES = 7

        /** How long after the first row background work waits for its first picture at most. */
        const val PICTURE_WAIT_MS = 2_000L

        // TV-S13: the marks, in uptime ms.
        const val OPENED = 0
        const val ASKED = 1
        const val PAGE = 2
        const val ROW = 3
        const val MARKS = 4
    }
}
