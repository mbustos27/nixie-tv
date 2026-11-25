// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.ProfileLock
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.SearchRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrRepository
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierCard
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.search.SearchScreen
import io.github.glacier_jellyfin.androidtv.search.SearchViewModel
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import io.github.mbustos27.nixie.tv.data.search.NixieSearch
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * tv-gate-b (the basics gate, rule 12): Right on a row's last card stays there, as tv-1.0-r1 made it on the library grid
 * and the Libraries page. Home's Up next (two shows) sent it to the top bar's Home, up and to the right; the search
 * grid's lone last result (25 results, four to a row) sent it up a row.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class RowEndTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `Right on a short row's last card stays there, not up to the bar`() {
        show {
            Column(Modifier.fillMaxSize()) {
                // Where the top bar's entry was: above the row and to the right of its last card. Like TopNav, the bar
                // is entered through its page's entry (its own onEnter), a request that reaches the row as an entry.
                TopBarLike()
                MediaRow(title = "Up next") {
                    items(listOf("Show A", "Show B")) { name ->
                        GlacierCard(onClick = {}, modifier = Modifier.width(CARD.dp)) { Text(name) }
                    }
                }
            }
        }
        val last = compose.onNodeWithText("Show B").getBoundsInRoot()
        val bar = compose.onNodeWithTag(BAR_TAG).getBoundsInRoot()
        assertTrue(
            "the bar's entry lies up and to the right of the row's last card",
            bar.left > last.right && bar.bottom < last.top,
        )

        pressRightOn("Show B")

        compose.onNodeWithText("Show B").assertIsFocused()
    }

    @Test
    fun `Right in the middle of a row still moves to the next card`() {
        show {
            MediaRow(title = "Up next") {
                items(listOf("Show A", "Show B", "Show C")) { name ->
                    GlacierCard(onClick = {}, modifier = Modifier.width(CARD.dp)) { Text(name) }
                }
            }
        }

        pressRightOn("Show B")

        compose.onNodeWithText("Show C").assertIsFocused()
    }

    @Test
    fun `the focused last card keeps the focus when a card is added after it`() {
        val names = mutableStateListOf("Show A", "Show B")
        show {
            MediaRow(title = "Up next") {
                items(names, key = { it }) { name ->
                    GlacierCard(onClick = {}, modifier = Modifier.width(CARD.dp)) { Text(name) }
                }
            }
        }
        compose.onNodeWithText("Show B").requestFocus()
        compose.waitForIdle()

        // A refresh brings a show more: "Show B" is no longer the row's last card.
        compose.runOnIdle { names += "Show C" }
        compose.waitForIdle()

        compose.onNodeWithText("Show B").assertIsFocused()
        // And the new last card ends the row.
        pressRightOn("Show C")
        compose.onNodeWithText("Show C").assertIsFocused()
    }

    @Test
    fun `Right on the search grid's lone last card stays there, not up a row`() {
        // Five suggestions, four to a row: the fifth alone in the last row, the others up and to its right.
        val titles = (1..FIVE).map { "Result $it" }
        show { SearchScreen(onNavigate = {}, viewModel = search(titles)) }
        val last = compose.onNodeWithText(titles.last()).getBoundsInRoot()
        val upRight = compose.onNodeWithText(titles[1]).getBoundsInRoot()
        assertTrue(
            "a result lies up and to the right of the last one",
            upRight.left > last.left && upRight.bottom < last.top,
        )

        pressRightOn(titles.last())

        compose.onNodeWithText(titles.last()).assertIsFocused()
    }

    private fun pressRightOn(text: String) {
        compose.onNodeWithText(text).requestFocus()
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) { GlacierTheme { content() } }
        }
        compose.waitForIdle()
    }

    /** The search page showing [titles] as its suggestions (nothing typed yet), from faked repositories. */
    private fun search(titles: List<String>): SearchViewModel {
        val repository = mockk<SearchRepository>()
        coEvery { repository.suggestions() } returns titles.map(::item)
        coEvery { repository.byIds(any()) } returns emptyList()
        val seerr = mockk<SeerrRepository>()
        coEvery { seerr.search(any()) } returns emptyList()
        val parental = mockk<ParentalControl>()
        every { parental.lock } returns MutableStateFlow(ProfileLock())
        val sessions = mockk<SessionManager>()
        every { sessions.session } returns MutableStateFlow<Session?>(null)
        return SearchViewModel(
            SavedStateHandle(),
            repository,
            seerr,
            parental,
            sessions,
            mockk<NixieSearch>(relaxed = true),
        )
    }

    private fun item(title: String) = MediaItem(
        id = UUID.nameUUIDFromBytes(title.toByteArray()),
        kind = ItemKind.Movie,
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

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }

    private companion object {
        const val CARD = 200
        const val FIVE = 5
    }
}
