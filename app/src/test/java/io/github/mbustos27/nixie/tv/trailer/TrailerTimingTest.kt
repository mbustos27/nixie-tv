// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.trailer

import android.app.Application
import androidx.lifecycle.SavedStateHandle
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
import io.github.glacier_jellyfin.androidtv.music.MusicController
import io.github.glacier_jellyfin.androidtv.trailer.TrailerViewModel
import io.github.glacier_jellyfin.androidtv.trailer.YOUTUBE_STEPS
import io.github.mbustos27.nixie.tv.play.StartTiming
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * TV-S9 and design rule 10: a trailer's start is timed for the detailed log, each step and the first frame as a
 * duration, with no title, name or video id in the line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrailerTimingTest {

    private val movieId = UUID.nameUUIDFromBytes("movie".toByteArray())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ShadowLog.clear()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model(): TrailerViewModel {
        val details = mockk<DetailRepository>()
        coEvery { details.details(movieId) } returns ItemDetails(
            item = MediaItem(
                id = movieId, kind = ItemKind.Movie, title = TITLE, sortName = null, year = 2020,
                communityRating = null, officialRating = null, runtimeMinutes = 100, runtimeMs = 6_000_000,
                genres = emptyList(), overview = null, parentTitle = null, seriesId = null, seasonNumber = null,
                episodeNumber = null, progress = null, remainingMinutes = null, unwatchedCount = null,
                isFavorite = false, played = false, childCount = null, quality = null, posterUrl = null,
                thumbUrl = null, backdropUrl = null,
            ),
            cast = emptyList(),
            tracks = null,
            trailers = Trailers(localCount = 0, youTube = listOf(YouTubeTrailer(VIDEO, "Official trailer"))),
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
            RuntimeEnvironment.getApplication(),
            details,
            mockk<PlaybackRepository>(),
            settings,
            mockk<MusicController>(relaxed = true),
            mockk<SeerrRepository>(),
        )
    }

    private fun timingLines() = ShadowLog.getLogsForTag(StartTiming.TAG).map { it.msg }

    @Test
    fun `a YouTube trailer's start is logged once, step by step, as durations only`() {
        val model = model()
        YOUTUBE_STEPS.forEach { model.onYouTubeStep(VIDEO, it) }
        model.onYouTubeStep("another1234", "page")
        model.onYouTubeState(VIDEO, YT_PLAYING)
        model.onYouTubeState(VIDEO, YT_PLAYING)

        val line = timingLines().single()
        assertTrue(line, line.startsWith("Trailer start (YouTube): details "))
        val steps = Regex("([a-zA-Z ]+) \\d+\\.\\d\\d s").findAll(line.substringAfter(": ")).map {
            it.groupValues[1].trim()
        }.toList()
        assertEquals(listOf("details") + YOUTUBE_STEPS + "first frame", steps)
        for (secret in listOf(TITLE, VIDEO, "Official trailer", movieId.toString())) assertFalse(line, secret in line)
    }

    private companion object {
        const val TITLE = "A Quiet Harbor"
        const val VIDEO = "vid00000001"
        const val YT_PLAYING = 1
    }
}
