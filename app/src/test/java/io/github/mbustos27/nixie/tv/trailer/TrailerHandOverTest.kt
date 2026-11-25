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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * TV-S13: the trailer screen asks the server nothing after the press when the movie page handed it the title's
 * details; YouTube's player then starts at once. Without them (another title) it asks, as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrailerHandOverTest {

    private val movieId = UUID.nameUUIDFromBytes("movie".toByteArray())

    /** The server: every request it gets is noted; it answers the title's details. */
    private val server = mockk<DetailRepository>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { server.details(any()) } answers { details(firstArg()) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        TrailerHandOver.clear()
    }

    private fun details(id: UUID) = ItemDetails(
        item = MediaItem(
            id = id, kind = ItemKind.Movie, title = "A Quiet Harbor", sortName = null, year = 2020,
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

    private fun pressTrailer(): TrailerViewModel {
        val settings = mockk<SettingsRepository>()
        every { settings.settings } returns MutableStateFlow(ProfileSettings())
        return TrailerViewModel(
            SavedStateHandle(mapOf("itemId" to movieId.toString())),
            RuntimeEnvironment.getApplication(),
            server,
            mockk<PlaybackRepository>(),
            settings,
            mockk<MusicController>(relaxed = true),
            mockk<SeerrRepository>(),
        )
    }

    @Test
    fun `with the page's details handed over, the trailer asks the server nothing after the press`() {
        TrailerHandOver.offer(details(movieId))
        val model = pressTrailer()
        val state = model.state.value
        assertEquals(YouTubeTrailer(VIDEO, "Official trailer"), state.current)
        assertFalse(state.empty)
        assertEquals(movieId, state.item?.id)
        confirmVerified(server)
    }

    @Test
    fun `another title's details are not taken, the trailer asks for its own, as before`() {
        TrailerHandOver.offer(details(UUID.nameUUIDFromBytes("another".toByteArray())))
        val model = pressTrailer()
        assertEquals(movieId, model.state.value.item?.id)
        coVerify(exactly = 1) { server.details(movieId) }
    }

    @Test
    fun `details handed over long ago are asked for again`() {
        TrailerHandOver.offer(details(movieId), now = 0)
        assertEquals(null, TrailerHandOver.take(movieId, now = TrailerHandOver.FRESH_MS + 1))
        assertEquals(movieId, TrailerHandOver.take(movieId, now = TrailerHandOver.FRESH_MS)?.item?.id)
    }

    private companion object {
        const val VIDEO = "vid00000001"
    }
}
