// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.ProfileLock
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.SearchRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrRepository
import io.github.glacier_jellyfin.androidtv.search.SearchViewModel
import io.github.mbustos27.nixie.tv.data.search.NixieSearch
import io.github.mbustos27.nixie.tv.data.search.SearchRanking
import io.github.mbustos27.nixie.tv.data.search.SearchUpdate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TV-S4 (search results took far too long on a big server): the search page waits for a 300 ms pause in
 * typing, asks nothing for one letter, shows each kind as it arrives and drops the search for text typed over.
 * The real SearchViewModel, with Nixie's search and the repositories faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchTypingTest {

    private val scheduler = TestCoroutineScheduler()
    private val suggestion = item("Suggested film", ItemKind.Movie)
    private val nixieSearch = mockk<NixieSearch>()
    private val asked = mutableListOf<String>()
    private val answers = mutableMapOf<String, Flow<SearchUpdate>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        every { nixieSearch.results(any()) } answers {
            val term = firstArg<String>()
            asked += term
            answers[term] ?: flow { emit(update(term, complete = true)) }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /** The page; with [offlineAtFirst], its first try at the suggestions fails. */
    private fun model(offlineAtFirst: Boolean = false): SearchViewModel {
        val repository = mockk<SearchRepository>()
        var tries = 0
        coEvery { repository.suggestions() } answers {
            if (offlineAtFirst && tries++ == 0) throw IOException("offline")
            listOf(suggestion)
        }
        coEvery { repository.byIds(any()) } returns emptyList()
        val seerr = mockk<SeerrRepository>()
        coEvery { seerr.search(any()) } returns emptyList()
        val parental = mockk<ParentalControl>()
        every { parental.lock } returns MutableStateFlow(ProfileLock())
        val sessions = mockk<SessionManager>()
        every { sessions.session } returns MutableStateFlow<Session?>(null)
        return SearchViewModel(SavedStateHandle(), repository, seerr, parental, sessions, nixieSearch)
            .also { settle() }
    }

    private fun settle(ms: Long = 0) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    @Test
    fun `the search waits for a 300 ms pause in typing`() {
        val model = model()
        model.setQuery("Bri")
        settle(299)
        assertTrue(asked.isEmpty())
        settle(1)
        assertEquals(listOf("Bri"), asked)
    }

    @Test
    fun `one letter shows the suggestions and is searched only after a longer pause`() {
        // Review: one-character titles (M, X, 9) could no longer be found; typing on still never asks for one letter.
        val model = model()
        model.setQuery("Br")
        settle(1_000)
        model.setQuery("B")
        settle(300)
        assertEquals(listOf("Br"), asked)
        assertEquals(listOf(suggestion), model.state.value.results)
        assertNull(model.state.value.resultsFor)
        settle(SHORT_TERM_PAUSE_MS - 301)
        assertEquals(listOf("Br"), asked)
        settle(1)
        assertEquals(listOf("Br", "B"), asked)
    }

    @Test
    fun `typing on within the longer pause asks only for the longer text`() {
        val model = model()
        model.setQuery("B")
        settle(SHORT_TERM_PAUSE_MS - 100)
        model.setQuery("Br")
        settle(1_000)
        assertEquals(listOf("Br"), asked)
    }

    @Test
    fun `Enter searches one letter at once`() {
        val model = model()
        model.setQuery("M")
        settle(300)
        model.searchNow()
        settle()
        assertEquals(listOf("M"), asked)
        settle(1_000)
        assertEquals("asked once", listOf("M"), asked)
    }

    @Test
    fun `suggestions that did not load when the page opened load for one letter`() {
        // Review: the page showed an empty "Suggestions" when they had failed to load at first.
        val model = model(offlineAtFirst = true)
        assertTrue(model.state.value.failed)
        model.setQuery("Br")
        settle(1_000)
        model.setQuery("B")
        settle(300)
        assertEquals(listOf(suggestion), model.state.value.results)
        assertFalse(model.state.value.failed)
    }

    @Test
    fun `each kind shows as it arrives, the rest following`() {
        val show = item("Bright Harbour", ItemKind.Series)
        val film = item("Bright Lights", ItemKind.Movie)
        answers["Bri"] = flow {
            emit(update("Bri", complete = false, show))
            delay(2_000)
            emit(update("Bri", complete = true, show, film))
        }
        val model = model()
        model.setQuery("Bri")
        settle(300 + COALESCE_MS)
        assertEquals(listOf(show), model.state.value.results)
        assertTrue(model.state.value.loading)
        settle(2_000)
        assertEquals(setOf(show, film), model.state.value.results.toSet())
        assertFalse(model.state.value.loading)
    }

    @Test
    fun `answers close together reach the screen together, the better match first`() {
        // Review M1: episodes first, then a better show a moment later; shown one by one, the show would land in front
        // of cards already on screen (above the grid's top). Within the window they reach the screen as one step.
        val episode = item("Bridge", ItemKind.Episode)
        val show = item("Bri", ItemKind.Series)
        answers["Bri"] = flow {
            emit(update("Bri", complete = false, episode))
            delay(100)
            emit(update("Bri", complete = false, episode, show))
            delay(5_000)
            emit(update("Bri", complete = true, episode, show))
        }
        val model = model()
        model.setQuery("Bri")
        settle(300)
        settle(COALESCE_MS - 1)
        assertEquals("nothing yet", listOf(suggestion), model.state.value.results)
        settle(1)
        assertEquals(listOf(show, episode), model.state.value.results)
        assertTrue(model.state.value.loading)
    }

    @Test
    fun `typing on drops the search for the earlier text`() {
        var dropped = false
        answers["Bri"] = flow {
            try {
                delay(5_000)
                emit(update("Bri", complete = true, item("Bridge", ItemKind.Movie)))
            } finally {
                dropped = true
            }
        }
        val brig = item("Brigadoon", ItemKind.Movie)
        answers["Brig"] = flow { emit(update("Brig", complete = true, brig)) }
        val model = model()
        model.setQuery("Bri")
        settle(300)
        model.setQuery("Brig")
        settle(0)
        assertTrue("the search for Bri still runs", dropped)
        settle(10_000)
        assertEquals("Brig", model.state.value.resultsFor)
        assertEquals(listOf(brig), model.state.value.results)
    }

    private companion object {
        /** SearchViewModel's window for the steps of one search, and its pause before a single character. */
        const val COALESCE_MS = 150L
        const val SHORT_TERM_PAUSE_MS = 800L
    }

    private fun update(
        term: String,
        complete: Boolean,
        vararg found: MediaItem,
    ) = SearchUpdate(SearchRanking.rank(term, found.toList()), complete)

    private fun item(title: String, kind: ItemKind) = MediaItem(
        id = UUID.nameUUIDFromBytes(title.toByteArray()),
        kind = kind,
        title = title,
        sortName = null,
        year = null,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = null,
        runtimeMs = null,
        genres = emptyList(),
        overview = null,
        parentTitle = null,
        seriesId = null,
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
}
