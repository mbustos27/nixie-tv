// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import android.app.Application
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.Lifecycle
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeContent
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsSync
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateManager
import io.github.glacier_jellyfin.androidtv.core.updater.UpdaterConfig
import io.github.glacier_jellyfin.androidtv.core.updater.WhatsNew
import io.github.glacier_jellyfin.androidtv.home.HomeScreen
import io.github.glacier_jellyfin.androidtv.home.HomeState
import io.github.glacier_jellyfin.androidtv.home.HomeViewModel
import io.github.glacier_jellyfin.androidtv.parental.PinGate
import io.github.glacier_jellyfin.androidtv.settings.RestoreViewModel
import io.github.glacier_jellyfin.androidtv.update.UpdateViewModel
import io.github.mbustos27.nixie.tv.data.ContinueWatchingRemovals
import io.github.mbustos27.nixie.tv.data.RemovalServer
import io.github.mbustos27.nixie.tv.data.ResumeSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * tv-home-two-rows (TV-S11, the owner: "continue watching then up next ... on one page"): the real HomeScreen with its
 * view models faked. Home is Continue watching, then Up next, and nothing else, on one 1080p screen; the hero shows the
 * focused card; an empty row says so in a line, and with both empty Home says to open a library from the top bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class HomeTwoRowsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun item(
        title: String,
        kind: ItemKind = ItemKind.Movie,
        show: String? = null,
        episode: Int? = null,
    ) = MediaItem(
        id = UUID.nameUUIDFromBytes("$show/$title".toByteArray()),
        kind = kind,
        title = title,
        sortName = null,
        year = 2026,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = 90,
        genres = listOf("Drama"),
        overview = "The keeper's logbook turns up on the wrong side of the bay.",
        parentTitle = show,
        seriesId = show?.let { UUID.nameUUIDFromBytes(it.toByteArray()) },
        seasonNumber = episode?.let { 1 },
        episodeNumber = episode,
        progress = 0.4f.takeIf { episode == null },
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

    private val harbour = item("A Quiet Harbor")
    private val circus = item("The Lantern", ItemKind.Episode, show = "The Iron Circus", episode = 2)
    private val signal = item("Arrival", ItemKind.Episode, show = "A Signal in Winter", episode = 3)
    private val forest = item("Roots", ItemKind.Episode, show = "Forest of Port Ellery", episode = 5)
    private val movies = Library(UUID.nameUUIDFromBytes("m".toByteArray()), "Movies", LibraryKind.Movies, 25_000, null)

    private val state = MutableStateFlow(HomeState(loading = true, userName = "Ana"))

    /** The flag the top bar's Home leaves on the Home it goes back to (FOCUS_NAV), taken once. */
    private var backHere = false

    private val removalServer = object : RemovalServer {
        override suspend fun snapshot(itemId: UUID) = ResumeSnapshot(1_000, false, 1, null)

        override suspend fun setPosition(itemId: UUID, positionTicks: Long) = Unit
    }

    private val viewModel = mockk<HomeViewModel>(relaxed = true) {
        every { state } returns this@HomeTwoRowsTest.state
        every { events } returns emptyFlow()
        every { pin } returns PinGate(scope, mockk<ParentalControl>(relaxed = true)) { }
        every { nixieRemovals } returns ContinueWatchingRemovals(scope, removalServer, { "s1/ana" })
        every { nixieRemoveAsk } returns MutableStateFlow(null)
    }

    private val updaterConfig = UpdaterConfig(versionName = "0.1.0", feedUrl = "", autoCheckDefault = false)
    private val updates = UpdateViewModel(
        UpdateManager(RuntimeEnvironment.getApplication(), updaterConfig),
        WhatsNew(RuntimeEnvironment.getApplication(), updaterConfig),
    )
    private val restore = RestoreViewModel(mockk<SettingsSync> { every { offer } returns MutableStateFlow(null) })

    /** The hero's slow zoom never stops: the clock moves only when the test says so. */
    private fun settle(ms: Long = 700) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun home(
        continueWatching: List<MediaItem>,
        upNext: List<MediaItem>,
        continueWatchingIn: Boolean = true,
        upNextIn: Boolean = true,
        // What Home showed before this stream: none of it is drawn now.
        others: Boolean = false,
    ) {
        state.value = HomeState(
            loading = false,
            content = HomeContent(
                libraries = listOf(movies),
                continueWatching = continueWatching,
                upNext = upNext,
                latest = if (others) listOf(movies to listOf(item("Salt Flats"))) else emptyList(),
                favorites = if (others) listOf(item("Desert Lights")) else emptyList(),
                latestLibraries = if (others) listOf(movies) else emptyList(),
            ),
            userName = "Ana",
            nixieContinueWatchingIn = continueWatchingIn,
            nixieUpNextIn = upNextIn,
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme {
                    HomeScreen(
                        onNavigate = {},
                        takeFocusNav = { backHere.also { backHere = false } },
                        viewModel = viewModel,
                        updates = updates,
                        restore = restore,
                    )
                }
            }
        }
        settle()
    }

    private fun text(id: Int) = compose.activity.getString(id)

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun hero() = compose.onNodeWithTag(NixieHomeTags.HERO)

    @Test
    fun `Home is Continue watching, then Up next, and nothing else`() {
        home(listOf(harbour, circus), listOf(signal, forest), others = true)

        val continueTop = compose.onNodeWithText(text(R.string.home_continue_watching)).getBoundsInRoot().top
        val upNextTop = compose.onNodeWithText(text(R.string.nixie_home_up_next)).getBoundsInRoot().top
        assertTrue("Continue watching first ($continueTop), Up next below it ($upNextTop)", continueTop < upNextTop)
        listOf("Salt Flats", "Desert Lights", text(R.string.home_my_media), text(R.string.home_favorites)).forEach {
            // "Favorites" is the top bar's entry too: only once, there.
            val count = compose.onAllNodesWithText(it).fetchSemanticsNodes().size
            assertTrue("$it is not on Home ($count)", count == if (it == text(R.string.home_favorites)) 1 else 0)
        }
        // The first focus is on Continue watching's first card.
        compose.onNodeWithText("A Quiet Harbor").assertIsFocused()
    }

    @Test
    fun `with nobody signed in Home draws no row and gives way at once`() {
        // tv-1.0-fixes (the owner's TV log): the signed-in profile's server removed in the server list above Home.
        home(listOf(harbour, circus), listOf(signal, forest))
        verify(exactly = 0) { viewModel.nixieLeave() }

        state.value = state.value.copy(nixieSignedOut = true)
        settle()

        verify(exactly = 1) { viewModel.nixieLeave() }
        assertTrue("no card of the profile that is gone", !shown("A Quiet Harbor") && !shown("A Signal in Winter"))
    }

    @Test
    fun `both rows fit on one 1080p screen, with no scrolling`() {
        home(listOf(harbour, circus), listOf(signal, forest))

        // In pixels: the screen is 1920 x 1080 (the theme's grid is the screen's).
        val screen = compose.onRoot().fetchSemanticsNode().size.height
        val lastCard = compose.onNodeWithText("Forest of Port Ellery").fetchSemanticsNode().boundsInRoot
        val rows = compose.onNodeWithTag(NixieHomeTags.ROWS).fetchSemanticsNode().boundsInRoot
        assertEquals(1080, screen)
        assertTrue("Up next's cards end on screen (${lastCard.bottom} <= $screen)", lastCard.bottom <= screen)
        assertTrue("the rows end on screen (${rows.bottom} <= $screen)", rows.bottom <= screen)
        assertTrue("the rows start under the top bar (${rows.top})", rows.top >= TOP_BAR_BOTTOM)
    }

    @Test
    fun `the hero shows the focused card`() {
        home(listOf(harbour, circus), listOf(signal, forest))
        hero().assert(hasContentDescription("A Quiet Harbor", substring = true))

        compose.onNodeWithText("Forest of Port Ellery").requestFocus()
        settle()

        compose.onNodeWithText("Forest of Port Ellery").assertIsFocused()
        // The show's name, its episode and the overview.
        hero().assert(hasContentDescription("Forest of Port Ellery. S1", substring = true))
        hero().assert(hasContentDescription("wrong side of the bay", substring = true))
    }

    @Test
    fun `an empty row says so in a line, once its answer is known`() {
        home(emptyList(), listOf(signal, forest))
        assertTrue(exists(NixieHomeTags.CONTINUE_EMPTY))
        assertTrue(shown(text(R.string.nixie_home_cw_empty)))
        assertTrue("Up next has cards: no line", !exists(NixieHomeTags.UP_NEXT_EMPTY))
        // The focus is on the first card there is: Up next's.
        compose.onNodeWithText("A Signal in Winter").assertIsFocused()
    }

    @Test
    fun `an empty Up next says so in a line`() {
        home(listOf(harbour), emptyList())
        assertTrue(exists(NixieHomeTags.UP_NEXT_EMPTY))
        assertTrue(shown(text(R.string.nixie_home_up_next_empty)))
        assertTrue(!exists(NixieHomeTags.CONTINUE_EMPTY))
    }

    @Test
    fun `a row whose answer has not come yet shows no line`() {
        home(listOf(harbour), emptyList(), upNextIn = false)
        assertTrue(!exists(NixieHomeTags.UP_NEXT_EMPTY))
        assertTrue(!exists(NixieHomeTags.ALL_EMPTY))
    }

    @Test
    fun `with both rows empty, Home says to open a library from the top bar`() {
        home(emptyList(), emptyList())

        assertTrue(exists(NixieHomeTags.ALL_EMPTY))
        assertTrue(shown(text(R.string.nixie_home_empty_title)))
        assertTrue(shown(text(R.string.nixie_home_empty_body)))
        assertTrue("no rows", !exists(NixieHomeTags.ROWS))
        assertTrue("the line names the top bar", text(R.string.nixie_home_empty_body).contains("top bar"))
        // Nothing to focus on the page: the top bar's Home entry has the focus.
        compose.onNodeWithText(text(R.string.nav_home)).assertIsFocused()
        assertTrue("no hero without a card", !exists(NixieHomeTags.HERO))
    }

    @Test
    fun `cards that come after the first draw take the focus while no key was pressed`() {
        home(emptyList(), emptyList(), continueWatchingIn = false, upNextIn = false)
        compose.onNodeWithText(text(R.string.nav_home)).assertIsFocused()

        cardsCome()

        compose.onNodeWithText("A Quiet Harbor").assertIsFocused()
    }

    @Test
    fun `cards that come while the user moves on the top bar leave the focus there`() {
        home(emptyList(), emptyList(), continueWatchingIn = false, upNextIn = false)
        press(KEYCODE_DPAD_RIGHT)
        val entry = compose.onAllNodes(isFocused()).fetchSemanticsNodes().single()

        cardsCome()

        assertEquals("the top bar's entry keeps the focus", entry.id, focusedNode().id)
    }

    @Test
    fun `the top bar's Home back to this Home keeps it on screen and asks for the two rows again`() {
        home(listOf(harbour, circus), listOf(signal, forest))
        compose.onNodeWithText("The Iron Circus").requestFocus()
        settle()

        // nixieBackToHome popped the pages above this Home and asked it to focus its bar entry; it comes to the front.
        backHere = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        settle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()

        verify(exactly = 1) { viewModel.nixieBackHere() }
        verify(exactly = 0) { viewModel.load() }
        compose.onNodeWithText(text(R.string.nav_home)).assertIsFocused()
        assertTrue("what it showed stays, no spinner", shown("The Iron Circus") && shown("A Signal in Winter"))
    }

    private fun cardsCome() {
        state.value = state.value.copy(
            content = state.value.content!!.copy(continueWatching = listOf(harbour, circus), upNext = listOf(signal)),
            nixieContinueWatchingIn = true,
            nixieUpNextIn = true,
        )
        settle()
    }

    private fun focusedNode() = compose.onAllNodes(isFocused()).fetchSemanticsNodes().single()

    private fun press(code: Int) {
        listOf(ACTION_DOWN, ACTION_UP).forEach { action ->
            compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0, 0, action, code, 0)))
            settle()
        }
    }

    private companion object {
        /** The top bar's foot: 34 dp from the top, 54 dp tall. */
        const val TOP_BAR_BOTTOM = 88f
    }

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }
}
