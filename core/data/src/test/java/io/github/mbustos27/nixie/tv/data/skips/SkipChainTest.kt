// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.skips

import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.Trailers
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentPolicy
import io.github.mbustos27.nixie.tv.data.play.NixiePlaySettings
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review (M2): the chain's own switch keeps online lookups off; nothing leaves the box until it is turned on. */
class SkipChainTest {

    private val seriesId = UUID.nameUUIDFromBytes("show".toByteArray())

    private val episode = ItemDetails(
        item = MediaItem(
            id = UUID.nameUUIDFromBytes("s2e5".toByteArray()),
            kind = ItemKind.Episode,
            title = "Episode",
            sortName = null,
            year = null,
            communityRating = null,
            officialRating = null,
            runtimeMinutes = 47,
            runtimeMs = 2_832_000,
            genres = emptyList(),
            overview = null,
            parentTitle = "Show",
            seriesId = seriesId,
            seasonNumber = 2,
            episodeNumber = 5,
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
        ),
        cast = emptyList(),
        tracks = null,
        trailers = Trailers(localCount = 0, youTube = emptyList()),
        seriesId = seriesId,
        seasonId = null,
        premiereDate = null,
        seasonCount = null,
        episodeCount = null,
    )

    private val answer = HttpAnswer(
        200,
        emptyMap(),
        """{"intro":[{"start_ms":73440,"end_ms":91014}],"credits":[{"start_ms":2794788,"end_ms":null}]}""",
    )

    private fun chain(settings: NixiePlaySettings, http: HttpGet) = SkipChain(
        playSettings = { settings },
        playSettingsNow = { settings },
        profile = { "server" to "user" },
        showIds = { ShowInfo(mapOf("Tvdb" to "81189"), emptyList()) },
        store = LearnedSkipStore(null),
        online = IntroOnline(http, "NixieTV/test"),
    )

    private fun recording(requests: MutableList<String>) = HttpGet { url, _, _ ->
        requests += url
        answer
    }

    @Test
    fun `with lookups off (the default) nothing is asked online`() = runBlocking {
        val requests = mutableListOf<String>()
        val found = chain(NixiePlaySettings(), recording(requests))
            .lookupOnline(episode, emptyList(), SegmentPolicy())
        assertTrue(found.isEmpty())
        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun `with lookups on one request names the show's id, the season, the episode and the length`() = runBlocking {
        val requests = mutableListOf<String>()
        val found = chain(NixiePlaySettings(lookupOnline = true), recording(requests))
            .lookupOnline(episode, emptyList(), SegmentPolicy())
        assertEquals(setOf(SegmentKind.Intro, SegmentKind.Outro), found.mapTo(HashSet()) { it.kind })
        assertEquals(
            listOf("${IntroOnline.THEINTRODB}?tvdb_id=81189&season=2&episode=5&duration_ms=2832000"),
            requests,
        )
    }
}
