// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.EpisodeNeighbours
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.TrackSelections
import io.github.glacier_jellyfin.androidtv.core.data.media.Trailers
import io.github.glacier_jellyfin.androidtv.core.data.playback.MediaSegment
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
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
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Review (M2): the play stream's hooks in Glacier's PlayerViewModel, tested through the real view model with its
 * repositories and Media3's player faked: stopping in the closing credits reports the title watched (Back and leaving
 * the player), and the user's Jellyfin autoplay setting decides whether the next episode starts at the end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerHooksTest {

    private val scheduler = TestCoroutineScheduler()
    private val seriesId = UUID.nameUUIDFromBytes("show".toByteArray())
    private val episodeId = UUID.nameUUIDFromBytes("s1e4".toByteArray())
    private val nextId = UUID.nameUUIDFromBytes("s1e5".toByteArray())
    private val durationMs = 2_832_000L
    private val credits = MediaSegment(SegmentKind.Outro, 2_700_000, 2_830_000)
    private val source = PlaybackSource(
        itemId = episodeId,
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

    private lateinit var playback: PlaybackRepository
    private lateinit var details: DetailRepository
    private val listener = slot<Player.Listener>()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun episode(id: UUID, number: Int) = MediaItem(
        id = id,
        kind = ItemKind.Episode,
        title = "Episode $number",
        sortName = null,
        year = null,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = 47,
        runtimeMs = durationMs,
        genres = emptyList(),
        overview = null,
        parentTitle = "Show",
        seriesId = seriesId,
        seasonNumber = 1,
        episodeNumber = number,
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

    private fun detailsOf(item: MediaItem) = ItemDetails(
        item = item,
        cast = emptyList(),
        tracks = null,
        trailers = Trailers(localCount = 0, youTube = emptyList()),
        seriesId = seriesId,
        seasonId = null,
        premiereDate = null,
        seasonCount = null,
        episodeCount = null,
    )

    /** The player on episode 4 at [positionMs], with episode 5 next and credits that run to the end. */
    private fun player(positionMs: Long, serverAutoPlay: Boolean = true): PlayerViewModel {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.settings } returns MutableStateFlow(ProfileSettings())
        coEvery { settings.current() } returns ProfileSettings()
        val serverPreferences = mockk<ServerPreferencesRepository>(relaxed = true)
        every { serverPreferences.preferences } returns
            MutableStateFlow(ServerPreferences(nextEpisodeAutoPlay = serverAutoPlay))
        details = mockk(relaxed = true)
        coEvery { details.details(any()) } answers {
            val id = firstArg<UUID>()
            detailsOf(episode(id, if (id == nextId) 5 else 4))
        }
        coEvery { details.neighbours(any(), any()) } returns EpisodeNeighbours(null, episode(nextId, 5))
        playback = mockk(relaxed = true)
        coEvery { playback.open(any(), any(), any(), any(), any(), any(), any()) } returns source
        coEvery { playback.segments(any()) } returns listOf(credits)
        every { playback.device } returns mockk(relaxed = true)
        every { playback.iec61937 } returns false
        val skips = mockk<SkipChain>(relaxed = true)
        every { skips.learner() } returns SkipLearner(LearnedSkipStore(null))
        coEvery { skips.withLocalSources(any(), any(), any()) } answers { secondArg() }
        coEvery { skips.lookupOnline(any(), any(), any()) } returns emptyList()
        every { skips.learnedKey(any()) } returns null
        val exo = mockk<ExoPlayer>(relaxed = true)
        every { exo.currentPosition } returns positionMs
        every { exo.duration } returns durationMs
        every { exo.addListener(capture(listener)) } just Runs
        val glacierPlayback = mockk<GlacierPlayback>(relaxed = true)
        every { glacierPlayback.player } returns exo
        mockkObject(GlacierPlayer)
        every { GlacierPlayer.create(any(), any()) } returns glacierPlayback
        val versions = mockk<VersionChooser>()
        coEvery { versions.choose(any()) } returns null
        val model = PlayerViewModel(
            SavedStateHandle(mapOf("itemId" to episodeId.toString(), "fromStart" to false)),
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
        // Opening, the segments and the neighbours, then a tick of the player's clock.
        scheduler.advanceTimeBy(TICKS_MS)
        scheduler.runCurrent()
        return model
    }

    @Test
    fun `Back inside closing credits that run to the end reports the title watched`() {
        player(positionMs = 2_710_000).stop()
        verify { playback.reportStopped(source, 2_710_000, false, true) }
    }

    @Test
    fun `Back before the credits keeps the resume point`() {
        player(positionMs = 1_000_000).stop()
        verify { playback.reportStopped(source, 1_000_000, false, false) }
    }

    @Test
    fun `leaving the player inside the closing credits reports the title watched`() {
        val model = player(positionMs = 2_710_000)
        PlayerViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(model)
        verify { playback.reportStopped(source, 2_710_000, false, true) }
    }

    @Test
    fun `with the user's autoplay off the end of the episode ends playback`() {
        player(positionMs = durationMs, serverAutoPlay = false)
        listener.captured.onPlaybackStateChanged(Player.STATE_ENDED)
        scheduler.advanceTimeBy(TICKS_MS)
        scheduler.runCurrent()
        coVerify(exactly = 0) { details.details(nextId) }
    }

    @Test
    fun `with the user's autoplay on the next episode starts`() {
        player(positionMs = durationMs, serverAutoPlay = true)
        listener.captured.onPlaybackStateChanged(Player.STATE_ENDED)
        scheduler.advanceTimeBy(TICKS_MS)
        scheduler.runCurrent()
        coVerify { details.details(nextId) }
    }

    private companion object {
        /** Two ticks of the player's clock (500 ms each). */
        const val TICKS_MS = 1_000L
    }
}
