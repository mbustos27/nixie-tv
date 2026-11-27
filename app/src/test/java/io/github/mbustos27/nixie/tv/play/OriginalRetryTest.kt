// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
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
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.0.1 review: the original plays because the server refused anything smaller (PlaybackSource.smallerRefused), so the
 * link failing it had no smaller stream to step down to and ended playback at once. It is opened again where it was, a
 * few times, before it is an error. Through the real PlayerViewModel, its repositories and Media3's player faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OriginalRetryTest {

    private val scheduler = TestCoroutineScheduler()
    private val movieId = UUID.nameUUIDFromBytes("film".toByteArray())
    private val positionMs = 600_000L
    private lateinit var playback: PlaybackRepository
    private val listeners = mutableListOf<Player.Listener>()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun source(smallerRefused: Boolean) = PlaybackSource(
        itemId = movieId,
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
        streamBitrate = 5_700_000,
        smallerRefused = smallerRefused,
    )

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

    private fun player(smallerRefused: Boolean): PlayerViewModel {
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
        playback = mockk(relaxed = true)
        coEvery { playback.open(any(), any(), any(), any(), any(), any(), any()) } returns source(smallerRefused)
        coEvery { playback.segments(any()) } returns emptyList()
        every { playback.device } returns mockk(relaxed = true)
        every { playback.iec61937 } returns false
        val skips = mockk<SkipChain>(relaxed = true)
        every { skips.learner() } returns SkipLearner(LearnedSkipStore(null))
        coEvery { skips.withLocalSources(any(), any(), any()) } answers { secondArg() }
        coEvery { skips.lookupOnline(any(), any(), any()) } returns emptyList()
        every { skips.learnedKey(any()) } returns null
        // A new player for each open, all at the same position.
        mockkObject(GlacierPlayer)
        every { GlacierPlayer.create(any(), any()) } answers {
            val exo = mockk<ExoPlayer>(relaxed = true)
            every { exo.currentPosition } returns positionMs
            every { exo.duration } returns 7_200_000
            every { exo.addListener(any()) } answers { listeners += firstArg<Player.Listener>() }
            val glacierPlayback = mockk<GlacierPlayback>(relaxed = true)
            every { glacierPlayback.player } returns exo
            glacierPlayback
        }
        val versions = mockk<VersionChooser>()
        coEvery { versions.choose(any()) } returns null
        val model = PlayerViewModel(
            SavedStateHandle(mapOf("itemId" to movieId.toString(), "fromStart" to false)),
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
        advance(TICKS_MS)
        return model
    }

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    /** The link fails the playing stream (a timeout, as on a link too slow for the file), then the retry's wait. */
    private fun linkFails() {
        listeners.last().onPlayerError(
            PlaybackException("timed out", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
        )
        advance(RETRY_MS)
    }

    @Test
    fun `a link that fails the original opens it again where it was`() {
        val model = player(smallerRefused = true)
        linkFails()

        coVerify(exactly = 2) { playback.open(movieId, any(), any(), any(), any(), any(), any()) }
        coVerify { playback.open(movieId, positionMs, any(), any(), any(), any(), any()) }
        assertFalse(model.state.value.failed)
    }

    @Test
    fun `the original is opened again three times, then playback fails`() {
        val model = player(smallerRefused = true)
        repeat(3) { linkFails() }
        assertFalse("three retries", model.state.value.failed)
        linkFails()

        coVerify(exactly = 4) { playback.open(movieId, any(), any(), any(), any(), any(), any()) }
        assertTrue(model.state.value.failed)
    }

    @Test
    fun `a stream with a smaller one to step down to keeps the old way`() {
        // Not the refused case: a direct play the link fails steps down instead (lowerQuality), not a retry of the same.
        val model = player(smallerRefused = false)
        linkFails()

        assertFalse(model.state.value.failed)
        coVerify { playback.lowerBudget(5_700_000) }
    }

    private companion object {
        /** Two ticks of the player's clock (500 ms each). */
        const val TICKS_MS = 1_000L

        /** The retry's wait (3 s) and a tick. */
        const val RETRY_MS = 3_500L
    }
}
