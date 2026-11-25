// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import android.app.Application
import android.util.Log
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryPage
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.mbustos27.nixie.tv.data.library.PageTiming
import io.github.mbustos27.nixie.tv.timing.NixieFrameWatch
import io.github.mbustos27.nixie.tv.timing.NixieKeyClock
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * TV-S1 (DESIGN-RULES 1, 10): the detailed log times the library's first page (its request and parsing), its first row
 * on screen, the first picture of its first rows and each A–Z jump, durations only (no titles); background work waits
 * for the first screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NixieLibraryTimingsTest {

    private var clock = 0L
    private val scope = TestScope()

    /** The picture watch: what was watched, and how to say a picture came. */
    private val pictures = object : NixiePictureWatch {
        var ids: Collection<UUID> = emptyList()
        var seen: ((String) -> Unit)? = null

        override fun watch(ids: Collection<UUID>, seen: (String) -> Unit) {
            this.ids = ids
            this.seen = seen
        }

        var all: (() -> Unit)? = null

        override fun watchAll(ids: Collection<UUID>, done: () -> Unit) {
            all = done
        }

        override fun stop() {
            seen = null
        }
    }
    private val timings = NixieLibraryTimings(scope, pictures) { clock }

    @Before
    fun setUp() = ShadowLog.clear()

    private fun lines() = ShadowLog.getLogsForTag("Library").filter { it.type == Log.DEBUG }.map { it.msg }

    private fun advance(ms: Long) {
        clock += ms * 1_000_000
    }

    private fun film(n: Int) = MediaItem(
        id = UUID.nameUUIDFromBytes("film-$n".toByteArray()),
        kind = ItemKind.Movie,
        title = "Film $n",
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
        progress = null,
        remainingMinutes = null,
        unwatchedCount = null,
        isFavorite = false,
        played = false,
        childCount = null,
        quality = null,
        posterUrl = null,
        thumbUrl = null,
        backdropUrl = null,
    )

    /** The first page, answered 760 ms after it was asked for. */
    private fun firstPage() = runBlocking {
        timings.firstPage {
            advance(760)
            LibraryPage((1..70).map(::film), total = null, serverOrder = true, PageTiming(640, 120))
        }
    }

    @Test
    fun `the first page with its split, the first row, the first picture, and each jump, in milliseconds`() {
        timings.opened()
        advance(12)
        firstPage()
        advance(40)
        timings.firstRowShown()
        timings.firstRowShown() // later frames: once per list
        assertEquals(14, pictures.ids.size)
        advance(900)
        pictures.seen!!("network")
        scope.runCurrent()
        val pressed = timings.mark()
        advance(410)
        timings.jumped(pressed, "by the server's offset")
        assertEquals(
            listOf(
                "First page: 760 ms (from the server: request 640 ms, parse 120 ms)",
                "First row on screen: 812 ms after opening",
                "First row: before the request 12 ms, request 640 ms, parse 120 ms, on screen at 812 ms, " +
                    "first picture at 1712 ms (network)",
                "A-Z jump 1: 410 ms (by the server's offset)",
            ),
            lines(),
        )
    }

    @Test
    fun `a picture before the row is drawn waits for it`() {
        timings.opened()
        firstPage()
        advance(5)
        pictures.seen!!("memory")
        scope.runCurrent()
        assertTrue(lines().none { it.startsWith("First row:") })
        advance(20)
        timings.firstRowShown()
        assertTrue(lines().last().endsWith("on screen at 785 ms, first picture at 765 ms (memory)"))
    }

    @Test
    fun `background work waits for the first row and its first picture, or two seconds after the row`() {
        timings.opened()
        firstPage()
        assertFalse(timings.firstScreen.isCompleted)
        timings.firstRowShown()
        scope.advanceTimeBy(1_999)
        assertFalse(timings.firstScreen.isCompleted)
        scope.advanceTimeBy(2)
        assertTrue(timings.firstScreen.isCompleted)

        // With its picture, at once.
        timings.opened()
        firstPage()
        timings.firstRowShown()
        pictures.seen!!("network")
        scope.runCurrent()
        assertTrue(timings.firstScreen.isCompleted)
    }

    @Test
    fun `a list opened anew is timed anew and stops the old watch`() {
        timings.opened()
        firstPage()
        timings.firstRowShown()
        timings.jumped(timings.mark(), "the index")
        val old = timings.firstScreen
        ShadowLog.clear()
        timings.opened()
        assertTrue(old.isCancelled)
        assertEquals(null, pictures.seen)
        advance(200)
        firstPage()
        timings.firstRowShown()
        timings.jumped(timings.mark(), "the index")
        assertEquals("A-Z jump 1: 0 ms (the index)", lines().last())
    }

    @Test
    fun `no first row before a list was opened`() {
        timings.firstRowShown()
        assertEquals(emptyList<String>(), lines())
    }

    @Test
    fun `TV-S13 - the first row's and a jump's whole wait from the OK, split, with the main thread's long frames`() {
        var up = 10_000L
        var key = NixieKeyClock.Press(seq = 7, downAt = 9_000, queuedMs = 4, upAt = 9_090, navigatedAt = 9_110)
        val keyed = NixieLibraryTimings(
            scope,
            pictures,
            uptime = { up },
            press = { key },
            stalls = { from, to -> NixieFrameWatch.Stalls(if (to - from > 3_000) 3 else 2, 420, 900) },
        ) { clock }
        up = 10_040
        keyed.opened()
        up = 10_045
        runBlocking {
            keyed.firstPage {
                up = 11_300
                LibraryPage((1..70).map { film(it).copy(posterUrl = "p$it") }, null, true, PageTiming(1100, 150))
            }
        }
        up = 11_500
        keyed.firstRowShown()
        // Not before the row's pictures are all in.
        assertTrue(lines().none { "from the key" in it })
        up = 12_100
        pictures.all!!()
        scope.runCurrent()
        assertEquals(
            "First row from the key: 2500 ms (the key waited 4 ms for the main thread, came up at 90 ms, the " +
                "page opened at 110 ms, the screen made at 1000 ms, the list opened at 1040 ms, the list asked at " +
                "1045 ms, request 1100 ms, parse 150 ms, page in at 2300 ms, row on screen at 2500 ms, its pictures " +
                "at 3100 ms); main thread from the key to the row: 2 frames over 50 ms, longest 420 ms, 900 ms in " +
                "all, to the pictures: 3 frames over 50 ms, longest 420 ms, 900 ms in all",
            lines().single { "from the key" in it },
        )
        // A jump: its own OK, from the press to the grid showing where it landed.
        key = NixieKeyClock.Press(seq = 8, downAt = 20_000, queuedMs = 2, upAt = 20_080)
        up = 20_085
        val pressed = keyed.mark()
        up = 21_300
        keyed.jumped(pressed, "by the letter's page")
        up = 21_350
        keyed.jumpShown()
        assertEquals(
            "A-Z jump 1 from the key: 1350 ms (the key waited 2 ms for the main thread, landed at 1300 ms, on screen " +
                "at 1350 ms); main thread: 2 frames over 50 ms, longest 420 ms, 900 ms in all",
            lines().last(),
        )
        // The same OK opens nothing twice: a list opened again without a key has no such line.
        keyed.opened()
        assertEquals(1, lines().count { it.startsWith("First row from the key") })
    }
}
