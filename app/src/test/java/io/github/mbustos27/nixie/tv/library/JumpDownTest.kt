// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.SavedStateHandle
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryPage
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * tv-gate-b (the basics gate; a real box too): Down right after an A–Z jump did nothing. The jump's page holds
 * ten rows; Down along it reached its last row while the next page was still on its way, found no row below and was
 * dropped, so the viewer had to press again. Down there now waits for the next titles and then goes down.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class JumpDownTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `Down on the jump page's last row, its next page still coming, goes down when the page comes`() {
        val next = CompletableDeferred<Unit>()
        val repository = repository(nextPage = { next.await() })
        val model = show(repository)
        val lastRowFirst = JUMPED + PAGE - COLUMNS
        walkDownTo(lastRowFirst)

        // The next page is on its way (asked for as the focus came near the end): Down now has no row to go to.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        next.complete(Unit)
        compose.waitForIdle()

        compose.onNodeWithText(film(lastRowFirst + COLUMNS)).assertIsFocused()
        val loaded = model.state.value.items.size
        check(loaded == 2 * PAGE) { "the next page did not come: $loaded titles" }
    }

    @Test
    fun `Down on the letter's real last row stays there and asks the server once`() {
        // The letter has only the jump's page: the next one comes back empty.
        val asked = AtomicInteger()
        val repository = repository(nextPage = { asked.incrementAndGet() }, letterEnd = true)
        show(repository)
        val lastRowFirst = JUMPED + PAGE - COLUMNS
        walkDownTo(lastRowFirst)
        val before = asked.get()

        repeat(DOWNS) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }

        compose.onNodeWithText(film(lastRowFirst)).assertIsFocused()
        // One more page asked for each Down at most, not again and again while the focus stays.
        val more = asked.get() - before
        assertTrue("$more pages asked for $DOWNS Downs at the letter's end", more <= DOWNS)
    }

    /** Jumps to M, then the focus from the jump's first title down to [index] (the first card of its row). */
    private fun walkDownTo(index: Int) {
        compose.onNodeWithText(film(JUMPED)).requestFocus()
        compose.waitForIdle()
        while (true) {
            val at = (JUMPED until index + 1 step COLUMNS).firstOrNull { focused(film(it)) }
            if (at == index) return
            check(at != null) { "the focus left the first column on the way down" }
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
    }

    private fun focused(text: String) = runCatching { compose.onNodeWithText(text).assertIsFocused() }.isSuccess

    private fun show(repository: LibraryRepository): LibraryViewModel {
        val model = model(repository)
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme { LibraryScreen(onNavigate = {}, viewModel = model) }
            }
        }
        compose.waitForIdle()
        model.jumpToLetter('M', COLUMNS)
        compose.waitForIdle()
        return model
    }

    private fun film(n: Int) = "Film %05d".format(n)

    private fun title(n: Int) = MediaItem(
        id = UUID.nameUUIDFromBytes("film-$n".toByteArray()),
        kind = ItemKind.Movie,
        title = film(n),
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

    /**
     * [TOTAL] films; a jump to M lands on a page from its letter (the [JUMPED]th film on) whose place the server never
     * says, so the next pages also come from the letter: each waits for [nextPage]; [letterEnd], none after the first.
     */
    private fun repository(nextPage: suspend () -> Unit, letterEnd: Boolean = false): LibraryRepository {
        val repository = mockk<LibraryRepository>(relaxed = true)
        coEvery { repository.firstPage(any(), any()) } answers {
            LibraryPage((0 until secondArg<Int>()).map(::title), TOTAL, serverOrder = true)
        }
        coEvery { repository.page(any(), any(), any(), any(), any(), any()) } answers {
            val start = secondArg<Int>()
            LibraryPage((start until minOf(start + thirdArg<Int>(), TOTAL)).map(::title), TOTAL, serverOrder = true)
        }
        coEvery { repository.page(any(), any(), any(), any(), any(), any(), 'M') } coAnswers {
            val from = secondArg<Int>()
            if (from > 0) nextPage()
            val end = if (letterEnd) JUMPED + PAGE else TOTAL
            val start = JUMPED + from
            LibraryPage((start until minOf(start + thirdArg<Int>(), end)).map(::title), null, serverOrder = true)
        }
        coEvery { repository.serverPosition(any(), any(), any()) } coAnswers { awaitCancellation() }
        coEvery { repository.availableLetters(any(), any()) } returns setOf('F', 'M')
        coEvery { repository.knownLetters(any()) } returns setOf('F', 'M')
        every { repository.progress } returns MutableStateFlow(null)
        return repository
    }

    private fun model(repository: LibraryRepository): LibraryViewModel {
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

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }

    private companion object {
        const val TOTAL = 400

        /** Where the jump to M lands (a row's start), the grid's columns on a 960 dp wide screen, and a page. */
        const val JUMPED = 70
        const val COLUMNS = 7
        const val PAGE = 70
        const val DOWNS = 3
    }
}
