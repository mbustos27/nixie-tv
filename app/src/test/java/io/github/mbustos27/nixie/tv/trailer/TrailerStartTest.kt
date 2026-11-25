// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.trailer

import android.app.Application
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.lifecycle.Lifecycle
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
import io.github.glacier_jellyfin.androidtv.trailer.makeYouTubePlayerAhead
import io.github.mbustos27.nixie.tv.play.StartTiming
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * TV-S9 (the owner: trailers "open small then open up full screen"): a YouTube trailer opens at its final size. Shown
 * with it, the controls moved the video into the small frame top right, and it grew to full screen when they left. Now
 * the first frame comes full screen with no controls; a key brings them (and the frame) as before. The real
 * TrailerScreen and TrailerViewModel, the player's reports fed by hand (no page is loaded).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class TrailerStartTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = RuntimeEnvironment.getApplication()
    private val movieId = UUID.nameUUIDFromBytes("movie".toByteArray())

    private fun model(): TrailerViewModel {
        val details = mockk<DetailRepository>()
        coEvery { details.details(movieId) } returns ItemDetails(
            item = MediaItem(
                id = movieId, kind = ItemKind.Movie, title = "A Quiet Harbor", sortName = null, year = 2020,
                communityRating = null, officialRating = null, runtimeMinutes = 100, runtimeMs = 6_000_000,
                genres = emptyList(), overview = null, parentTitle = null, seriesId = null, seasonNumber = null,
                episodeNumber = null, progress = null, remainingMinutes = null, unwatchedCount = null,
                isFavorite = false, played = false, childCount = null, quality = null, posterUrl = null,
                thumbUrl = null, backdropUrl = null,
            ),
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

    private fun settle(ms: Long = 100) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun controlsGone() = listOf(R.string.player_play, R.string.player_pause).all { id ->
        compose.onAllNodesWithContentDescription(app.getString(id)).fetchSemanticsNodes().isEmpty()
    }

    @Test
    fun `a YouTube trailer's first frame comes full screen, without the controls that shrink it`() {
        val model = model()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme { TrailerScreen(onNavigate = {}, onBack = {}, viewModel = model) }
            }
        }
        settle(500)
        model.onYouTubeState(VIDEO, YT_PLAYING)
        // The first frames after "playing": no controls, so the frame stays at full size from the start.
        for (step in 1..5) {
            settle(FRAME_MS)
            assertTrue("the controls showed ${step * FRAME_MS} ms after the first frame", controlsGone())
        }

        // A key brings the controls, as before.
        listOf(ACTION_DOWN, ACTION_UP).forEach { action ->
            compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0, 0, action, KEYCODE_DPAD_UP, 0)))
            settle(50)
        }
        settle(500)
        assertFalse("the controls did not show", controlsGone())
    }

    @Test
    fun `in the background the trailer's web view pauses, and it resumes on the app's return (design rule 5)`() {
        val model = model()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme { TrailerScreen(onNavigate = {}, onBack = {}, viewModel = model) }
            }
        }
        settle(500)
        model.onYouTubeState(VIDEO, YT_PLAYING)
        settle()
        val webView = webViews(compose.activity.window.decorView).single()
        assertFalse(shadowOf(webView).wasOnPauseCalled())

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        settle()
        assertTrue("not paused in the background", shadowOf(webView).wasOnPauseCalled())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()
        assertTrue("not resumed on return", shadowOf(webView).wasOnResumeCalled())
    }

    @Test
    fun `a trailer made ahead on the movie page is taken and started, not made again`() {
        ShadowLog.clear()
        var made: WebView? = null
        TrailerWarmup.make = { c, l -> makeYouTubePlayerAhead(c, l).also { made = it } }
        try {
            TrailerWarmup.prepare(app, started = true)
            val model = model()
            compose.mainClock.autoAdvance = false
            compose.setContent {
                CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                    GlacierTheme { TrailerScreen(onNavigate = {}, onBack = {}, viewModel = model) }
                }
            }
            settle(500)
            val shown = webViews(compose.activity.window.decorView).single()
            assertSame("a second web view was made", made, shown)
            // The video is named to YouTube only now, by the trailer screen.
            assertTrue(shadowOf(shown).lastEvaluatedJavascript.orEmpty().contains("startVideo(\"$VIDEO\")"))
            assertFalse(TrailerWarmup.ready)

            model.onYouTubeState(VIDEO, YT_PLAYING)
            settle()
            val line = ShadowLog.getLogsForTag(StartTiming.TAG).single().msg
            assertTrue(line, line.startsWith("Trailer start (YouTube, made ahead): "))
        } finally {
            TrailerWarmup.make = ::makeYouTubePlayerAhead
        }
    }

    private fun webViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }

    private companion object {
        const val VIDEO = "vid00000001"
        const val YT_PLAYING = 1
        const val FRAME_MS = 100L
    }
}
