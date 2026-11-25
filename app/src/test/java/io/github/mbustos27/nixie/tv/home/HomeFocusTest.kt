// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import android.app.Application
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.Lifecycle
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeContent
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
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
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Nixie: the focus on Home's own rows, on the real HomeScreen with its view models faked: Undo, Back to Home and
 * live updates leave the focus on the card, or its neighbour, never on the top bar or nowhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class HomeFocusTest {

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
        runtimeMs = 5_400_000,
        genres = emptyList(),
        overview = null,
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
    private val salt = item("Salt Flats")
    private val signal = item("Arrival", ItemKind.Episode, show = "A Signal in Winter", episode = 3)
    private val forest = item("Roots", ItemKind.Episode, show = "Forest of Port Ellery", episode = 5)
    private val paper = item("Tide", ItemKind.Episode, show = "Paper Moons", episode = 2)

    private val state = MutableStateFlow(
        HomeState(
            loading = false,
            content = HomeContent(
                libraries = emptyList(),
                continueWatching = listOf(harbour, circus, salt),
                upNext = listOf(signal, forest, paper),
                latest = emptyList(),
            ),
            userName = "Ana",
            nixieContinueWatchingIn = true,
            nixieUpNextIn = true,
        ),
    )

    private val removalServer = object : RemovalServer {
        override suspend fun snapshot(itemId: UUID) = ResumeSnapshot(1_000, false, 1, null)

        override suspend fun setPosition(itemId: UUID, positionTicks: Long) = Unit
    }
    private val removals = ContinueWatchingRemovals(scope, removalServer, { "s1/ana" })

    private val viewModel = mockk<HomeViewModel>(relaxed = true) {
        every { state } returns this@HomeFocusTest.state
        every { events } returns emptyFlow()
        every { pin } returns PinGate(scope, mockk<ParentalControl>(relaxed = true)) { }
        every { nixieRemovals } returns removals
        every { nixieRemoveAsk } returns MutableStateFlow(null)
    }

    private val updaterConfig = UpdaterConfig(versionName = "0.1.0", feedUrl = "", autoCheckDefault = false)
    private val updates = UpdateViewModel(
        UpdateManager(RuntimeEnvironment.getApplication(), updaterConfig),
        WhatsNew(RuntimeEnvironment.getApplication(), updaterConfig),
    )
    private val restore = RestoreViewModel(mockk<SettingsSync> { every { offer } returns MutableStateFlow(null) })

    /** The hero's slow zoom never stops: the clock moves only when the test says so. */
    private fun settle(ms: Long = 500) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private var onPage by mutableStateOf(false)

    /** Home as a page of the app: Back from another page composes it anew from its saved state. */
    private val pages = StateRestorationTester(compose)

    private fun home() {
        compose.mainClock.autoAdvance = false
        pages.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme {
                    Box {
                        HomeScreen(onNavigate = {}, viewModel = viewModel, updates = updates, restore = restore)
                        // Another page over Home, as the app's navigation shows one while Home stays composed.
                        if (onPage) Box(Modifier.fillMaxSize().testTag("page").focusable())
                    }
                }
            }
        }
        settle()
    }

    /** Off to a title's page and Back: Home is composed anew from its saved state. */
    private fun pageAndBack() {
        pages.emulateSavedInstanceStateRestore()
        settle()
    }

    private fun focus(text: String) {
        compose.onNodeWithText(text).requestFocus()
        settle()
    }

    private fun ok() {
        listOf(ACTION_DOWN, ACTION_UP).forEach { action ->
            compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0, 0, action, KEYCODE_DPAD_CENTER, 0)))
            settle()
        }
    }

    private fun bounds(text: String): Rect = compose.onNodeWithText(text).getBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    private fun focusedCount() = compose.onAllNodes(isFocused()).fetchSemanticsNodes().size

    @Test
    fun `Undo on a removed card puts the focus back on the card, with no scroll`() {
        home()
        focus("The Iron Circus")
        removals.remove(circus, index = 1)
        settle()
        focus("Select to undo")
        val before = bounds("Select to undo")
        ok()
        settle(2_000)
        compose.onNodeWithText("The Iron Circus").assertIsFocused()
        assertEquals(1, focusedCount())
        // The page did not scroll: the card is where its Undo card was (to the top, it would be ~370 px lower).
        assertEquals(before.center.y, bounds("The Iron Circus").center.y, ROW_SLACK)
    }

    @Test
    fun `Back to Home lands on the Up next card left`() {
        home()
        focus("Forest of Port Ellery")
        pageAndBack()
        compose.onNodeWithText("Forest of Port Ellery").assertIsFocused()
    }

    @Test
    fun `Back to Home lands on the Continue Watching episode left`() {
        home()
        focus("The Iron Circus")
        pageAndBack()
        compose.onNodeWithText("The Iron Circus").assertIsFocused()
    }

    @Test
    fun `Back to Home lands on the neighbour when the card left is gone`() {
        home()
        focus("Forest of Port Ellery")
        // Watched on its page: the show leaves Up next meanwhile.
        upNext(signal, paper)
        pageAndBack()
        compose.onNodeWithText("Paper Moons").assertIsFocused()
    }

    @Test
    fun `a live update that takes the focused Up next card hands the focus to its neighbour`() {
        home()
        focus("A Signal in Winter")
        // The show was finished on another device.
        upNext(forest, paper)
        compose.onNodeWithText("Forest of Port Ellery").assertIsFocused()
        assertEquals(1, focusedCount())
    }

    private fun upNext(vararg items: MediaItem) {
        state.value = state.value.copy(content = state.value.content!!.copy(upNext = items.toList()))
        settle()
    }

    @Test
    fun `a quick Back that left Home composed gives the focus back to the card`() {
        home()
        focus("A Quiet Harbor")
        // OK opens the page, Back 0.4 s later: Home was never left, but its focus went with the page.
        onPage = true
        settle()
        compose.onNodeWithTag("page").requestFocus()
        settle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        onPage = false
        settle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()
        compose.onNodeWithText("A Quiet Harbor").assertIsFocused()
    }

    private companion object {
        /** The cards' captions differ in height; a scroll to the top moves a row hundreds of pixels. */
        const val ROW_SLACK = 16f
    }

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }
}
