// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import android.app.Application
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.data.media.AgeFilter
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.SearchRepository
import io.github.mbustos27.nixie.tv.data.search.NixieSearch
import io.github.mbustos27.nixie.tv.data.search.SearchKind
import io.github.mbustos27.nixie.tv.data.search.SearchUpdate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Nixie's search through the Jellyfin SDK against the TV test server (testing/fakeserver, profile jellyfin-12, on
 * 127.0.0.1): the suggestions the search page opens with (GET /Items/Suggestions, which the desktop's fake lacked: a
 * 404 and "Search failed" on the emulator), titles best match first, people, and the "Show N more" split. The server
 * runs the pinned desktop fake in testing/fakeserver/desktop, never another checkout. Skipped where Python is missing.
 *
 * TV-S4 (search results took far too long on a big server): one request per kind with a small limit and only the
 * sort name, each kind shown as it answers, and nothing asked for one letter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchServerTest {

    private lateinit var search: NixieSearch
    private lateinit var glacier: SearchRepository
    private var token = ""
    private var userId = ""
    private var serverId = ""

    @Before
    fun signIn() {
        // Quick Connect, approved through the server's harness: no credentials file is read.
        val device = """MediaBrowser Client="Nixie TV test", Device="jvm", DeviceId="search-test", Version="0.1.0""""
        val start = JSONObject(call("/QuickConnect/Initiate", "POST", null, device))
        call("/__harness/quickconnect/approve", "POST", JSONObject().put("code", start.getString("Code")), null)
        val auth = JSONObject(
            call(
                "/Users/AuthenticateWithQuickConnect",
                "POST",
                JSONObject().put("Secret", start.getString("Secret")),
                device,
            ),
        )
        token = auth.getString("AccessToken")
        // Jellyfin writes ids without dashes; the app keeps them as the SDK parses them.
        userId = dashed(auth.getJSONObject("User").getString("Id"))
        serverId = auth.getString("ServerId")
        val (sessions, ageFilter) = signedIn(HttpClientOptions())
        glacier = SearchRepository(sessions, ageFilter)
        search = NixieSearch(sessions, ageFilter)
    }

    /** The signed-in profile with the SDK's HTTP [options], and an age filter that lets everything through. */
    private fun signedIn(options: HttpClientOptions): Pair<SessionManager, AgeFilter> {
        val api = createJellyfin {
            clientInfo = ClientInfo(name = "Nixie TV test", version = "0.1.0")
            // Robolectric has no ANDROID_ID for the SDK to make one from.
            deviceInfo = DeviceInfo(id = "search-test", name = "jvm")
            context = RuntimeEnvironment.getApplication()
        }.createApi(baseUrl = url, accessToken = token, httpClientOptions = options)
        val session = Session(
            StoredServer(serverId, "Test server", url, "12.4.0", lastUsedAt = 0),
            StoredUser(serverId, userId, "test", null, token, lastUsedAt = 0),
            api,
        )
        val sessions = mockk<SessionManager>()
        every { sessions.session } returns MutableStateFlow(session)
        val ageFilter = mockk<AgeFilter>()
        coEvery { ageFilter.screen(any()) } answers { firstArg() }
        return sessions to ageFilter
    }

    @Test
    fun `the search page opens with the server's suggestions`() = runBlocking {
        val suggestions = glacier.suggestions()
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.all { it.kind == ItemKind.Movie || it.kind == ItemKind.Series })
    }

    @Test
    fun `titles come best match first, with a chip for each kind found`() = runBlocking {
        val ranked = search.search("bright")
        val shown = ranked.visible(chip = null, expanded = false).map { it.title }
        // Titles that start with the word (a leading "The" set aside) come before those where only a later word starts
        // with it ("Carnival of Brightwater"), whatever the server's order.
        val starts = shown.map { it.removePrefix("The ").startsWith("Bright") }
        assertEquals(shown.toString(), starts.sortedDescending(), starts)
        assertTrue(starts.first())
        assertTrue("Carnival of Brightwater" in shown)
        assertTrue(ranked.chips.containsAll(listOf(SearchKind.Shows, SearchKind.Movies)))
        assertTrue(ranked.visible(SearchKind.Shows, expanded = false).all { it.kind == ItemKind.Series })
    }

    @Test
    fun `Bri shows the shows and films first, then one episode of each show, naming the show`() = runBlocking {
        // Bug hunt M4: on this server "Bri" filled the first rows with episodes called "Bridge".
        val shown = search.search("Bri").visible(chip = null, expanded = false)
        val leading = shown.takeWhile { it.kind != ItemKind.Episode }
        assertTrue(shown.toString(), leading.isNotEmpty())
        // Every show and film whose title starts with "Bri" comes before the first episode. (TV-S4: each kind now has its
        // own request, so films and shows where only a later word starts with "Bri" come too; they follow the episodes.)
        val starting = shown.filter { it.kind != ItemKind.Episode && it.title.removePrefix("The ").startsWith("Bri") }
        assertTrue(shown.toString(), leading.containsAll(starting))
        val episodes = shown.filter { it.kind == ItemKind.Episode }
        assertTrue(shown.toString(), episodes.isNotEmpty())
        assertEquals(episodes.map { it.seriesId }.distinct().size, episodes.size)
        assertTrue(episodes.all { it.parentTitle != null && it.seasonNumber != null && it.episodeNumber != null })
    }

    @Test
    fun `a person is found by name, their titles wait behind Show N more`() = runBlocking {
        val ranked = search.search("ada abbott")
        val shown = ranked.visible(chip = null, expanded = false)
        assertEquals(listOf("Ada Abbott"), shown.map { it.title })
        assertEquals(ItemKind.Person, shown.single().kind)
        val hidden = ranked.hidden(chip = null, expanded = false)
        assertTrue(hidden > 0)
        val all = ranked.visible(chip = null, expanded = true)
        assertEquals(1 + hidden, all.size)
        assertTrue(all.any { it.title == "The Forgotten Crown" && it.kind == ItemKind.Movie })
        assertTrue(SearchKind.People in ranked.chips)
    }

    @Test
    fun `each kind shows as soon as it answers, before a slow one is back`() = runBlocking {
        // The films take SLOW_MS; the shows found for "bright" must not wait for them.
        latency("""/Items\?.*includeItemTypes=Movie(&|$)""" to SLOW_MS)
        try {
            val started = System.nanoTime()
            val steps = mutableListOf<Pair<Long, SearchUpdate>>()
            search.results("bright").collect { steps += (System.nanoTime() - started) / NANOS_PER_MS to it }
            val shows = steps.first { (_, step) -> step.ranked.items.any { it.kind == SearchKind.Shows } }
            assertTrue("shows after ${shows.first} ms", shows.first < SLOW_MS)
            assertTrue(steps.none { (_, step) -> step.complete && step !== steps.last().second })
            val (doneAt, done) = steps.last()
            assertTrue(done.complete && doneAt >= SLOW_MS)
            assertTrue(done.ranked.items.any { it.kind == SearchKind.Movies })
        } finally {
            latency()
        }
    }

    @Test
    fun `one request per kind, with a small limit, the sort name and no total`() = runBlocking {
        search.search("silent")
        val asked = requests().filter { it.optString("route") == "items" && it.query("searchterm") == "silent" }
        val kinds = asked.map { it.query("includeitemtypes").orEmpty() }
        assertEquals(kinds.toString(), listOf("BoxSet", "Episode", "Movie", "MusicAlbum", "Series"), kinds.sorted())
        asked.forEach { request ->
            assertTrue(request.toString(), request.query("limit")!!.toInt() <= MAX_KIND_LIMIT)
            assertEquals("SortName", request.query("fields"))
            assertEquals("false", request.query("enabletotalrecordcount"))
            assertEquals("Primary", request.query("enableimagetypes"))
            assertEquals("1", request.query("imagetypelimit"))
        }
        assertEquals(1, requests().count { it.optString("route") == "persons" && it.query("searchterm") == "silent" })
    }

    @Test
    fun `a failed kind and a people request timing out after the titles showed leave the rest`() = runBlocking {
        // Review M2: one failed request failed the whole search and wiped what was on screen.
        val (sessions, ageFilter) = signedIn(HttpClientOptions(requestTimeout = TIMEOUT, socketTimeout = TIMEOUT))
        val search = NixieSearch(sessions, ageFilter)
        latency("""/Items\?.*includeItemTypes=Movie(&|$)""" to "status:500", "/Persons" to "hang")
        try {
            val started = System.nanoTime()
            val steps = mutableListOf<Pair<Long, SearchUpdate>>()
            search.results("bright").collect { steps += (System.nanoTime() - started) / NANOS_PER_MS to it }
            val shows = steps.first { (_, step) -> step.ranked.items.any { it.kind == SearchKind.Shows } }
            assertTrue("shows after ${shows.first} ms", shows.first < TIMEOUT.inWholeMilliseconds)
            val (doneAt, done) = steps.last()
            assertTrue(done.complete && doneAt >= TIMEOUT.inWholeMilliseconds)
            assertTrue(done.ranked.items.any { it.kind == SearchKind.Shows })
            assertTrue(done.ranked.items.none { it.kind == SearchKind.Movies || it.kind == SearchKind.People })
        } finally {
            latency()
        }
    }

    @Test
    fun `episodes wait for the shows, so they never show and then fold away`() = runBlocking {
        // Review: an episode shown before its show answers folds away under the focus once the show is found.
        latency("""/Items\?.*includeItemTypes=Series(&|$)""" to SLOW_MS)
        try {
            val steps = search.results("Bri").toList()
            val beforeShows = steps.takeWhile { step -> step.ranked.items.none { it.kind == SearchKind.Shows } }
            assertTrue("no step before the shows", beforeShows.isNotEmpty())
            assertTrue(beforeShows.none { step -> step.ranked.items.any { it.kind == SearchKind.Episodes } })
            assertTrue(steps.last().ranked.items.any { it.kind == SearchKind.Episodes })
        } finally {
            latency()
        }
    }

    @Test
    fun `the search fails only when every kind and the people request fail`() = runBlocking {
        latency("""/Items\?.*searchTerm=""" to "status:500", "/Persons" to "status:500")
        try {
            val failed = runCatching { search.search("bright") }.exceptionOrNull()
            assertTrue("the search did not fail", failed != null)
        } finally {
            latency()
        }
    }

    @Test
    fun `nothing is asked for an empty term, and one character when the page asks`() = runBlocking {
        // The page asks for one character only after a longer pause or Enter (SearchTypingTest).
        val before = requests().size
        val steps = search.results("  ").toList()
        assertEquals(1, steps.size)
        assertTrue(steps.single().complete && steps.single().ranked.items.isEmpty())
        assertEquals(before, requests().size)
        search.search("q")
        assertTrue(requests().any { it.optString("route") == "items" && it.query("searchterm") == "q" })
    }

    /** Every request the test server answered so far, from its log. */
    private fun requests(): List<JSONObject> = File(folder, "out/requests.jsonl").readLines()
        .filter { it.isNotBlank() }
        .map(::JSONObject)

    /** The first value of [key] in a logged request's query, as the client sent it. */
    private fun JSONObject.query(key: String): String? = optJSONObject("query")?.optJSONArray(key)?.optString(0)

    /** The test server's answer to the requests matching each pattern: a delay in ms, "hang" or "status:NNN"; none: as normal. */
    private fun latency(vararg rules: Pair<String, Any>) {
        val list = JSONArray()
        rules.forEach { (pattern, rule) -> list.put(JSONArray().put(pattern).put(rule)) }
        call("/__harness/latency", "POST", JSONObject().put("default", 0).put("rules", list), null)
    }

    private fun dashed(id: String): String {
        if (id.contains('-')) return id
        val high = id.substring(0, HALF_ID).toULong(HEX).toLong()
        val low = id.substring(HALF_ID).toULong(HEX).toLong()
        return UUID(high, low).toString()
    }

    private fun call(
        path: String,
        method: String,
        body: JSONObject?,
        authorization: String?,
    ): String {
        val connection = URL(url + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            authorization?.let { connection.setRequestProperty("Authorization", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            } else if (method == "POST") {
                connection.doOutput = true
                connection.outputStream.use { }
            }
            check(connection.responseCode == HTTP_OK) { "$method $path: ${connection.responseCode}" }
            return connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val HTTP_OK = 200
        private const val HALF_ID = 16
        private const val HEX = 16
        private const val READY_WAIT_MS = 30_000L
        private const val POLL_MS = 100L
        private const val SLOW_MS = 3_000L
        private const val NANOS_PER_MS = 1_000_000L
        private const val MAX_KIND_LIMIT = 24
        private val TIMEOUT = 2.seconds

        private var server: Process? = null
        private var folder: File? = null
        private var url = ""

        @BeforeClass
        @JvmStatic
        fun startServer() {
            // The SDK logs through kotlin-logging, which looks for SLF4J here; Android's log (Robolectric's) is enough.
            System.setProperty("kotlin-logging-to-android-native", "true")
            val repo = File("").absoluteFile.let { if (File(it, "testing").isDirectory) it else it.parentFile }
            val script = File(repo, "testing/fakeserver/serve_synthetic.py")
            val dir = Files.createTempDirectory("nixie-search-").toFile().also { folder = it }
            val builder = ProcessBuilder("python", "-B", script.path, dir.path)
                .redirectErrorStream(true)
                .redirectOutput(File(dir, "server.log"))
            // Always the pinned desktop fake in testing/fakeserver/desktop (bug hunt M5), never another checkout.
            builder.environment().remove("NIXIE_DESKTOP_FAKESERVER")
            server = try {
                builder.start()
            } catch (ignored: IOException) {
                null
            }
            assumeTrue("Python is not on the PATH", server != null)
            val ready = File(dir, "ready.json")
            val until = System.currentTimeMillis() + READY_WAIT_MS
            while (!ready.isFile && System.currentTimeMillis() < until && server!!.isAlive) Thread.sleep(POLL_MS)
            check(ready.isFile) { "the test server did not start: " + File(dir, "server.log").readText() }
            url = Regex("\"url\": \"([^\"]+)\"").find(ready.readText())!!.groupValues[1]
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            server?.let {
                it.destroy()
                if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly()
            }
            folder?.deleteRecursively()
        }
    }
}
