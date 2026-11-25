// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.detail

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryPage
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.detail.EpisodeRow
import io.github.glacier_jellyfin.androidtv.library.BrowseMemory
import io.github.glacier_jellyfin.androidtv.library.LibraryScreen
import io.github.glacier_jellyfin.androidtv.library.LibraryViewModel
import io.github.mbustos27.nixie.tv.home.LocalTopBarFavourites
import io.github.mbustos27.nixie.tv.library.NixieLibrariesPage
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * tv-gate-b (the basics gate, rule 12, "Back is instant"): Back returns to the card that was left, with its row scrolled
 * as it was, in one press. Navigation composes the page again on the way back; a title's episodes went back to the
 * first unwatched one with the focus on Play, the cast to the card before, a collection's titles to Play, the
 * Libraries page to its first card, and a library opened from Libraries took three presses (its tabs, the top bar,
 * then back). A real NavHost, Glacier's EpisodeRow and LibraryScreen and Nixie's Libraries page.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class CardReturnTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `Back from an episode focuses its card again, in the row as it was`() {
        val episodes = (1..EPISODES).map(::episode)
        show(
            page = {
                EpisodeRow(
                    episodes,
                    focusIndex = 0,
                    currentId = null,
                    onClick = { open() },
                    returnTo = rememberCardReturn(),
                )
            },
        )
        compose.onNodeWithText("Episode 1").requestFocus()
        repeat(EPISODES - 2) { press(Key.DirectionRight) }
        compose.onNodeWithText(LEFT_EPISODE).assertIsFocused()
        val left = compose.onNodeWithText(LEFT_EPISODE).getBoundsInRoot()

        press(Key.DirectionCenter)
        assertEquals(NEXT, nav.currentDestination?.route)
        back()

        compose.onNodeWithText(LEFT_EPISODE).assertIsDisplayed().assertIsFocused()
        assertEquals("the row as it was left", left.left, compose.onNodeWithText(LEFT_EPISODE).getBoundsInRoot().left)
    }

    @Test
    fun `Back from a library focuses its card on the Libraries page, not the first one`() {
        val libraries = listOf("Movies", "Shows", "Music").map { name ->
            Library(
                UUID.nameUUIDFromBytes(name.toByteArray()),
                name,
                LibraryKind.Movies,
                itemCount = 10,
                imageUrl = null,
            )
        }
        show(page = { NixieLibrariesPage(libraries, loading = false, onOpen = { open() }) })
        compose.onNodeWithText("Movies").requestFocus()
        press(Key.DirectionRight)
        compose.onNodeWithText("Shows").assertIsFocused()

        press(Key.DirectionCenter)
        assertEquals(NEXT, nav.currentDestination?.route)
        back()

        compose.onNodeWithText("Shows").assertIsFocused()
    }

    @Test
    fun `one Back from a library opened from another page returns to that page`() {
        val library = library()
        show(
            page = { Text("Libraries", Modifier.focusable()) },
            next = { LibraryScreen(onNavigate = {}, viewModel = library) },
        )
        compose.runOnIdle { nav.navigate(NEXT) }
        settle()
        compose.onNodeWithText("Title 05").requestFocus()
        compose.waitForIdle()

        back()

        assertEquals("one Back from the library's cards", PAGE, nav.currentDestination?.route)
    }

    @Test
    fun `one Back from a starred library opened from another page returns to that page`() {
        // The review: a library starred into the top bar, opened from Libraries (or a title), took several presses.
        val library = library()
        show(
            page = { Text("Libraries", Modifier.focusable()) },
            next = { LibraryScreen(onNavigate = {}, viewModel = library) },
            starred = listOf(shows()),
        )
        compose.runOnIdle { nav.navigate(NEXT) }
        settle()
        compose.onNodeWithText("Title 05").requestFocus()
        compose.waitForIdle()

        back()

        assertEquals("one Back from the starred library's cards", PAGE, nav.currentDestination?.route)
    }

    @Test
    fun `a starred library opened from its top bar entry keeps Back to its tabs first`() {
        val library = library(fromBar = true)
        show(
            page = { Text("Home", Modifier.focusable()) },
            next = { LibraryScreen(onNavigate = {}, viewModel = library) },
            starred = listOf(shows()),
        )
        compose.runOnIdle { nav.navigate(NEXT) }
        settle()
        compose.onNodeWithText("Title 05").requestFocus()
        compose.waitForIdle()

        back()

        assertEquals("Back from a bar page's cards stays on it (its tabs first)", NEXT, nav.currentDestination?.route)
    }

    private fun show(
        page: @Composable () -> Unit,
        next: @Composable () -> Unit = { NextPage() },
        starred: List<Library>? = null,
    ) {
        compose.setContent {
            nav = rememberNavController()
            CompositionLocalProvider(LocalInputModeManager provides DpadMode, LocalTopBarFavourites provides starred) {
                GlacierTheme {
                    NavHost(nav, startDestination = PAGE) {
                        composable(PAGE) { page() }
                        composable(NEXT) { next() }
                    }
                }
            }
        }
        settle()
    }

    @Composable
    private fun NextPage() = Text("Next page", Modifier.focusable())

    private fun open() = nav.navigate(NEXT)

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    /** The remote's Back, through the activity as the system sends it. */
    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SETTLE_MS))
        compose.waitForIdle()
    }

    /** The "Shows" library, as the top bar's stars list it. */
    private fun shows() = Library(UUID.nameUUIDFromBytes("Shows".toByteArray()), "Shows", LibraryKind.Movies, 10, null)

    /**
     * A library page opened from another page (its route names the library), with [TITLES] titles; [fromBar], by its
     * top bar entry.
     */
    private fun library(fromBar: Boolean = false): LibraryViewModel {
        val repository = mockk<LibraryRepository>(relaxed = true)
        val titles = (1..TITLES).map { title(it) }
        coEvery { repository.firstPage(any(), any()) } answers {
            LibraryPage(titles.take(secondArg()), TITLES, serverOrder = true)
        }
        coEvery { repository.page(any(), any(), any(), any(), any(), any()) } answers {
            LibraryPage(titles.drop(secondArg<Int>()).take(thirdArg()), TITLES, serverOrder = true)
        }
        coEvery { repository.knownLetters(any()) } returns null
        every { repository.progress } returns MutableStateFlow(null)
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.settings } returns MutableStateFlow(ProfileSettings())
        coEvery { settings.current() } returns ProfileSettings()
        val sessions = mockk<SessionManager>(relaxed = true)
        every { sessions.session } returns MutableStateFlow(null)
        val details = mockk<DetailRepository>(relaxed = true)
        every { details.playedChanged } returns MutableSharedFlow()
        val playback = mockk<PlaybackRepository>(relaxed = true)
        every { playback.stopped } returns MutableSharedFlow()
        val home = mockk<HomeRepository>(relaxed = true)
        every { home.kinds } returns MutableStateFlow(listOf(LibraryKind.Movies))
        val id = UUID.nameUUIDFromBytes("Shows".toByteArray()).toString()
        return LibraryViewModel(
            SavedStateHandle(
                mapOf("kind" to LibraryKind.Movies.name, "libraryId" to id, "title" to "Shows", "fromBar" to fromBar),
            ),
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

    private fun episode(n: Int) = item("Episode $n", ItemKind.Episode, season = 1, episode = n)

    private fun title(n: Int) = item("Title %02d".format(n), ItemKind.Movie)

    private fun item(
        title: String,
        kind: ItemKind,
        season: Int? = null,
        episode: Int? = null,
    ) = MediaItem(
        id = UUID.nameUUIDFromBytes(title.toByteArray()),
        kind = kind,
        title = title,
        sortName = null,
        year = null,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = null,
        genres = emptyList(),
        overview = null,
        parentTitle = null,
        seasonNumber = season,
        episodeNumber = episode,
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

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }

    private companion object {
        const val PAGE = "page"
        const val NEXT = "next"
        const val EPISODES = 12
        const val LEFT_EPISODE = "Episode 11"
        const val TITLES = 20
        const val SETTLE_MS = 500L
    }
}
