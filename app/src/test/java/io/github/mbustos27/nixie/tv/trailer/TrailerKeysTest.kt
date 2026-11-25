// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.trailer

import android.app.Application
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_MENU
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.Trailers
import io.github.glacier_jellyfin.androidtv.core.data.media.YouTubeTrailer
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.music.MusicController
import io.github.glacier_jellyfin.androidtv.trailer.TrailerScreen
import io.github.glacier_jellyfin.androidtv.trailer.TrailerViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
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
 * TV-S3 (the owner: the trailer controls feel "kind of weird"): the trailer player takes the movie player's keys. With
 * the controls hidden, OK pauses, Left and Right scrub by the profile's seek steps and jump after the pause, the menu
 * key shows the controls, and Back closes. The real TrailerScreen and TrailerViewModel on a YouTube trailer, whose
 * player reports are fed by hand (no page is loaded).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class TrailerKeysTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = RuntimeEnvironment.getApplication()
    private val movieId = UUID.nameUUIDFromBytes("movie".toByteArray())
    private var closed = 0
    private var density = Density(1f)

    private fun movie() = MediaItem(
        id = movieId,
        kind = ItemKind.Movie,
        title = "A Quiet Harbor",
        sortName = null,
        year = 2020,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = 100,
        runtimeMs = 6_000_000,
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

    private fun model(): TrailerViewModel {
        val details = mockk<DetailRepository>()
        coEvery { details.details(movieId) } returns ItemDetails(
            item = movie(),
            cast = emptyList(),
            tracks = null,
            trailers = Trailers(localCount = 0, youTube = listOf(YouTubeTrailer(VIDEO, "Trailer"))),
            seriesId = null,
            seasonId = null,
            premiereDate = null,
            seasonCount = null,
            episodeCount = null,
        )
        val settings = mockk<SettingsRepository>()
        every { settings.settings } returns MutableStateFlow(ProfileSettings())
        return TrailerViewModel(
            SavedStateHandle(mapOf("itemId" to movieId.toString())),
            app,
            details,
            mockk<PlaybackRepository>(),
            settings,
            mockk<MusicController>(relaxed = true),
            mockk<SeerrRepository>(),
        )
    }

    /** The trailer playing at 0:30 of 2:00, its controls gone. */
    private fun playing(): TrailerViewModel {
        val model = model()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme {
                    // The theme sets its own density (the design's 1920 px across): the sizes below are in it.
                    density = LocalDensity.current
                    TrailerScreen(onNavigate = {}, onBack = { closed++ }, viewModel = model)
                }
            }
        }
        settle()
        model.onYouTubeState(VIDEO, YT_PLAYING)
        model.onYouTubeProgress(VIDEO, START_MS, LENGTH_MS)
        settle(HIDE_MS)
        assertTrue("the controls still show", controlsGone())
        return model
    }

    private fun settle(ms: Long = 500) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun press(code: Int) {
        listOf(ACTION_DOWN, ACTION_UP).forEach { action ->
            compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0, 0, action, code, 0)))
            settle(KEY_MS)
        }
    }

    private fun label(id: Int) = app.getString(id)

    private fun controlsGone() = listOf(R.string.player_play, R.string.player_pause).all { id ->
        compose.onAllNodesWithContentDescription(label(id)).fetchSemanticsNodes().isEmpty()
    }

    @Test
    fun `OK on the bare picture pauses, and the seek bar takes the focus`() {
        val model = playing()
        press(KEYCODE_DPAD_CENTER)
        settle()
        assertFalse(model.state.value.playWhenReady)
        // The controls show, paused, with the focus on the seek bar (review: not merely off the play button): the one
        // focused node is the bar, as wide as the controls and 38 dp high.
        compose.onNodeWithContentDescription(label(R.string.player_play)).assertIsNotFocused()
        val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
        assertEquals(1, focused.size)
        val bar = focused.single().boundsInRoot
        with(density) {
            assertTrue("focused node ${bar.width} px wide", bar.width > SEEK_BAR_MIN_WIDTH.dp.toPx())
            assertEquals(SEEK_BAR_HEIGHT.dp.toPx(), bar.height, 1f)
        }
    }

    @Test
    fun `Left and Right on the bare picture scrub, and the jump follows the pause`() {
        val model = playing()
        press(KEYCODE_DPAD_RIGHT)
        assertFalse("the controls did not show", controlsGone())
        // Only the knob moves at first.
        assertEquals(START_MS, model.progress.value.positionMs)
        settle(SCRUB_MS)
        assertEquals(START_MS + ProfileSettings().playback.seekForward.ms, model.progress.value.positionMs)
        press(KEYCODE_DPAD_LEFT)
        settle(SCRUB_MS)
        assertEquals(
            START_MS + ProfileSettings().playback.let { it.seekForward.ms - it.seekBack.ms },
            model.progress.value.positionMs,
        )
    }

    @Test
    fun `the menu key brings the controls back`() {
        playing()
        press(KEYCODE_MENU)
        assertFalse("the controls did not show", controlsGone())
    }

    @Test
    fun `Back closes the trailer`() {
        playing()
        press(KEYCODE_BACK)
        settle()
        assertEquals(1, closed)
    }

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }

    private companion object {
        const val VIDEO = "vid00000001"
        const val YT_PLAYING = 1
        const val START_MS = 30_000L
        const val LENGTH_MS = 120_000L

        /** A YouTube trailer's controls leave 2 s after it starts; the fade takes a little more. */
        const val HIDE_MS = 3_000L
        const val KEY_MS = 50L

        /** The movie player's scrub pause is 1.2 s. */
        const val SCRUB_MS = 1_400L

        /** The seek bar on the design's 1920 dp across: the width less the 80 dp margins (1760), and its height. */
        const val SEEK_BAR_MIN_WIDTH = 1700
        const val SEEK_BAR_HEIGHT = 38
    }
}
