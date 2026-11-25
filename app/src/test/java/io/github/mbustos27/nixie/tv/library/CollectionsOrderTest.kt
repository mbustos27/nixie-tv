// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import android.app.Application
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.SavedStateHandle
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryPage
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.LibrarySortChoice
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.library.BrowseMemory
import io.github.glacier_jellyfin.androidtv.library.LibraryScreen
import io.github.glacier_jellyfin.androidtv.library.LibraryViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * tv-1.0-r1 (the owner, 2026-11-23: "we should be able to reverse the direction of the sort in collections"): the
 * Collections page lists A to Z; its order button, reached with the remote, flips it to Z to A and says so, and the
 * direction is kept while a collection is opened and Back comes to the page (the view model kept, the screen composed
 * anew), and when the page is opened again (the page's own kept sort). Glacier's real LibraryScreen and LibraryViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class CollectionsOrderTest {

    @get:Rule
    val compose = createComposeRule()

    /** The profile's settings as the page saves them. */
    private var saved = ProfileSettings()

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `each sort's direction has its words`() {
        val words = LibrarySort.entries.associateWith { sort ->
            NixieCollectionsPage.orderLabel(sort, descending = false) to NixieCollectionsPage.orderLabel(sort, true)
        }
        assertEquals(R.string.nixie_order_a_to_z to R.string.nixie_order_z_to_a, words[LibrarySort.Title])
        assertEquals(R.string.nixie_order_oldest to R.string.nixie_order_newest, words[LibrarySort.DateAdded])
        assertEquals(R.string.nixie_order_oldest to R.string.nixie_order_newest, words[LibrarySort.Year])
        assertEquals(R.string.nixie_order_lowest to R.string.nixie_order_highest, words[LibrarySort.Rating])
        assertEquals(R.string.nixie_order_shortest to R.string.nixie_order_longest, words[LibrarySort.Runtime])
        words.forEach { (sort, pair) -> assertTrue("$sort: one word for both ways", pair.first != pair.second) }
    }

    @Test
    fun `the remote flips the page to Z to A, and Back to the page keeps it`() {
        val model = collections()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme { LibraryScreen(onNavigate = {}, viewModel = model) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(name(1)).assertIsFocused()
        assertFirstCard(name(1))

        // Up to the toolbar, then right along it to the order button.
        press(KEYCODE_DPAD_UP)
        var moves = 0
        while (focusedText() != "A to Z" && moves++ < TOOLBAR_MOVES) press(KEYCODE_DPAD_RIGHT)
        assertEquals("the order button is reached with the remote", "A to Z", focusedText())

        press(KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Z to A").assertIsFocused()
        assertTrue(model.state.value.query.descending)
        assertFirstCard(name(COLLECTIONS))
        assertEquals(LibrarySortChoice(LibrarySort.Title, true), saved.librarySorts[LibraryQuery.NIXIE_COLLECTIONS_KEY])

        // A collection opened and Back: the screen composed anew from what it saved, the view model kept.
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("Z to A").assertExists()
        assertFirstCard(name(COLLECTIONS))

        // Pressed again: A to Z.
        compose.onNodeWithText("Z to A").requestFocus()
        compose.waitForIdle()
        press(KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("A to Z").assertIsFocused()
        assertFirstCard(name(1))
    }

    @Test
    fun `the page opened again starts in the direction it was left in`() {
        saved = ProfileSettings(
            librarySorts = mapOf(LibraryQuery.NIXIE_COLLECTIONS_KEY to LibrarySortChoice(LibrarySort.Title, true)),
        )
        val model = collections()
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme { LibraryScreen(onNavigate = {}, viewModel = model) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Z to A").assertExists()
        assertFirstCard(name(COLLECTIONS))
    }

    @Test
    fun `a library page has no order button`() {
        val model = collections(page = false)
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme { LibraryScreen(onNavigate = {}, viewModel = model) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(NixieCollectionsPage.ORDER_TAG).assertDoesNotExist()
    }

    /** The grid's first card (top left) is [name]. */
    private fun assertFirstCard(name: String) {
        val first = (1..COLLECTIONS).map(::name).mapNotNull { title ->
            runCatching { title to compose.onNodeWithText(title).getBoundsInRoot() }.getOrNull()
        }.minWith(compareBy({ it.second.top }, { it.second.left })).first
        assertEquals(name, first)
    }

    private fun focusedText(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().single().let { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            ?: node.children.firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
            ?: ""
    }

    private fun press(code: Int) {
        listOf(ACTION_DOWN, ACTION_UP).forEach { action ->
            compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0, 0, action, code, 0)))
        }
        compose.waitForIdle()
    }

    private fun name(n: Int) = "Collection %02d".format(n)

    /** The Collections page (or, not [page], a movie library) over a faked server that lists in the query's direction. */
    private fun collections(page: Boolean = true): LibraryViewModel {
        val repository = mockk<LibraryRepository>(relaxed = true)
        val sets = (1..COLLECTIONS).map(::collection)
        fun ordered(query: LibraryQuery) = if (query.orderDescending) sets.asReversed() else sets
        coEvery { repository.firstPage(any(), any()) } answers {
            LibraryPage(ordered(firstArg()).take(secondArg()), COLLECTIONS, serverOrder = true)
        }
        coEvery { repository.page(any(), any(), any(), any(), any(), any()) } answers {
            LibraryPage(ordered(firstArg()).drop(secondArg<Int>()).take(thirdArg()), COLLECTIONS, serverOrder = true)
        }
        coEvery { repository.knownLetters(any()) } returns null
        every { repository.progress } returns MutableStateFlow(null)
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.settings } answers { MutableStateFlow(saved) }
        coEvery { settings.current() } answers { saved }
        coEvery { settings.update(any()) } answers { saved = firstArg<(ProfileSettings) -> ProfileSettings>()(saved) }
        val sessions = mockk<SessionManager>(relaxed = true)
        every { sessions.session } returns MutableStateFlow(null)
        val details = mockk<DetailRepository>(relaxed = true)
        every { details.playedChanged } returns MutableSharedFlow()
        val playback = mockk<PlaybackRepository>(relaxed = true)
        every { playback.stopped } returns MutableSharedFlow()
        val home = mockk<HomeRepository>(relaxed = true)
        every { home.kinds } returns MutableStateFlow(listOf(LibraryKind.Movies))
        return LibraryViewModel(
            SavedStateHandle(mapOf("kind" to LibraryKind.Movies.name, "collections" to page)),
            repository,
            sessions,
            settings,
            mockk(relaxed = true),
            mockk(relaxed = true),
            details,
            playback,
            home,
            mockk(relaxed = true),
            BrowseMemory(flowOf(null), Dispatchers.Unconfined),
        )
    }

    private fun collection(n: Int) = MediaItem(
        id = UUID.nameUUIDFromBytes("collection-$n".toByteArray()),
        kind = ItemKind.Collection,
        title = name(n),
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
        childCount = 3,
        quality = null,
        posterUrl = null,
        thumbUrl = null,
        backdropUrl = null,
    )

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }

    private companion object {
        /** Two rows of seven and a short third, all on the first screen. */
        const val COLLECTIONS = 10

        /** The toolbar's controls before the order button: the Collections tab, Unwatched, Favourites. */
        const val TOOLBAR_MOVES = 6
    }
}
