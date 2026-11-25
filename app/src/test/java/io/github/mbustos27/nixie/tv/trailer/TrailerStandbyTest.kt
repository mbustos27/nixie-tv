// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.trailer

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LocalTrailer
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.Trailers
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayback
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import io.github.glacier_jellyfin.androidtv.core.player.StreamRequest
import io.github.glacier_jellyfin.androidtv.music.MusicController
import io.github.glacier_jellyfin.androidtv.trailer.TrailerViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Design rule 5 for the trailer player: a server file playing when the app goes to the background (Home, standby) has
 * its player released, its place kept, and comes back there paused. The real TrailerViewModel, Media3 faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrailerStandbyTest {

    private val scheduler = TestCoroutineScheduler()
    private val movieId = UUID.nameUUIDFromBytes("movie".toByteArray())
    private val trailerId = UUID.nameUUIDFromBytes("teaser".toByteArray())
    private lateinit var playback: PlaybackRepository
    private val players = mutableListOf<ExoPlayer>()
    private val requests = mutableListOf<StreamRequest>()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun model(positionMs: Long): TrailerViewModel {
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
            trailers = Trailers(localCount = 1, youTube = emptyList()),
            seriesId = null,
            seasonId = null,
            premiereDate = null,
            seasonCount = null,
            episodeCount = null,
        )
        coEvery { details.localTrailers(movieId) } returns listOf(LocalTrailer(trailerId, "Teaser", 120_000, null))
        val settings = mockk<SettingsRepository>()
        every { settings.settings } returns MutableStateFlow(ProfileSettings())
        playback = mockk(relaxed = true)
        coEvery { playback.open(any(), any(), any(), any(), any(), any(), any()) } returns PlaybackSource(
            itemId = trailerId,
            mediaSourceId = "file",
            playSessionId = "session",
            url = "http://127.0.0.1:1/none",
            isHls = false,
            method = PlaybackMethod.DirectPlay,
            headers = emptyMap(),
            audioIndex = null,
            subtitleIndex = null,
            audioTracks = emptyList(),
            subtitles = emptyList(),
        )
        mockkObject(GlacierPlayer)
        val request = slot<StreamRequest>()
        every { GlacierPlayer.create(any(), capture(request)) } answers {
            requests += request.captured
            val exo = mockk<ExoPlayer>(relaxed = true)
            every { exo.currentPosition } returns positionMs
            players += exo
            mockk<GlacierPlayback>(relaxed = true).also { every { it.player } returns exo }
        }
        val model = TrailerViewModel(
            SavedStateHandle(mapOf("itemId" to movieId.toString())),
            RuntimeEnvironment.getApplication(),
            details,
            playback,
            settings,
            mockk<MusicController>(relaxed = true),
            mockk<SeerrRepository>(),
        )
        settle()
        return model
    }

    private fun settle() {
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
    }

    @Test
    fun `a server file is released in the background and comes back where it was, paused`() {
        val model = model(positionMs = 42_000)
        val listener = slot<Player.Listener>()
        verify { players.single().addListener(capture(listener)) }
        listener.captured.onPlaybackStateChanged(Player.STATE_READY)
        model.pause()
        model.nixieBackground()

        verify { players.single().release() }
        assertEquals(null, model.state.value.player)

        model.nixieForeground()
        settle()
        coVerify { playback.open(trailerId, 42_000, null, null, any(), any(), any()) }
        assertEquals(2, players.size)
        assertEquals(42_000L, requests.last().startPositionMs)
        verify { players.last().playWhenReady = false }
        verify(exactly = 0) { players.last().playWhenReady = true }
        assertTrue(model.state.value.loading)
    }

    @Test
    fun `nothing is released or opened again without a trip to the background`() {
        val model = model(positionMs = 42_000)
        model.nixieForeground()
        settle()
        model.close()
        model.nixieBackground()

        assertEquals(1, players.size)
        verify(exactly = 0) { players.single().release() }
    }
}
