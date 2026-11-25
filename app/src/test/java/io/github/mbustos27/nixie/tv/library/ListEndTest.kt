// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
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
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.TopNav
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * tv-1.0-r1 (the quick basics check's Minor, on the release-like build): Right on the last card of a short last row (the
 * Collections page) or on a lone card (the Libraries page) moved the focus up a row, to the bar. It stays on the card,
 * or goes to the A–Z rail when the page has one (Glacier's rule for a group's short last row). Glacier's real
 * LibraryScreen and LibraryViewModel as the Collections page, and Nixie's Libraries page under the real top bar.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class ListEndTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `without the A-Z rail, Right on the last collection of a short last row stays there`() {
        // Sorted by date added: no rail.
        show { LibraryScreen(onNavigate = {}, viewModel = collections(LibrarySortChoice(LibrarySort.DateAdded, true))) }
        settle()
        compose.onNodeWithText("C").assertDoesNotExist()
        val last = "Collection %02d".format(COLLECTIONS)
        assertShortRowEnd(last, (1..COLLECTIONS).map { "Collection %02d".format(it) })

        compose.onNodeWithText(last).requestFocus()
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()

        compose.onNodeWithText(last).assertIsFocused()
    }

    @Test
    fun `with the A-Z rail, Right on the last collection of a short last row goes to the rail`() {
        // A to Z, as the page opens: the rail comes once the first screen is done.
        show { LibraryScreen(onNavigate = {}, viewModel = collections(sort = null)) }
        settle()
        val last = "Collection %02d".format(COLLECTIONS)
        assertShortRowEnd(last, (1..COLLECTIONS).map { "Collection %02d".format(it) })

        compose.onNodeWithText(last).requestFocus()
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()

        // The rail's letter of the card left (every letter is offered while the list goes in the server's order).
        compose.onNodeWithText("C").assertIsFocused()
    }

    @Test
    fun `Right on a lone library card in its row stays there, not up in the bar`() {
        val libraries = (0 until LIBRARIES).map { n ->
            val name = "Library ${'A' + n}"
            Library(
                UUID.nameUUIDFromBytes(name.toByteArray()),
                name,
                LibraryKind.Movies,
                itemCount = 100,
                imageUrl = null,
            )
        }
        show {
            Box(Modifier.fillMaxSize()) {
                NixieLibrariesPage(libraries, loading = false, onOpen = {})
                TopNav(
                    active = NavTarget.Libraries,
                    kinds = listOf(LibraryKind.Movies),
                    userName = "Ana",
                    onSelect = {},
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 34.dp),
                )
            }
        }
        val last = libraries.last().name
        assertShortRowEnd(last, libraries.map { it.name })

        compose.onNodeWithText(last).requestFocus()
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()

        compose.onNodeWithText(last).assertIsFocused()
    }

    /** The case itself: a card of [others] lies in a row above [last] and to its right, where Right used to go. */
    private fun assertShortRowEnd(last: String, others: List<String>) {
        val end = compose.onNodeWithText(last).getBoundsInRoot()
        val upRight = others.filter { it != last }.filter { name ->
            val card = compose.onNodeWithText(name).getBoundsInRoot()
            card.bottom < end.top && card.left > end.left
        }
        assertTrue("no card up and to the right of $last: its row is not short", upRight.isNotEmpty())
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) { GlacierTheme { content() } }
        }
        compose.waitForIdle()
    }

    /** Past the first screen (its first picture, or the wait for it), when the background work starts. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SETTLE_MS))
        compose.waitForIdle()
    }

    /** The Collections page with [COLLECTIONS] collections from a faked server, in the page's kept [sort] if any. */
    private fun collections(sort: LibrarySortChoice?): LibraryViewModel {
        val repository = mockk<LibraryRepository>(relaxed = true)
        val sets = (1..COLLECTIONS).map(::collection)
        coEvery { repository.firstPage(any(), any()) } answers {
            LibraryPage(sets.take(secondArg()), COLLECTIONS, serverOrder = true)
        }
        coEvery { repository.page(any(), any(), any(), any(), any(), any()) } answers {
            LibraryPage(sets.drop(secondArg<Int>()).take(thirdArg()), COLLECTIONS, serverOrder = true)
        }
        coEvery { repository.knownLetters(any()) } returns null
        every { repository.progress } returns MutableStateFlow(null)
        val kept = ProfileSettings(
            librarySorts = sort?.let { mapOf(LibraryQuery.NIXIE_COLLECTIONS_KEY to it) }.orEmpty(),
        )
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.settings } returns MutableStateFlow(kept)
        coEvery { settings.current() } returns kept
        val sessions = mockk<SessionManager>(relaxed = true)
        every { sessions.session } returns MutableStateFlow(null)
        val details = mockk<DetailRepository>(relaxed = true)
        every { details.playedChanged } returns MutableSharedFlow()
        val playback = mockk<PlaybackRepository>(relaxed = true)
        every { playback.stopped } returns MutableSharedFlow()
        val home = mockk<HomeRepository>(relaxed = true)
        every { home.kinds } returns MutableStateFlow(listOf(LibraryKind.Movies))
        return LibraryViewModel(
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
            BrowseMemory(flowOf(null), Dispatchers.Unconfined),
        )
    }

    private fun collection(n: Int) = MediaItem(
        id = UUID.nameUUIDFromBytes("collection-$n".toByteArray()),
        kind = ItemKind.Collection,
        title = "Collection %02d".format(n),
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
        /** Seven to a row on a 960 dp wide screen: a last row of three. */
        const val COLLECTIONS = 10

        /** Four to a row: the fifth alone in its row. */
        const val LIBRARIES = 5

        /** Past NixieLibraryTimings.PICTURE_WAIT_MS (2 s): the posters here have no picture. */
        const val SETTLE_MS = 2_500L
    }
}
