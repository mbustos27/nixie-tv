// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.media3.exoplayer.ExoPlayer
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.EpisodeNeighbours
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.TrackSelections
import io.github.glacier_jellyfin.androidtv.core.data.media.Trailers
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferences
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferencesRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayback
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import io.github.glacier_jellyfin.androidtv.player.PlayerViewModel
import io.github.glacier_jellyfin.androidtv.player.VersionChooser
import io.github.mbustos27.nixie.tv.data.skips.LearnedSkipStore
import io.github.mbustos27.nixie.tv.data.skips.SkipChain
import io.github.mbustos27.nixie.tv.data.skips.SkipLearner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * TV-S8: a box held a paused player all night in standby. Through the real PlayerViewModel, with the
 * repositories and Media3's player faked: the app going to the background releases the player and tells the server
 * where the title stands; coming back opens the same title at the same place, paused, also on a screen restored after
 * its process went away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerStandbyTest {

    private val scheduler = TestCoroutineScheduler()
    private val movieId = UUID.nameUUIDFromBytes("film".toByteArray())
    private val source = PlaybackSource(
        itemId = movieId,
        mediaSourceId = "file",
        playSessionId = "session",
        url = "http://127.0.0.1:1/none",
        isHls = false,
        method = PlaybackMethod.DirectPlay,
        headers = emptyMap(),
        audioIndex = 1,
        subtitleIndex = 3,
        audioTracks = emptyList(),
        subtitles = emptyList(),
    )

    private lateinit var playback: PlaybackRepository

    /** Every player the view model made, in order. */
    private val players = mutableListOf<ExoPlayer>()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private val movie = MediaItem(
        id = movieId,
        kind = ItemKind.Movie,
        title = "Film",
        sortName = null,
        year = null,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = 120,
        runtimeMs = 7_200_000,
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

    /** The player on the film, at [positionMs] once it plays; [saved] is the screen's saved state. */
    private fun player(positionMs: Long, saved: Map<String, Any?> = emptyMap()): PlayerViewModel {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.settings } returns MutableStateFlow(ProfileSettings())
        coEvery { settings.current() } returns ProfileSettings()
        val serverPreferences = mockk<ServerPreferencesRepository>(relaxed = true)
        every { serverPreferences.preferences } returns MutableStateFlow(ServerPreferences())
        val details = mockk<DetailRepository>(relaxed = true)
        coEvery { details.details(any()) } returns ItemDetails(
            item = movie,
            cast = emptyList(),
            tracks = null,
            trailers = Trailers(localCount = 0, youTube = emptyList()),
            seriesId = null,
            seasonId = null,
            premiereDate = null,
            seasonCount = null,
            episodeCount = null,
        )
        coEvery { details.neighbours(any(), any()) } returns EpisodeNeighbours(null, null)
        playback = mockk(relaxed = true)
        coEvery { playback.open(any(), any(), any(), any(), any(), any(), any()) } returns source
        coEvery { playback.segments(any()) } returns emptyList()
        every { playback.device } returns mockk(relaxed = true)
        every { playback.iec61937 } returns false
        val skips = mockk<SkipChain>(relaxed = true)
        every { skips.learner() } returns SkipLearner(LearnedSkipStore(null))
        coEvery { skips.withLocalSources(any(), any(), any()) } answers { secondArg() }
        coEvery { skips.lookupOnline(any(), any(), any()) } returns emptyList()
        every { skips.learnedKey(any()) } returns null
        mockkObject(GlacierPlayer)
        every { GlacierPlayer.create(any(), any()) } answers {
            val exo = mockk<ExoPlayer>(relaxed = true)
            every { exo.currentPosition } returns positionMs
            every { exo.duration } returns 7_200_000
            players += exo
            mockk<GlacierPlayback>(relaxed = true).also { every { it.player } returns exo }
        }
        val versions = mockk<VersionChooser>()
        coEvery { versions.choose(any()) } returns null
        val model = PlayerViewModel(
            SavedStateHandle(mapOf("itemId" to movieId.toString(), "fromStart" to false) + saved),
            RuntimeEnvironment.getApplication(),
            details,
            playback,
            TrackSelections(),
            settings,
            serverPreferences,
            mockk(relaxed = true),
            versions,
            mockk(relaxed = true),
            skips,
        )
        settle()
        return model
    }

    /** The view model's coroutines run on until they wait. */
    private fun settle() {
        scheduler.advanceTimeBy(TICKS_MS)
        scheduler.runCurrent()
    }

    /** The player shows its first frame: Media3's READY. */
    private fun ready(model: PlayerViewModel) {
        val listener = io.mockk.slot<androidx.media3.common.Player.Listener>()
        verify { players.last().addListener(capture(listener)) }
        listener.captured.onPlaybackStateChanged(androidx.media3.common.Player.STATE_READY)
        settle()
        assertFalse(model.state.value.loading)
    }

    @Test
    fun `the background releases the player and tells the server where the title stands`() {
        val model = player(positionMs = 1_000_000)
        ready(model)
        model.pause()
        model.nixieBackground()

        verify { players.single().release() }
        verify { playback.reportStopped(source, 1_000_000, false, false) }
        assertEquals(null, model.state.value.player)
    }

    @Test
    fun `back in front the same title opens where it stood, paused, with its tracks`() {
        val model = player(positionMs = 1_000_000)
        ready(model)
        model.nixieBackground()
        assertTrue(model.nixieForeground())
        settle()

        coVerify { playback.open(movieId, 1_000_000, 1, 3, any(), any(), any()) }
        assertEquals(2, players.size)
        verify { players.last().playWhenReady = false }
        verify(exactly = 0) { players.last().playWhenReady = true }
        // The first player started playing as asked.
        verify { players.first().playWhenReady = true }
    }

    @Test
    fun `before the first frame the open is made again as it was asked`() {
        val model = player(positionMs = 0)
        model.nixieBackground()
        verify { players.single().release() }
        assertTrue(model.nixieForeground())
        settle()

        // The first open (the resume point, null) once more, not "0".
        coVerify(exactly = 2) { playback.open(movieId, 0, null, null, any(), any(), any()) }
        verify { players.last().playWhenReady = false }
    }

    @Test
    fun `a screen restored after its process went away opens the saved title there, paused`() {
        val saved = SavedStateHandle()
        Standby.save(saved, movieId, 2_500_000)
        player(positionMs = 2_500_000, saved = saved.keys().associateWith { saved.get<Any>(it) })

        coVerify { playback.open(movieId, 2_500_000, any(), any(), any(), any(), any()) }
        verify { players.single().playWhenReady = false }
    }

    @Test
    fun `a closing screen, or one never sent to the background, is left alone`() {
        val model = player(positionMs = 1_000_000)
        ready(model)
        assertFalse(model.nixieForeground())
        model.stop()
        model.nixieBackground()
        assertFalse(model.nixieForeground())

        verify(exactly = 0) { players.single().release() }
        assertEquals(1, players.size)
    }

    @Test
    fun `each open's start goes to the detailed log as durations (design rule 10)`() {
        org.robolectric.shadows.ShadowLog.clear()
        val model = player(positionMs = 0)
        val listener = io.mockk.slot<androidx.media3.common.Player.Listener>()
        verify { players.last().addListener(capture(listener)) }
        listener.captured.onRenderedFirstFrame()
        listener.captured.onRenderedFirstFrame()

        val lines = org.robolectric.shadows.ShadowLog.getLogsForTag(StartTiming.TAG).map { it.msg }
        val line = lines.single()
        assertTrue(
            line,
            Regex("""Playback start: details \d+\.\d\d s, server answer .* s, first frame .* s""").matches(line),
        )
        assertFalse(line, "Film" in line || movieId.toString() in line)
        assertFalse(model.state.value.failed)
    }

    private companion object {
        /** Two ticks of the player's clock (500 ms each). */
        const val TICKS_MS = 1_000L
    }
}
