// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import android.app.Application
import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.data.media.AgeFilter
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.data.media.NameIndexStore
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.library.BrowseMemory
import io.github.glacier_jellyfin.androidtv.library.LibraryState
import io.github.glacier_jellyfin.androidtv.library.LibraryViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TV-S13 (a remote server answering in about a second): Movies, Unwatched and the first A–Z jump each wait on one
 * request before their first row, and nothing goes before it (no count, no place in the list, no index). Glacier's
 * LibraryViewModel and the real LibraryRepository, the SDK's client answering from a fake library of 2,600 films (a
 * hundred under each letter) that notes every request, and what the grid showed when it came.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SlowServerPathsTest {

    private val scheduler = TestCoroutineScheduler()
    private val cache: File = Files.createTempDirectory("nixie-slow").toFile()

    /** Titles "Apple 0000" to "Zebra 2599": a hundred under each letter; every third one watched. */
    private class Film(val n: Int) {
        val id: UUID = UUID.nameUUIDFromBytes("film-$n".toByteArray())
        val name = "%c%s %04d".format('A' + n / PER_LETTER, "pple", n)
        val sort = name.lowercase()
        val played = n % 3 == 0
    }

    private val films = (0 until FILMS).map(::Film)

    /** A request as the fake server saw it: its query, and what the grid held then. */
    private class Request(val query: Map<String, Any?>, val shown: LibraryState?)

    private val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    /** The requests so far (the place and the count come on IO threads meanwhile). */
    private fun seen(): List<Request> = synchronized(requests) { requests.toList() }

    @Volatile
    private var model: LibraryViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        cache.deleteRecursively()
    }

    // ---- the paths ----------------------------------------------------------------------------------------------

    @Test
    fun `Movies asks for its first row with one request and nothing before it`() {
        val model = open()
        val before = seen().filter { it.shown?.items.isNullOrEmpty() }
        assertEquals("requests before the first row: ${before.map { it.query }}", 1, before.size)
        val page = before.single().query
        assertEquals(0, page["startIndex"])
        assertEquals(LibraryViewModel.PAGE_SIZE, page["limit"])
        assertEquals(false, page["enableTotalRecordCount"])
        assertNull(page["nameLessThan"])
        // The count comes after the first row is drawn, never before.
        drawn(model)
        val count = seen().last().query
        assertEquals(0, count["limit"])
        assertEquals(true, count["enableTotalRecordCount"])
        assertEquals(FILMS, model.state.value.total)
    }

    @Test
    fun `Unwatched asks for its first row with one request and nothing before it`() {
        val model = open()
        drawn(model)
        requests.clear()
        model.toggleUnwatchedOnly()
        until { model.state.value.query.unwatchedOnly && model.state.value.items.isNotEmpty() }
        val before = seen().filter { it.shown?.items.isNullOrEmpty() }
        assertEquals("requests before Unwatched's first row: ${before.map { it.query }}", 1, before.size)
        assertEquals(listOf(ItemFilter.IS_UNPLAYED), before.single().query["filters"])
        assertEquals(false, before.single().query["enableTotalRecordCount"])
        assertTrue(model.state.value.items.none { item -> films.first { it.id == item.id }.played })
    }

    @Test
    fun `the first A-Z jump asks for its titles with one request, its place after they are shown`() {
        val model = open()
        drawn(model)
        requests.clear()
        model.jumpToLetter('M', COLUMNS)
        until { model.state.value.items.firstOrNull()?.title?.startsWith("M") == true }
        val before = seen().filter { it.shown?.items?.firstOrNull()?.title?.startsWith("M") != true }
        assertEquals("requests before the jump's titles: ${before.map { it.query }}", 1, before.size)
        val page = before.single().query
        assertEquals("m", page["nameStartsWithOrGreater"])
        assertEquals(0, page["startIndex"])
        assertEquals(false, page["enableTotalRecordCount"])
        // The grid lands on the letter's first title, the first card of a row.
        assertEquals(0, model.state.value.jumpTo)
        // Its place comes after ("m" is the 13th letter: 1,200 titles before it), then the rows above it, whole rows
        // from the letter's, so nothing on screen moves.
        until { model.state.value.anchor == null && model.state.value.offset < LETTER_M }
        val place = seen()[before.size].query
        assertEquals("m", place["nameLessThan"])
        assertEquals(0, place["limit"])
        assertEquals(LETTER_M % COLUMNS, model.state.value.rowPhase)
        assertEquals(LETTER_M - LibraryViewModel.PAGE_SIZE, model.state.value.offset)
        assertEquals(0, (LETTER_M - model.state.value.offset) % COLUMNS)
        val shown = model.state.value
        assertEquals(films.drop(shown.offset).take(shown.items.size).map { it.id }, shown.items.map { it.id })
    }

    @Test
    fun `going Z to A, the first jump also asks for its titles with one request`() {
        val model = open()
        drawn(model)
        model.setSort(LibrarySort.Title) // the same sort again: Z to A
        until { model.state.value.query.descending && model.state.value.items.isNotEmpty() }
        drawn(model)
        requests.clear()
        model.jumpToLetter('M', COLUMNS)
        until { model.state.value.items.firstOrNull()?.title?.startsWith("M") == true }
        val before = seen().filter { it.shown?.items?.firstOrNull()?.title?.startsWith("M") != true }
        assertEquals("requests before the jump's titles: ${before.map { it.query }}", 1, before.size)
        val page = before.single().query
        assertEquals("n", page["nameLessThan"])
        assertEquals(listOf(SortOrder.DESCENDING, SortOrder.DESCENDING), page["sortOrder"])
        // The last M title first; its place counted from the end (1,300 titles before it, Z to N), the rows above after.
        assertEquals(films[LETTER_M + PER_LETTER - 1].id, model.state.value.items.first().id)
        val place = FILMS - LETTER_M - PER_LETTER
        until { model.state.value.anchor == null && model.state.value.offset < place }
        assertEquals(place % COLUMNS, model.state.value.rowPhase)
        assertEquals(place - LibraryViewModel.PAGE_SIZE, model.state.value.offset)
    }

    // ---- the view model over the fake server --------------------------------------------------------------------

    /** Movies opened from the top bar, waited on until its first page is in. */
    private fun open(): LibraryViewModel {
        val repository = newRepository()
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.settings } returns MutableStateFlow(ProfileSettings())
        coEvery { settings.current() } returns ProfileSettings()
        val details = mockk<DetailRepository>(relaxed = true)
        every { details.playedChanged } returns MutableSharedFlow()
        val playback = mockk<PlaybackRepository>(relaxed = true)
        every { playback.stopped } returns MutableSharedFlow()
        val home = mockk<HomeRepository>(relaxed = true)
        every { home.kinds } returns MutableStateFlow(listOf(LibraryKind.Movies))
        val made = LibraryViewModel(
            SavedStateHandle(mapOf("kind" to LibraryKind.Movies.name)),
            repository,
            sessions,
            settings,
            mockk(relaxed = true),
            mockk(relaxed = true),
            details,
            playback,
            home,
            mockk(relaxed = true),
            BrowseMemory(flowOf(null), StandardTestDispatcher(scheduler)),
        )
        model = made
        until { made.state.value.items.isNotEmpty() }
        return made
    }

    /** The grid drew the list's first row: what waits for the first screen runs, and is waited on. */
    private fun drawn(model: LibraryViewModel) {
        model.firstRowShown()
        until { model.state.value.total != null }
    }

    /** Runs the main thread's work, and lets the requests (on the IO threads) answer, until [done]. */
    private fun until(done: () -> Boolean) {
        repeat(WAIT_ROUNDS) {
            scheduler.advanceUntilIdle()
            if (done()) return
            Thread.sleep(WAIT_STEP_MS)
        }
        scheduler.advanceUntilIdle()
        assertTrue("not reached; requests: ${seen().map { it.query }}", done())
    }

    // ---- the fake server -----------------------------------------------------------------------------------------

    /** The library's titles as the server filters, sorts and pages them (by sort name, as SQLite compares). */
    private fun answer(query: Map<String, Any?>): String {
        requests += Request(query, model?.state?.value)
        val ids = (query["ids"] as? Collection<*>).orEmpty().map { it.toString() }.toSet()
        var list = if (ids.isNotEmpty()) films.filter { it.id.toString() in ids } else films
        if (ItemFilter.IS_UNPLAYED in (query["filters"] as? Collection<*>).orEmpty()) list = list.filter { !it.played }
        (query["nameLessThan"] as? String)?.let { below -> list = list.filter { it.sort < below } }
        (query["nameStartsWithOrGreater"] as? String)?.let { from -> list = list.filter { it.sort >= from } }
        list = list.sortedBy { it.sort }
        if ((query["sortOrder"] as? Collection<*>)?.firstOrNull() == SortOrder.DESCENDING) list = list.reversed()
        val start = query["startIndex"] as? Int ?: 0
        val limit = query["limit"] as? Int ?: list.size
        val items = list.drop(start).take(limit).joinToString(",") { film ->
            """{"Id":"${film.id}","Name":"${film.name}","SortName":"${film.sort}","Type":"Movie",""" +
                """"UserData":{"Played":${film.played},"PlaybackPositionTicks":0,"PlayCount":0,"IsFavorite":false,""" +
                """"Key":"${film.id}","ItemId":"${film.id}"}}"""
        }
        val total = if (query["enableTotalRecordCount"] == false) 0 else list.size
        return """{"Items":[$items],"TotalRecordCount":$total,"StartIndex":$start}"""
    }

    private val sessions = mockk<SessionManager>(relaxed = true)

    /** The repository as the app makes it, signed in to the fake server; no age limit (the profile's own filter). */
    private fun newRepository(): LibraryRepository {
        val server = StoredServer("s1", "Home", "http://home.invalid", "12.4.0", lastUsedAt = 0)
        val user = StoredUser("s1", USER.toString(), "anna", null, "t", lastUsedAt = 0, deviceId = "d")
        val api = Client("http://home.invalid", "t", ClientInfo("Nixie for TV", "test"), DeviceInfo("tv", "Test TV"))
        every { sessions.session } returns MutableStateFlow(Session(server, user, api))
        val ages = mockk<AgeFilter>()
        coEvery { ages.limits() } returns null
        coEvery { ages.mark(any()) } answers { firstArg() }
        val context = object : ContextWrapper(null) {
            override fun getCacheDir(): File = cache
        }
        return LibraryRepository(sessions, ages, NameIndexStore(context))
    }

    private inner class Client(
        override var baseUrl: String?,
        override var accessToken: String?,
        override var clientInfo: ClientInfo,
        override var deviceInfo: DeviceInfo,
    ) : ApiClient() {
        override val httpClientOptions = HttpClientOptions()

        override val webSocket: SocketApi get() = error("no sockets here")

        override fun update(
            baseUrl: String?,
            accessToken: String?,
            clientInfo: ClientInfo,
            deviceInfo: DeviceInfo,
        ) = Unit

        override suspend fun request(
            method: HttpMethod,
            pathTemplate: String,
            pathParameters: Map<String, Any?>,
            queryParameters: Map<String, Any?>,
            requestBody: Any?,
        ): RawResponse {
            check(pathTemplate == "/Items") { "unexpected $pathTemplate" }
            val body = answer(HashMap(queryParameters))
            return RawResponse(body.toByteArray(), 200, mapOf("content-type" to listOf("application/json")))
        }
    }

    private companion object {
        const val FILMS = 2_600
        const val PER_LETTER = 100
        const val COLUMNS = 7

        /** Titles before the first M title (A to L). */
        const val LETTER_M = 1_200
        const val WAIT_ROUNDS = 500
        const val WAIT_STEP_MS = 10L
        val USER: UUID = UUID.nameUUIDFromBytes("anna".toByteArray())
    }
}
