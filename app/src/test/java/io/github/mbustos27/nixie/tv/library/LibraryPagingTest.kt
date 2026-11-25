// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.AlphabetLetters
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryPage
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.library.BrowseMemory
import io.github.glacier_jellyfin.androidtv.library.LibraryViewModel
import io.github.mbustos27.nixie.tv.data.library.NixieLibraryPaging
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TV-S1 (the owner's TV session): Glacier's LibraryViewModel with the library's first page from the server. Its next
 * pages follow the server's order; a sort change cancels every request of the old list (its pages, its refresh and
 * the index its A–Z rail waits for); a letter jump starts the list anew in the app's own order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryPagingTest {

    private val scheduler = TestCoroutineScheduler()
    private val stopped = MutableSharedFlow<UUID>(extraBufferCapacity = 1)
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun title(n: Int) = MediaItem(
        id = UUID.nameUUIDFromBytes("film-$n".toByteArray()),
        kind = ItemKind.Movie,
        title = "Film %05d".format(n),
        sortName = null,
        year = 2000,
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

    private fun page(
        from: Int,
        count: Int,
        serverOrder: Boolean,
        total: Int? = TOTAL,
    ) = LibraryPage((from until minOf(from + count, TOTAL)).map(::title), total, serverOrder)

    /** The library's grid of every movie, its first page from the server; [also] sets more answers first. */
    private fun model(drawn: Boolean = true, also: () -> Unit = {}): LibraryViewModel {
        repository = mockk(relaxed = true)
        coEvery { repository.firstPage(any(), any()) } answers { page(0, secondArg(), serverOrder = true) }
        coEvery { repository.page(any(), any(), any(), any(), any(), any()) } answers {
            page(secondArg(), thirdArg(), serverOrder = arg(3))
        }
        coEvery { repository.availableLetters(any(), any()) } returns setOf('F')
        coEvery { repository.knownLetters(any()) } returns setOf('F')
        every { repository.progress } returns MutableStateFlow(null)
        also()
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.settings } returns MutableStateFlow(ProfileSettings())
        coEvery { settings.current() } returns ProfileSettings()
        val sessions = mockk<SessionManager>(relaxed = true)
        every { sessions.session } returns MutableStateFlow(null)
        val details = mockk<DetailRepository>(relaxed = true)
        every { details.playedChanged } returns MutableSharedFlow()
        val playback = mockk<PlaybackRepository>(relaxed = true)
        every { playback.stopped } returns stopped
        val home = mockk<HomeRepository>(relaxed = true)
        every { home.kinds } returns MutableStateFlow(listOf(LibraryKind.Movies))
        val model = LibraryViewModel(
            SavedStateHandle(mapOf("kind" to LibraryKind.Movies.name)),
            repository,
            sessions,
            settings,
            mockk(relaxed = true),
            mockk(relaxed = true),
            details,
            playback,
            home,
            mockk(relaxed = true),
            BrowseMemory(flowOf(null), StandardTestDispatcher(scheduler)),
        )
        scheduler.advanceUntilIdle()
        if (drawn) {
            // The grid drew its first row (LibraryScreen): the background work after the first screen may start.
            model.firstRowShown()
            scheduler.advanceUntilIdle()
        }
        return model
    }

    @Test
    fun `after a first page from the server, the next pages follow the server's order`() {
        val model = model()
        assertTrue(model.state.value.serverOrder)
        assertEquals(LibraryViewModel.PAGE_SIZE, model.state.value.items.size)
        model.loadMore()
        scheduler.advanceUntilIdle()
        coVerify { repository.page(any(), 70, 70, true, TOTAL) }
        assertEquals(140, model.state.value.items.size)
        assertTrue(model.state.value.serverOrder)
    }

    @Test
    fun `a page that repeats titles leaves each title once in the grid`() {
        val model = model()
        // The server's list moved (a film added at the top): the next page repeats the last titles shown.
        coEvery { repository.page(any(), any(), any(), any(), any(), any()) } answers {
            page(secondArg<Int>() - 5, thirdArg<Int>() + 5, serverOrder = true)
        }
        model.loadMore()
        scheduler.advanceUntilIdle()
        val ids = model.state.value.items.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        assertEquals(140, ids.size)
    }

    @Test
    fun `a sort change cancels the old list's page, its refresh and the index its rail waits for`() {
        val model = model()
        val cancelled = mutableSetOf<String>()
        coEvery { repository.page(any(), any(), any(), any(), any(), any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                cancelled += if (secondArg<Int>() == 0) "refresh" else "page"
            }
        }
        // The rail's index, read from the device.
        coEvery { repository.knownLetters(any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                cancelled += "index"
            }
        }
        // The old list: a refresh (a title played meanwhile), its letters, and the next page coming.
        scheduler.advanceUntilIdle()
        model.retry()
        scheduler.advanceUntilIdle()
        model.firstRowShown() // its first row drawn: its letters come after it
        scheduler.advanceUntilIdle()
        stopped.tryEmit(UUID.randomUUID())
        scheduler.advanceUntilIdle()
        model.loadMore()
        scheduler.advanceUntilIdle()
        assertEquals(emptySet<String>(), cancelled)

        model.setSort(LibrarySort.Year)
        scheduler.advanceUntilIdle()
        assertEquals(setOf("refresh", "page", "index"), cancelled)
        coVerify { repository.firstPage(match { it.sort == LibrarySort.Year && it.descending }, any()) }
    }

    @Test
    fun `an A-Z list opens without its index, and fetches it in the background after a few pages`() {
        val model = model { coEvery { repository.knownLetters(any()) } returns null }
        coVerify(exactly = 0) { repository.availableLetters(any(), any()) }
        // Every letter on the rail until the list's own are known.
        assertEquals(AlphabetLetters.toSet(), model.state.value.letters)
        repeat(3) {
            model.loadMore()
            scheduler.advanceUntilIdle()
        }
        assertEquals(NixieLibraryPaging.INDEX_AFTER_TITLES, model.state.value.items.size)
        coVerify(exactly = 1) { repository.availableLetters(any(), true) }
        assertEquals(setOf('F'), model.state.value.letters)
    }

    @Test
    fun `two letters pressed while the index loads land on the second, with one load of the index`() {
        // The index path: the list's index is in memory (it is loaded anew, as after a sort's first look).
        val model = model { coEvery { repository.indexReady(any()) } returns true }
        val index = CompletableDeferred<Set<Char>>()
        coEvery { repository.availableLetters(any(), any()) } coAnswers { index.await() }
        // M's place is found only after O's: without the cancel, M's jump would land last.
        val m = CompletableDeferred<Int>()
        coEvery { repository.indexOfLetter(any(), 'M') } coAnswers { m.await() }
        coEvery { repository.indexOfLetter(any(), 'O') } returns 3_500
        coEvery { repository.rowStart(any(), any(), any()) } answers { secondArg<Int>() / 7 * 7 }
        model.jumpToLetter('M', columns = 7)
        scheduler.advanceUntilIdle()
        model.jumpToLetter('O', columns = 7)
        scheduler.advanceUntilIdle()
        index.complete(setOf('M', 'O'))
        scheduler.advanceUntilIdle()
        m.complete(2_000)
        scheduler.advanceUntilIdle()
        val state = model.state.value
        assertEquals(3_500, state.offset + state.jumpTo!!)
        coVerify(exactly = 1) { repository.availableLetters(any(), any()) }
    }

    @Test
    fun `a letter jump in a list from the server, its index in memory by then, starts it anew in the app's order`() {
        val model = model { coEvery { repository.indexReady(any()) } returns true }
        coEvery { repository.indexOfLetter(any(), 'C') } returns 30
        coEvery { repository.rowStart(any(), any(), any()) } answers { secondArg<Int>() / 7 * 7 }
        model.jumpToLetter('C', columns = 7)
        scheduler.advanceUntilIdle()
        // Three rows before the title, from the start of its row, in the index's order.
        coVerify { repository.page(any(), 7, LibraryViewModel.PAGE_SIZE, false, any(), any()) }
        val state = model.state.value
        assertFalse(state.serverOrder)
        assertEquals(7, state.offset)
        assertEquals(title(7), state.items.first())
        assertEquals(30 - 7, state.jumpTo)
    }

    @Test
    fun `the first page comes uncounted and the count follows apart, after it`() {
        val counted = CompletableDeferred<Int>()
        val model = model {
            coEvery { repository.firstPage(any(), any()) } answers {
                page(0, secondArg(), serverOrder = true, total = null)
            }
            coEvery { repository.serverPosition(any()) } coAnswers { counted.await() }
        }
        val state = model.state.value
        assertEquals(LibraryViewModel.PAGE_SIZE, state.items.size)
        assertEquals(null, state.total)
        assertFalse(state.complete)
        counted.complete(TOTAL)
        scheduler.advanceUntilIdle()
        assertEquals(TOTAL, model.state.value.total)
        coVerifyOrder {
            repository.firstPage(any(), any())
            repository.serverPosition(any())
        }
        // The next pages carry the count.
        model.loadMore()
        scheduler.advanceUntilIdle()
        coVerify { repository.page(any(), 70, 70, true, TOTAL, true) }
    }

    @Test
    fun `an A-Z jump in a list from the server goes by the server's offset, without the index`() {
        val model = model {
            coEvery { repository.firstPage(any(), any()) } answers {
                page(0, secondArg(), serverOrder = true, total = null)
            }
            // The count has not come yet: the jump does not wait for it.
            coEvery { repository.serverPosition(any()) } coAnswers { awaitCancellation() }
            coEvery { repository.serverPosition(any(), 'M', any()) } returns 2_000
        }
        model.jumpToLetter('M', columns = 7)
        scheduler.advanceUntilIdle()
        // Three rows before the letter, from the start of its row: 2,000 - 21 = 1,979, row start 1,974.
        coVerify { repository.page(any(), 1_974, LibraryViewModel.PAGE_SIZE, true, null, false) }
        coVerify(exactly = 0) { repository.availableLetters(any(), any()) }
        coVerify(exactly = 0) { repository.indexOfLetter(any(), any()) }
        val state = model.state.value
        assertTrue(state.serverOrder)
        assertEquals(1_974, state.offset)
        assertEquals(title(1_974), state.items.first())
        assertEquals(2_000 - 1_974, state.jumpTo)

        // The titles before it, back up the list: from the server too, whole rows, no overlap.
        model.loadBefore(columns = 7)
        scheduler.advanceUntilIdle()
        coVerify { repository.page(any(), 1_904, 70, true, any(), false) }
        assertEquals(1_904, model.state.value.offset)
        assertEquals(title(1_904), model.state.value.items.first())
    }

    @Test
    fun `two letters pressed in a list from the server land on the second`() {
        val m = CompletableDeferred<Int>()
        val model = model {
            coEvery { repository.serverPosition(any(), 'M', any()) } coAnswers { m.await() }
            coEvery { repository.serverPosition(any(), 'O', any()) } returns 3_500
        }
        model.jumpToLetter('M', columns = 7)
        scheduler.advanceUntilIdle()
        model.jumpToLetter('O', columns = 7)
        scheduler.advanceUntilIdle()
        m.complete(2_000)
        scheduler.advanceUntilIdle()
        val state = model.state.value
        assertEquals(3_500, state.offset + state.jumpTo!!)
    }

    @Test
    fun `a letter already on screen in a list from the server scrolls there without a page`() {
        val model = model { coEvery { repository.serverPosition(any(), 'B', any()) } returns 30 }
        model.jumpToLetter('B', columns = 7)
        scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { repository.page(any(), any(), any(), any(), any(), false) }
        assertEquals(0, model.state.value.offset)
        assertEquals(30, model.state.value.jumpTo)
    }

    @Test
    fun `the count and the rail's letters wait for the first row on screen`() {
        val model = model(drawn = false) {
            coEvery { repository.firstPage(any(), any()) } answers {
                page(0, secondArg(), serverOrder = true, total = null)
            }
            coEvery { repository.serverPosition(any()) } returns TOTAL
        }
        assertEquals(LibraryViewModel.PAGE_SIZE, model.state.value.items.size)
        coVerify(exactly = 0) { repository.serverPosition(any()) }
        coVerify(exactly = 0) { repository.knownLetters(any()) }
        model.firstRowShown()
        scheduler.advanceUntilIdle()
        assertEquals(TOTAL, model.state.value.total)
        coVerify(exactly = 1) { repository.knownLetters(any()) }
    }

    private companion object {
        const val TOTAL = 10_000
    }

    @Test
    fun `TV-S13 - a list in the server's order offers every letter at once, while its saved index is still read`() {
        // Reading a saved index of 25,000 films takes a slow box seconds: the rail does not wait for it.
        val model = model {
            coEvery { repository.knownLetters(any()) } coAnswers { awaitCancellation() }
        }
        assertEquals(AlphabetLetters.toSet(), model.state.value.letters)
    }
}
