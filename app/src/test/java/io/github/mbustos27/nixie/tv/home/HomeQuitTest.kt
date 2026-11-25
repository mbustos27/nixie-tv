// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import android.app.Application
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import io.github.glacier_jellyfin.androidtv.R
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
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * TV-S10: Back to Home while its rows are still loading, Back again: "Quit Nixie?" comes up, and Quit works at once,
 * whatever Home is doing; rows that come in under the dialog leave its focus alone. And the dialog owns the remote from
 * Back on: an OK that comes before Quit has the focus (the same frame as Back) quits, and never reaches the top bar's
 * entry behind it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class HomeQuitTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Home still loading: nothing on screen but the spinner. */
    private val state = MutableStateFlow(HomeState(loading = true, userName = "Ana"))

    private val removalServer = object : RemovalServer {
        override suspend fun snapshot(itemId: UUID) = ResumeSnapshot(1_000, false, 1, null)

        override suspend fun setPosition(itemId: UUID, positionTicks: Long) = Unit
    }

    private val viewModel = mockk<HomeViewModel>(relaxed = true) {
        every { state } returns this@HomeQuitTest.state
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

    private var navigated = 0

    private fun settle(ms: Long = 500) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun home() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme {
                    HomeScreen(
                        onNavigate = { navigated++ },
                        viewModel = viewModel,
                        updates = updates,
                        restore = restore,
                    )
                }
            }
        }
        settle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun ok(settleAfter: Boolean = true) = press(KEYCODE_DPAD_CENTER, settleAfter)

    private fun press(code: Int, settleAfter: Boolean = true) {
        listOf(ACTION_DOWN, ACTION_UP).forEach { action ->
            compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0, 0, action, code, 0)))
            if (settleAfter) settle()
        }
    }

    /**
     * Up to the top bar, Right past Libraries (TV-S11: one press right of Home) to the movie library, as in the hunt's
     * repro on the box.
     */
    private fun topBarMovies() {
        press(KEYCODE_DPAD_UP)
        press(KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Libraries").assertIsFocused()
        press(KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Movie").assertIsFocused()
    }

    private fun loaded() {
        state.value = HomeState(
            loading = false,
            content = HomeContent(libraries = emptyList(), continueWatching = listOf(film()), latest = emptyList()),
            userName = "Ana",
            nixieContinueWatchingIn = true,
            nixieUpNextIn = true,
        )
        settle(2_000)
    }

    private val quit get() = compose.activity.getString(R.string.home_exit_confirm)

    @Test
    fun `Back while Home is still loading, then OK on Quit, quits at once`() {
        home()
        back()
        compose.onNodeWithText(quit).assertIsFocused()

        ok()

        assertTrue("Quit worked while Home was loading", compose.activity.isFinishing)
    }

    @Test
    fun `rows coming in under the dialog leave Quit focused, and it still quits`() {
        home()
        back()
        loaded()
        compose.onNodeWithText(quit).assertIsFocused()

        ok()

        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun `Back and OK in the same frame, the top bar focused, quit and open nothing`() {
        home()
        loaded()
        topBarMovies()

        // The frame clock stands still: the dialog is asked for but not drawn, and Quit has no focus yet.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue(compose.onAllNodesWithText(quit).fetchSemanticsNodes().isEmpty())
        ok(settleAfter = false)

        assertTrue("OK before Quit had the focus quit", compose.activity.isFinishing)
        verify(exactly = 0) { viewModel.onNav(any()) }
        assertEquals(0, navigated)
    }

    @Test
    fun `Back still closes the dialog`() {
        home()
        loaded()
        topBarMovies()
        back()
        compose.onNodeWithText(quit).assertIsFocused()

        back()

        assertTrue(compose.onAllNodesWithText(quit).fetchSemanticsNodes().isEmpty())
        assertFalse(compose.activity.isFinishing)
    }

    private fun film() = MediaItem(
        id = UUID.nameUUIDFromBytes("harbor".toByteArray()),
        kind = ItemKind.Movie,
        title = "A Quiet Harbor",
        sortName = null,
        year = 2026,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = 90,
        genres = emptyList(),
        overview = null,
        parentTitle = null,
        seasonNumber = null,
        episodeNumber = null,
        progress = 0.4f,
        remainingMinutes = null,
        unwatchedCount = null,
        isFavorite = false,
        played = false,
        childCount = null,
        quality = null,
        posterUrl = null,
        thumbUrl = null,
        backdropUrl = "http://home.invalid/backdrop",
    )

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }
}
