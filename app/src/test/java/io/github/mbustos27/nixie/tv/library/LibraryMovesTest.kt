// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import android.app.Application
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.SavedStateHandle
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryPage
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ListGroup
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
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
import kotlinx.coroutines.CompletableDeferred
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
 * The library grid on the JVM: Glacier's real LibraryScreen and LibraryViewModel with 140 films. TV-S2 (the owner's TV
 * session): a move composes the cards it moves between, not the screen and every card on it (Compose's tracer counts
 * what runs while the focus goes right along a row), and Back from a title gives the focus back to the card left. The
 * review: a group the server's order brings back further down gets a heading of its own (the grid's keys).
 */
@OptIn(InternalComposeTracingApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class LibraryMovesTest {

    @get:Rule
    val compose = createComposeRule()

    /** How many times each composable ran (by its name), while counting. */
    private val ran = mutableMapOf<String, Int>()
    private var counting = false

    @After
    fun tearDown() {
        Composer.setTracer(Idle)
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

    /** Glacier's view model with [TOTAL] films ([film] makes each), the first page from the server. */
    private fun model(also: (LibraryRepository) -> Unit = {}, film: (Int) -> MediaItem = ::title): LibraryViewModel {
        val repository = mockk<LibraryRepository>(relaxed = true)
        coEvery { repository.firstPage(any(), any()) } answers {
            LibraryPage((0 until secondArg<Int>()).map(film), TOTAL, serverOrder = true)
        }
        coEvery { repository.page(any(), any(), any(), any(), any(), any()) } answers {
            val start = secondArg<Int>()
            LibraryPage((start until minOf(start + thirdArg<Int>(), TOTAL)).map(film), TOTAL, serverOrder = true)
        }
        coEvery { repository.availableLetters(any(), any()) } returns setOf('F')
        coEvery { repository.knownLetters(any()) } returns setOf('F')
        every { repository.progress } returns MutableStateFlow(null)
        also(repository)
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
        return LibraryViewModel(
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
            BrowseMemory(flowOf(null), Dispatchers.Unconfined),
        )
    }

    @Test
    fun `a move along a row composes the two cards it moves between, not the screen`() {
        val model = model()
        Composer.setTracer(
            object : CompositionTracer {
                override fun isTraceInProgress() = counting

                override fun traceEventStart(
                    key: Int,
                    dirty1: Int,
                    dirty2: Int,
                    info: String,
                ) {
                    val name = info.substringBefore(" (").substringAfterLast('.')
                    ran[name] = (ran[name] ?: 0) + 1
                }

                override fun traceEventEnd() = Unit
            },
        )
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme { LibraryScreen(onNavigate = {}, viewModel = model) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Film 00000").assertIsFocused()

        counting = true
        repeat(MOVES) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitForIdle()
        }
        counting = false
        compose.onNodeWithText("Film %05d".format(MOVES)).assertIsFocused()

        assertEquals("the screen composed again: $ran", 0, ran["LibraryScreen"] ?: 0)
        // Each move runs the cells of the card left and the card reached (which one Back returns to), nothing more.
        val cells = ran["captionFor"] ?: 0
        assertTrue("the cells ran $cells times for $MOVES moves: $ran", cells in 1..2 * MOVES)
    }

    @Test
    fun `Back to the library gives the focus back to the card it left`() {
        val model = model()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme { LibraryScreen(onNavigate = {}, viewModel = model) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Film 00000").assertIsFocused()
        repeat(MOVES) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitForIdle()
        }
        compose.onNodeWithText("Film %05d".format(MOVES)).assertIsFocused()
        // A title opened and Back: the screen is composed anew from what it saved, the view model kept.
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("Film %05d".format(MOVES)).assertIsFocused()
    }

    @Test
    // A tall screen, so the groups that come back are on it together, as when a few short groups pass by.
    @Config(qualifiers = "w960dp-h3000dp-port-television-xhdpi")
    fun `a group that comes back further down gets a heading of its own`() {
        // Highest rating first as a server may order it: titles without a rating among those at 0, on one screen.
        val groups = listOf(ListGroup.Stars(1), ListGroup.Stars(0), ListGroup.Unknown)
        val model = model { n -> title(n).copy(group = if (n < 8) groups[n % 3] else ListGroup.Unknown) }
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme { LibraryScreen(onNavigate = {}, viewModel = model) }
            }
        }
        // The grid takes every key of the titles near the screen at once: a heading key used twice threw here.
        compose.waitForIdle()
        compose.onNodeWithText("Film 00000").assertIsFocused()
    }

    @Test
    fun `TV-S13 - the rows that come in above a jump's letter leave its row where it was on screen`() {
        // A jump's page from its letter (the 71st title on), its place asked after it lands, then the rows above it.
        val placed = CompletableDeferred<Int>()
        val model = model(also = { repository ->
            coEvery { repository.page(any(), any(), any(), any(), any(), any(), 'M') } answers {
                val start = JUMPED + secondArg<Int>()
                LibraryPage((start until minOf(start + thirdArg<Int>(), TOTAL)).map(::title), null, serverOrder = true)
            }
            coEvery { repository.serverPosition(any(), any(), any()) } coAnswers { placed.await() }
        })
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme { LibraryScreen(onNavigate = {}, viewModel = model) }
            }
        }
        compose.waitForIdle()
        model.jumpToLetter('M', COLUMNS)
        compose.waitForIdle()
        val landed = compose.onNodeWithText("Film %05d".format(JUMPED)).getBoundsInRoot().top
        placed.complete(JUMPED)
        compose.waitForIdle()
        assertEquals(0, model.state.value.offset)
        assertEquals(landed, compose.onNodeWithText("Film %05d".format(JUMPED)).getBoundsInRoot().top)
        compose.onNodeWithText("Film %05d".format(JUMPED - 1)).assertExists()
    }

    private object Idle : CompositionTracer {
        override fun isTraceInProgress() = false

        override fun traceEventStart(
            key: Int,
            dirty1: Int,
            dirty2: Int,
            info: String,
        ) = Unit

        override fun traceEventEnd() = Unit
    }

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }

    private companion object {
        const val TOTAL = 140
        const val MOVES = 3

        /** TV-S13: where the jump lands (a row's start), and the grid's columns on a 960 dp wide screen. */
        const val JUMPED = 70
        const val COLUMNS = 7
    }
}
