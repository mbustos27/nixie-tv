// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.skips

import io.github.glacier_jellyfin.androidtv.core.data.playback.MediaSegment
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** One answer of a GET; HTTP errors are answers too. */
class HttpAnswer(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

/** A plain GET, swapped for a fake in tests. Throws on a network failure. */
fun interface HttpGet {
    fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): HttpAnswer
}

/** [HttpGet] over `HttpURLConnection`: only the headers given, no cookies, no redirects to other hosts followed. */
object PlainHttpGet : HttpGet {
    private const val MAX_BYTES = 1_000_000
    private const val BUFFER_BYTES = 8192

    override fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): HttpAnswer {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readAtMost(MAX_BYTES).decodeToString() }.orEmpty()
            val answerHeaders = connection.headerFields.entries
                .filter { it.key != null && !it.value.isNullOrEmpty() }
                .associate { it.key to it.value.first() }
            return HttpAnswer(status, answerHeaders, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readAtMost(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (out.size() < max) {
            val read = read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

/** The show's public ids (TVDB, TMDB, IMDb, AniList, AniDB, MyAnimeList) and genres, from the user's own server. */
data class ShowInfo(val ids: Map<String, String>, val genres: List<String>)

/** The title being played: a movie, or an episode with its season and episode number. */
data class LookupItem(
    val movie: Boolean,
    val season: Int?,
    val episode: Int?,
    val runtimeMs: Long?,
)

/**
 * Opt-in online skip lookups, as desktop Nixie's `intro_online.py` (off by default): TheIntroDB (no key) and, for anime,
 * AniSkip by its MyAnimeList id (through ARM's id mapping when the server knows only another id). **Not** SkipMe.db, whose
 * notice keeps its data to its own plugin.
 *
 * What leaves the box, and only with lookups on: one GET naming the show's public id, the season, the episode and the
 * runtime, with a plain `User-Agent`. Never the server's address, the user, a token, a file path or a title.
 * TheIntroDB's terms shape the rest: one episode at a time (never a season ahead), answers kept in memory for the session
 * only, a 429 pauses that service for its Retry-After (300 s by default, a day at most) and nothing is retried inside a
 * lookup, about 3 s per request, and every failure is quiet. The caller runs it off the playback path.
 */
class IntroOnline(
    private val http: HttpGet,
    private val userAgent: String,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val log: (String) -> Unit = {},
) {
    private class Rejected : Exception()

    private val lock = Any()
    private val cache = HashMap<String, Any?>()
    private val pausedUntil = HashMap<String, Long>()

    /** Forgets the answers held in memory. A service's pause stays: forgetting must not get around a Retry-After. */
    fun reset() = synchronized(lock) { cache.clear() }

    /**
     * The segments for [item] from TheIntroDB, then AniSkip for anime when one of the [missing] kinds is still missing.
     * [show]: the series' ids and genres for an episode (an episode's own ids are not the show's), the movie's for a movie.
     * Blocking; never throws.
     */
    @Suppress("TooGenericExceptionCaught") // A lookup never fails playback: any failure is no answer.
    fun lookup(
        item: LookupItem,
        show: ShowInfo,
        missing: Set<SegmentKind>,
    ): List<MediaSegment> = try {
        lookupOrThrow(item, show, missing)
    } catch (e: Exception) {
        log("Online skip lookup failed: ${e.javaClass.simpleName}")
        emptyList()
    }

    private fun lookupOrThrow(
        item: LookupItem,
        show: ShowInfo,
        missing: Set<SegmentKind>,
    ): List<MediaSegment> {
        val season = if (item.movie) null else item.season
        val episode = if (item.movie) null else item.episode
        if (!item.movie && (season == null || episode == null)) return emptyList()
        val now = clock()
        val found = quietly("TheIntroDB") { introDb(show.ids, season, episode, item.runtimeMs, now) }.orEmpty()
        val out = found.filter { it.kind in missing }.toMutableList()
        val still = missing - found.mapTo(mutableSetOf()) { it.kind }
        if (still.any { it in ANISKIP_KINDS } && !item.movie && isAnime(show)) {
            quietly("AniSkip") { aniSkip(show.ids, season!!, episode!!, item.runtimeMs, now) }.orEmpty()
                .filter { it.kind in still }
                .forEach { out += it }
        }
        return out.map { it.copy(source = SegmentSource.Online) }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Quiet by design: a log line, no answer.
    private fun <T> quietly(service: String, call: () -> T): T? = try {
        call()
    } catch (e: Rejected) {
        log("$service is pausing lookups (rate limit)")
        null
    } catch (e: Exception) {
        log("$service lookup failed: ${e.javaClass.simpleName}")
        null
    }

    // -- TheIntroDB ---------------------------------------------------------------------------------------------------

    private fun introDb(
        ids: Map<String, String>,
        season: Int?,
        episode: Int?,
        runtimeMs: Long?,
        now: Long,
    ): List<MediaSegment>? {
        val query = introDbQuery(ids, season, episode, runtimeMs) ?: return null
        val key = "introdb?$query"
        cached<List<MediaSegment>>(key)?.let { return it }
        val data = getJson("introdb", "$THEINTRODB?$query", now)
        val result = (data as? JsonObject)?.let { parseIntroDb(it, runtimeMs) }.orEmpty()
        synchronized(lock) { cache[key] = result }
        return result
    }

    // -- AniSkip ------------------------------------------------------------------------------------------------------

    private fun aniSkip(
        ids: Map<String, String>,
        season: Int,
        episode: Int,
        runtimeMs: Long?,
        now: Long,
    ): List<MediaSegment>? {
        val mal = malId(ids, season, now) ?: return null
        val url = aniSkipUrl(mal, episode, runtimeMs)
        cached<List<MediaSegment>>(url)?.let { return it }
        val result = parseAniSkip(getJson("aniskip", url, now))
        synchronized(lock) { cache[url] = result }
        return result
    }

    /**
     * The season's MyAnimeList id. A MyAnimeList, AniList or AniDB id on the series is one entry, the first season's,
     * so it counts for season 1 only (desktop 1.5.1); otherwise ARM's per-season mapping of the TVDB or TMDB id. None
     * for specials (season 0) and for a split season (ARM maps it to more than one entry, with no episode offsets: the
     * episode cannot pick its part, and no Skip button is better than another episode's).
     */
    private fun malId(
        ids: Map<String, String>,
        season: Int,
        now: Long,
    ): Int? {
        if (season == 0) return null
        val first = season == 1
        val direct = ids.id("MyAnimeList") ?: ids.id("Mal")
        if (first && direct != null && direct.all(Char::isDigit)) return direct.toInt()
        if (first) malFromAnimeIds(ids, now)?.let { return it }
        return malFromSeason(ids, season, now)
    }

    /** The first season's MyAnimeList id through ARM, from the series' AniList or AniDB id. */
    private fun malFromAnimeIds(ids: Map<String, String>, now: Long): Int? {
        for ((key, source) in listOf("AniList" to "anilist", "AniDB" to "anidb")) {
            val id = ids.id(key) ?: continue
            val data = cachedJson("arm", "${ARM}ids?source=$source&include=myanimelist&id=${encode(id)}", now)
            ((data as? JsonObject)?.get("myanimelist") as? JsonPrimitive)?.intOrNull?.let { return it }
        }
        return null
    }

    /** The season's MyAnimeList id through ARM's mapping of the TVDB or TMDB id; null for a split season. */
    private fun malFromSeason(
        ids: Map<String, String>,
        season: Int,
        now: Long,
    ): Int? {
        for ((key, path, field) in listOf(
            Triple("Tvdb", "thetvdb", "thetvdb-season"),
            Triple("Tmdb", "themoviedb", "themoviedb-season"),
        )) {
            val id = ids.id(key) ?: continue
            val data = cachedJson("arm", "$ARM$path?id=${encode(id)}", now)
            val found = (data as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { (it[field] as? JsonPrimitive)?.intOrNull == season }
                .mapNotNullTo(mutableSetOf()) { (it["myanimelist"] as? JsonPrimitive)?.intOrNull }
            if (found.size > 1) return null
            if (found.size == 1) return found.single()
        }
        return null
    }

    // -- requests -----------------------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: String): T? = synchronized(lock) { cache[key] as T? }

    private fun cachedJson(
        service: String,
        url: String,
        now: Long,
    ): JsonElement? {
        synchronized(lock) { if (url in cache) return cache[url] as JsonElement? }
        val data = getJson(service, url, now)
        synchronized(lock) { cache[url] = data }
        return data
    }

    /** The JSON of a 200; null for anything else that is not an error; a 429 pauses the service. */
    @Suppress("ThrowsCount") // Paused, rate-limited and server error are three different answers.
    private fun getJson(
        service: String,
        url: String,
        now: Long,
    ): JsonElement? {
        synchronized(lock) { if (now < (pausedUntil[service] ?: Long.MIN_VALUE)) throw Rejected() }
        // Nixie (tv-1.0-privacy): the service and its answer, never the address: its ids say what was watched.
        val answer = http.get(url, mapOf("Accept" to "application/json", "User-Agent" to userAgent), TIMEOUT_MS)
        log("Online skip lookup: $service answered ${answer.status}")
        if (answer.status == HTTP_TOO_MANY_REQUESTS) {
            synchronized(lock) { pausedUntil[service] = now + retryAfterMs(answer.headers) }
            throw Rejected()
        }
        if (answer.status >= HTTP_SERVER_ERROR) throw IOException("server error ${answer.status}")
        if (answer.status != HTTP_OK) return null
        return Json.parseToJsonElement(answer.body)
    }

    companion object {
        const val TIMEOUT_MS = 3_000
        const val THEINTRODB = "https://api.theintrodb.org/v4/media"
        const val ANISKIP = "https://api.aniskip.com/v2/skip-times/"
        const val ARM = "https://arm.haglund.dev/api/v2/"
        const val DEFAULT_PAUSE_MS = 300_000L
        const val MAX_PAUSE_MS = 86_400_000L
        private const val HTTP_OK = 200
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
        private const val NANOS_PER_MS = 1_000_000L
        private const val MS_PER_S = 1000.0

        private val ANISKIP_KINDS = setOf(SegmentKind.Intro, SegmentKind.Outro, SegmentKind.Recap)
        private val INTRODB_KINDS = listOf(
            "intro" to SegmentKind.Intro,
            "recap" to SegmentKind.Recap,
            "credits" to SegmentKind.Outro,
            "preview" to SegmentKind.Preview,
        )
        private val ANISKIP_TYPES = mapOf(
            "op" to SegmentKind.Intro,
            "mixed-op" to SegmentKind.Intro,
            "ed" to SegmentKind.Outro,
            "mixed-ed" to SegmentKind.Outro,
            "recap" to SegmentKind.Recap,
        )

        private fun Map<String, String>.id(key: String): String? =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

        /** Anime by an anime id on the show, or the genre. */
        fun isAnime(show: ShowInfo): Boolean =
            listOf("AniList", "AniDB", "MyAnimeList", "Mal").any { show.ids.id(it) != null } ||
                show.genres.any { it.equals("anime", ignoreCase = true) }

        /**
         * The query of one TheIntroDB lookup: the show's id (TVDB, else TMDB, else IMDb), the season and episode (for an
         * episode), the runtime. Nothing else, ever. Null without an id.
         */
        fun introDbQuery(
            ids: Map<String, String>,
            season: Int?,
            episode: Int?,
            runtimeMs: Long?,
        ): String? {
            val id = listOf("Tvdb" to "tvdb_id", "Tmdb" to "tmdb_id", "Imdb" to "imdb_id")
                .firstNotNullOfOrNull { (key, name) -> ids.id(key)?.let { name to it } } ?: return null
            val params = mutableListOf(id)
            if (season != null && episode != null) {
                params += "season" to season.toString()
                params += "episode" to episode.toString()
            }
            if (runtimeMs != null && runtimeMs > 0) params += "duration_ms" to runtimeMs.toString()
            return params.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        }

        /**
         * TheIntroDB's answer: a missing start is 0, a missing end is the runtime, "0 to 0" (confirmed none) is dropped,
         * times are held to the runtime, the first segment of each kind is kept.
         */
        fun parseIntroDb(data: JsonObject, runtimeMs: Long?): List<MediaSegment> {
            val out = mutableListOf<MediaSegment>()
            for ((name, kind) in INTRODB_KINDS) {
                val entry = (data[name] as? JsonArray).orEmpty().firstNotNullOfOrNull { element ->
                    val o = element as? JsonObject ?: return@firstNotNullOfOrNull null
                    val start = o.millis("start_ms") ?: 0L
                    var end = o.millis("end_ms") ?: runtimeMs?.takeIf { it > 0 } ?: return@firstNotNullOfOrNull null
                    if (runtimeMs != null && runtimeMs > 0) end = minOf(end, runtimeMs)
                    if (end <= start) null else MediaSegment(kind, start, end, SegmentSource.Online)
                }
                entry?.let { out += it }
            }
            return out
        }

        private fun JsonObject.millis(key: String): Long? {
            val value = this[key] ?: return null
            if (value is JsonNull) return null
            val primitive = value as? JsonPrimitive ?: return null
            return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
        }

        fun aniSkipUrl(
            malId: Int,
            episode: Int,
            runtimeMs: Long?,
        ): String {
            val types = listOf("op", "ed", "mixed-op", "mixed-ed", "recap").joinToString("&") { "types=$it" }
            val length = String.format(java.util.Locale.ROOT, "%.3f", (runtimeMs ?: 0L) / MS_PER_S)
            return "$ANISKIP$malId/$episode?$types&episodeLength=$length"
        }

        /** AniSkip's answer, seconds to milliseconds, the first of each kind. */
        fun parseAniSkip(data: JsonElement?): List<MediaSegment> {
            val o = data as? JsonObject ?: return emptyList()
            if ((o["found"] as? JsonPrimitive)?.booleanOrNull == false) return emptyList()
            return (o["results"] as? JsonArray).orEmpty()
                .mapNotNull(::aniSkipSegment)
                .distinctBy { it.kind }
        }

        private fun aniSkipSegment(element: JsonElement): MediaSegment? {
            val result = element as? JsonObject ?: return null
            val kind = ANISKIP_TYPES[(result["skipType"] as? JsonPrimitive)?.contentOrNull] ?: return null
            val interval = result["interval"] as? JsonObject ?: return null
            val start = (interval["startTime"] as? JsonPrimitive)?.doubleOrNull ?: return null
            val end = (interval["endTime"] as? JsonPrimitive)?.doubleOrNull ?: return null
            if (end <= start) return null
            return MediaSegment(kind, (start * MS_PER_S).toLong(), (end * MS_PER_S).toLong(), SegmentSource.Online)
        }

        /** Retry-After in seconds, held between 1 s and a day; [DEFAULT_PAUSE_MS] when missing or unreadable. */
        fun retryAfterMs(headers: Map<String, String>): Long {
            val value = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value
            val seconds = value?.trim()?.toDoubleOrNull() ?: return DEFAULT_PAUSE_MS
            return (seconds * MS_PER_S).toLong().coerceIn(1_000L, MAX_PAUSE_MS)
        }
    }
}
