// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.skips

import io.github.glacier_jellyfin.androidtv.core.data.playback.MediaSegment
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroOnlineTest {

    /** Answers by URL prefix and keeps every request. */
    private class FakeHttp(val answers: MutableMap<String, HttpAnswer> = mutableMapOf()) : HttpGet {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()

        override fun get(
            url: String,
            headers: Map<String, String>,
            timeoutMs: Int,
        ): HttpAnswer {
            requests += url to headers
            return answers.entries.firstOrNull { url.startsWith(it.key) }?.value ?: throw IOException("no route")
        }
    }

    private var now = 1_000_000L
    private val all = setOf(SegmentKind.Intro, SegmentKind.Recap, SegmentKind.Outro)
    private val episode = LookupItem(movie = false, season = 2, episode = 5, runtimeMs = 1_420_000)
    private val show = ShowInfo(mapOf("Tvdb" to "81189", "Tmdb" to "1396", "Imdb" to "tt0903747"), listOf("Drama"))

    private val introDbAnswer = HttpAnswer(
        200,
        emptyMap(),
        """{"intro":[{"start_ms":null,"end_ms":62000}],"recap":[{"start_ms":0,"end_ms":0}],
           "credits":[{"start_ms":1380000,"end_ms":null}],"preview":[]}""",
    )

    private fun online(http: FakeHttp) = IntroOnline(http, "NixieTV/0.1.0", clock = { now })

    @Test
    fun `a request names only the show's public id, the episode and the runtime`() {
        val http = FakeHttp(mutableMapOf(IntroOnline.THEINTRODB to introDbAnswer))
        val found = online(http).lookup(episode, show, all)
        assertEquals(
            listOf(
                MediaSegment(SegmentKind.Intro, 0, 62_000, SegmentSource.Online),
                MediaSegment(SegmentKind.Outro, 1_380_000, 1_420_000, SegmentSource.Online),
            ),
            found,
        )
        val (url, headers) = http.requests.single()
        assertEquals("${IntroOnline.THEINTRODB}?tvdb_id=81189&season=2&episode=5&duration_ms=1420000", url)
        assertEquals(mapOf("Accept" to "application/json", "User-Agent" to "NixieTV/0.1.0"), headers)
    }

    @Test
    fun `answers are kept for the session, one request per episode`() {
        val http = FakeHttp(mutableMapOf(IntroOnline.THEINTRODB to introDbAnswer))
        val lookups = online(http)
        lookups.lookup(episode, show, all)
        lookups.lookup(episode, show, all)
        assertEquals(1, http.requests.size)
        lookups.reset()
        lookups.lookup(episode, show, all)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `a 429 pauses the service for its Retry-After, and forgetting does not get around it`() {
        val http = FakeHttp(mutableMapOf(IntroOnline.THEINTRODB to HttpAnswer(429, mapOf("retry-after" to "120"), "")))
        val lookups = online(http)
        assertTrue(lookups.lookup(episode, show, all).isEmpty())
        lookups.reset()
        now += 119_000
        assertTrue(lookups.lookup(episode, show, all).isEmpty())
        assertEquals(1, http.requests.size)
        now += 2_000
        http.answers[IntroOnline.THEINTRODB] = introDbAnswer
        assertEquals(2, lookups.lookup(episode, show, all).size)
        assertEquals(2, http.requests.size)
        assertEquals(IntroOnline.DEFAULT_PAUSE_MS, IntroOnline.retryAfterMs(emptyMap()))
        assertEquals(IntroOnline.MAX_PAUSE_MS, IntroOnline.retryAfterMs(mapOf("Retry-After" to "9999999")))
    }

    @Test
    fun `a failure is quiet and not kept`() {
        val http = FakeHttp(mutableMapOf(IntroOnline.THEINTRODB to HttpAnswer(503, emptyMap(), "")))
        val lookups = online(http)
        assertTrue(lookups.lookup(episode, show, all).isEmpty())
        assertTrue(lookups.lookup(episode, show, all).isEmpty())
        assertEquals(2, http.requests.size)
        assertTrue(online(FakeHttp()).lookup(episode, show, all).isEmpty())
    }

    @Test
    fun `nothing is asked without an id or an episode number`() {
        val http = FakeHttp()
        assertTrue(online(http).lookup(episode, ShowInfo(emptyMap(), emptyList()), all).isEmpty())
        assertTrue(online(http).lookup(episode.copy(episode = null), show, all).isEmpty())
        assertTrue(http.requests.isEmpty())
    }

    private val anime = ShowInfo(mapOf("Tvdb" to "267440", "AniList" to "16498"), listOf("Animation", "Anime"))
    private val aniSkipAnswer = HttpAnswer(
        200,
        emptyMap(),
        """{"found":true,"results":[
            {"interval":{"startTime":71.5,"endTime":161.5},"skipType":"op","skipId":"a","episodeLength":1420.0},
            {"interval":{"startTime":1330.0,"endTime":1420.0},"skipType":"ed","skipId":"b","episodeLength":1420.0}]}""",
    )

    @Test
    fun `anime the first source lacks are asked of AniSkip by the season's MyAnimeList id`() {
        val http = FakeHttp(
            mutableMapOf(
                IntroOnline.THEINTRODB to HttpAnswer(404, emptyMap(), ""),
                "${IntroOnline.ARM}ids?source=anilist" to HttpAnswer(200, emptyMap(), """{"myanimelist":16498}"""),
                IntroOnline.ANISKIP to aniSkipAnswer,
            ),
        )
        val found = online(http).lookup(episode.copy(season = 1, episode = 3), anime, all)
        assertEquals(
            listOf(
                MediaSegment(SegmentKind.Intro, 71_500, 161_500, SegmentSource.Online),
                MediaSegment(SegmentKind.Outro, 1_330_000, 1_420_000, SegmentSource.Online),
            ),
            found,
        )
        assertTrue(http.requests.last().first.startsWith("${IntroOnline.ANISKIP}16498/3?types=op"))
    }

    @Test
    fun `a split season gets no answer rather than the other part's`() {
        // TVDB season 3 maps to two MyAnimeList entries (Season 3 and Season 3 Part 2): the episode cannot pick its part.
        val http = FakeHttp(
            mutableMapOf(
                IntroOnline.THEINTRODB to HttpAnswer(404, emptyMap(), ""),
                "${IntroOnline.ARM}thetvdb" to HttpAnswer(
                    200,
                    emptyMap(),
                    """[{"myanimelist":35760,"thetvdb-season":3},{"myanimelist":38524,"thetvdb-season":3},
                       {"myanimelist":25777,"thetvdb-season":2}]""",
                ),
                IntroOnline.ANISKIP to aniSkipAnswer,
            ),
        )
        val lookups = online(http)
        assertTrue(lookups.lookup(episode.copy(season = 3, episode = 4), anime, all).isEmpty())
        assertFalse(http.requests.any { it.first.startsWith(IntroOnline.ANISKIP) })
        // Season 2 maps to one entry; the series' own AniList id is season 1's and is not used for it.
        assertEquals(2, lookups.lookup(episode.copy(season = 2, episode = 4), anime, all).size)
        assertTrue(http.requests.last().first.startsWith("${IntroOnline.ANISKIP}25777/4"))
        assertFalse(http.requests.any { it.first.contains("source=anilist") })
    }

    @Test
    fun `AniSkip is not asked for shows that are not anime`() {
        val http = FakeHttp(mutableMapOf(IntroOnline.THEINTRODB to HttpAnswer(404, emptyMap(), "")))
        assertTrue(online(http).lookup(episode, show, all).isEmpty())
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `the services' live answers as seen on 2026-11-21 read right`() {
        val introDb = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"tvdb_id":81189,"type":"tv","season":2,"episode":5,"intro":[{"start_ms":73440,"end_ms":91014}],
               "credits":[{"start_ms":2794788,"end_ms":null}]}""",
        ) as kotlinx.serialization.json.JsonObject
        assertEquals(
            listOf(
                MediaSegment(SegmentKind.Intro, 73_440, 91_014, SegmentSource.Online),
                MediaSegment(SegmentKind.Outro, 2_794_788, 2_832_000, SegmentSource.Online),
            ),
            IntroOnline.parseIntroDb(introDb, 2_832_000),
        )
        val notFound = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"found":false,"results":[],"message":"No skip times found","statusCode":404}""",
        )
        assertTrue(IntroOnline.parseAniSkip(notFound).isEmpty())
    }

    @Test
    fun `a movie is looked up by its own id without season or episode`() {
        assertEquals(
            "tmdb_id=603&duration_ms=8160000",
            IntroOnline.introDbQuery(mapOf("Tmdb" to "603", "Imdb" to "tt0133093"), null, null, 8_160_000),
        )
    }

    // tv-1.0-privacy: the log may go into a public issue; a lookup's ids name what was watched. Made-up ids.
    @Test
    fun `a lookup is logged by its service and answer, never its address or ids`() {
        val lines = mutableListOf<String>()
        val http = FakeHttp(mutableMapOf(IntroOnline.THEINTRODB to introDbAnswer))
        val made = ShowInfo(mapOf("Tvdb" to "880002", "Tmdb" to "990001", "Imdb" to "tt9900001"), listOf("Drama"))

        IntroOnline(http, "NixieTV/0.1.0", clock = { now }, log = { lines += it }).lookup(episode, made, all)

        val log = lines.joinToString("\n")
        for (id in listOf("880002", "990001", "tt9900001", "_id=", "theintrodb", "?")) {
            assertFalse("\"$id\" in the log:\n$log", id in log)
        }
        assertEquals(listOf("Online skip lookup: introdb answered 200"), lines)
    }
}
