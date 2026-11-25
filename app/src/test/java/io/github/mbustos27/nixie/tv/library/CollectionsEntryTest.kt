// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryPage
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryScope
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.LibraryFilterChoice
import io.github.glacier_jellyfin.androidtv.core.data.settings.LibrarySortChoice
import io.github.glacier_jellyfin.androidtv.core.data.settings.NavigationSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.library.BrowseMemory
import io.github.glacier_jellyfin.androidtv.library.LibraryViewModel
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.navEntryIds
import io.github.glacier_jellyfin.androidtv.navigation.navEvent
import io.github.glacier_jellyfin.androidtv.navigation.navTargets
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * tv-home-two-rows (TV-S11, the owner: "an easier way to access collections"): Collections has its own entry in the
 * top bar, after the libraries, also on a server with 23 libraries; it opens every collection on the server, A to Z,
 * in a library's grid: the first request asks only for the first screen, the next pages follow in the server's order.
 * Glacier's real LibraryViewModel with the repository faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollectionsEntryTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var repository: LibraryRepository
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private var profile = ProfileSettings()
    private val memory = BrowseMemory(flowOf(null), StandardTestDispatcher(scheduler))

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `Collections is in the top bar after the libraries, before Live TV and Favorites`() {
        val ids = navEntryIds(listOf(LibraryKind.Movies, LibraryKind.Shows, LibraryKind.Music), liveTv = true)
        assertEquals(listOf("Home", "Libraries", "Movies", "Shows", "Music", "Collections", "LiveTv", "Favorites"), ids)
        val targets = navTargets(NavigationSettings(), listOf(LibraryKind.Movies), liveTv = false)
        assertEquals(
            listOf(NavTarget.Home, NavTarget.Libraries, NavTarget.Library(LibraryKind.Movies), NavTarget.Collections),
            targets.take(4),
        )
    }

    @Test
    fun `with 23 libraries the top bar keeps one entry per kind, Libraries, Collections, Live TV and Favorites`() {
        // A big shared server: 23 libraries, most of them films (two named apart already, LibraryNames.kt).
        val libraries = List(14) { LibraryKind.Movies } + List(6) { LibraryKind.Shows } +
            List(2) { LibraryKind.Music } + LibraryKind.HomeVideos
        assertEquals(23, libraries.size)
        val kinds = libraries.distinct().sorted()
        val targets = navTargets(NavigationSettings(), kinds, liveTv = true)

        assertEquals(
            listOf(
                NavTarget.Home,
                NavTarget.Libraries,
                NavTarget.Library(LibraryKind.Movies),
                NavTarget.Library(LibraryKind.Shows),
                NavTarget.Library(LibraryKind.Music),
                NavTarget.Library(LibraryKind.HomeVideos),
                NavTarget.Collections,
                NavTarget.LiveTv,
                NavTarget.Favorites,
            ),
            targets,
        )
        assertTrue("every library is under its kind's entry", libraries.all { NavTarget.Library(it) in targets })
    }

    @Test
    fun `the entry opens every collection on the server, one press from Home`() {
        val sessions = mockk<SessionManager>(relaxed = true)
        val event = sessions.navEvent(NavTarget.Collections, current = NavTarget.Home)
        assertEquals(UiEvent.Navigate(LibraryRoute(LibraryKind.Movies.name, collections = true)), event)
        assertNull("on its own page it does nothing", sessions.navEvent(NavTarget.Collections, NavTarget.Collections))
    }

    @Test
    fun `the page asks for its first screen, then pages in the server's order`() {
        val model = model()

        coVerifyOrder {
            repository.firstPage(LibraryQuery.nixieCollections(), NixieCollectionsPage.FIRST_PAGE)
            repository.page(
                LibraryQuery.nixieCollections(),
                NixieCollectionsPage.FIRST_PAGE,
                LibraryViewModel.PAGE_SIZE,
                true,
                null,
                true,
            )
        }
        val state = model.state.value
        assertEquals(NixieCollectionsPage.FIRST_PAGE + LibraryViewModel.PAGE_SIZE, state.items.size)
        assertTrue(state.serverOrder)
        assertEquals(listOf(LibraryScope.Collections), state.scopes)
        assertEquals(LibrarySort.Title, state.query.sort)
        assertEquals(false, state.query.descending)

        model.loadMore()
        scheduler.advanceUntilIdle()
        coVerify { repository.page(any(), 94, LibraryViewModel.PAGE_SIZE, true, any(), true) }
    }

    @Test
    fun `the page keeps its own sort and toggles, and none of the movies' memory`() {
        // The movies are sorted by date added, unwatched only: the Collections page opens A to Z, unfiltered.
        profile = ProfileSettings(
            librarySorts = mapOf(LibraryKind.Movies.name to LibrarySortChoice(LibrarySort.DateAdded, true)),
            libraryFilters = mapOf(LibraryKind.Movies.name to LibraryFilterChoice(unwatchedOnly = true)),
        )
        val model = model()
        assertEquals(LibrarySort.Title, model.state.value.query.sort)
        assertEquals(false, model.state.value.query.unwatchedOnly)

        model.focused(UUID.randomUUID().toString())
        model.setSort(LibrarySort.Year)
        scheduler.advanceUntilIdle()
        assertNull("the movies' place in the top bar's memory is untouched", memory.library(LibraryKind.Movies))
        coVerify { repository.firstPage(match { it.nixieAllCollections && it.sort == LibrarySort.Year }, any()) }
        val sorts = profile.librarySorts
        assertEquals(LibrarySortChoice(LibrarySort.Year, true), sorts[LibraryQuery.NIXIE_COLLECTIONS_KEY])
        assertEquals(LibrarySortChoice(LibrarySort.DateAdded, true), sorts[LibraryKind.Movies.name])
    }

    private fun collection(n: Int) = MediaItem(
        id = UUID.nameUUIDFromBytes("set-$n".toByteArray()),
        kind = ItemKind.Collection,
        title = "Set %03d".format(n),
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
        childCount = 4,
        quality = null,
        posterUrl = null,
        thumbUrl = null,
        backdropUrl = null,
    )

    private fun page(from: Int, count: Int) =
        LibraryPage((from until minOf(from + count, SETS)).map(::collection), null, serverOrder = true)

    /** The top bar's Collections page, its first row drawn. */
    private fun model(): LibraryViewModel {
        repository = mockk(relaxed = true)
        coEvery { repository.firstPage(any(), any()) } answers { page(0, secondArg()) }
        coEvery { repository.page(any(), any(), any(), any(), any(), any()) } answers { page(secondArg(), thirdArg()) }
        coEvery { repository.knownLetters(any()) } returns null
        coEvery { repository.serverPosition(any()) } returns SETS
        every { repository.progress } returns MutableStateFlow(null)
        every { settings.settings } answers { MutableStateFlow(profile) }
        coEvery { settings.current() } answers { profile }
        coEvery { settings.update(any()) } coAnswers {
            profile = firstArg<(ProfileSettings) -> ProfileSettings>().invoke(profile)
        }
        val sessions = mockk<SessionManager>(relaxed = true)
        every { sessions.session } returns MutableStateFlow(null)
        val details = mockk<DetailRepository>(relaxed = true)
        every { details.playedChanged } returns MutableSharedFlow()
        val playback = mockk<PlaybackRepository>(relaxed = true)
        every { playback.stopped } returns MutableSharedFlow()
        val home = mockk<HomeRepository>(relaxed = true)
        every { home.kinds } returns MutableStateFlow(listOf(LibraryKind.Movies))
        val model = LibraryViewModel(
            SavedStateHandle(mapOf("kind" to LibraryKind.Movies.name, "collections" to true)),
            repository,
            sessions,
            settings,
            mockk(relaxed = true),
            mockk(relaxed = true),
            details,
            playback,
            home,
            mockk(relaxed = true),
            memory,
        )
        scheduler.advanceUntilIdle()
        model.firstRowShown()
        scheduler.advanceUntilIdle()
        return model
    }

    private companion object {
        /** Collections on the test instance. */
        const val SETS = 492
    }
}
