// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.library

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.data.media.AgeFilter
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.data.media.ListGroup
import io.github.glacier_jellyfin.androidtv.core.data.media.LoadProgress
import io.github.glacier_jellyfin.androidtv.core.data.media.NameIndexStore
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import io.github.mbustos27.nixie.tv.jellyfin.NixieTimedApiClient
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.JellyfinOptions
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.ApiClientFactory
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.SocketConnectionFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TV-S1 (the owner's TV session): a library of 10,000 films opens on its first page from one small request, before
 * any request for the whole library; the next pages follow the server's order without a count; the index comes in big
 * pages, two at once, when nobody waits for it, in small counted ones when a jump does; a saved index does not hold up
 * the first page; with the index in memory, the app's own order is back. The real [LibraryRepository], with the SDK's
 * client answering from a fake library.
 */
class LibraryFirstPageTest {

    private val cache: File = Files.createTempDirectory("nixie-index").toFile()

    @After
    fun tearDown() {
        cache.deleteRecursively()
    }

    private val films = (1..FILMS).map { n ->
        // Years tie (70 of them) and every seventh is missing.
        val year = (1950 + n % 70).takeIf { n % 7 != 0 }
        Film(UUID.nameUUIDFromBytes("film-$n".toByteArray()), "Film %05d".format(n), year)
    }

    private class Film(
        val id: UUID,
        val name: String,
        val year: Int?,
    )

    /** Every /Items request's query, in order; and the most requests that were open at once. */
    private val requests: MutableList<Map<String, Any?>> = Collections.synchronizedList(mutableListOf())
    private val open = AtomicInteger()
    private val mostOpen = AtomicInteger()

    private val repository: LibraryRepository by lazy { newRepository() }

    /** A repository as the app makes one when it starts: nothing in memory, the indexes saved before on the device. */
    private fun newRepository(): LibraryRepository {
        val store = MemoryStore(
            AccountState(
                servers = listOf(StoredServer("s1", "Home", "http://home.invalid", "12.4.0", lastUsedAt = 0)),
                users = listOf(
                    StoredUser("s1", USER.toString(), "anna", null, accessToken = "t", lastUsedAt = 0, deviceId = "d"),
                ),
            ),
        )
        val accounts = AccountRepository(store)
        val jellyfin = Jellyfin(
            JellyfinOptions(
                context = ContextWrapper(null),
                clientInfo = ClientInfo("Nixie for TV", "test"),
                deviceInfo = DeviceInfo(id = "install", name = "Test TV"),
                apiClientFactory = Factory(),
                socketConnectionFactory = SocketConnectionFactory { _, _ -> error("no sockets here") },
                minimumServerVersion = Jellyfin.minimumVersion,
            ),
        )
        val sessions = SessionManager(jellyfin, accounts, Authenticator(jellyfin))
        runBlocking { check(sessions.open("s1", USER.toString())) }
        val context = object : ContextWrapper(null) {
            override fun getCacheDir(): File = cache
        }
        val ages = AgeFilter(sessions, ParentalControl(accounts, sessions))
        return LibraryRepository(sessions, ages, NameIndexStore(context))
    }

    private val movies = LibraryQuery(LibraryKind.Movies)

    @Test
    fun `a big library opens on its first page from one small request, uncounted`() = runBlocking {
        val page = repository.firstPage(movies, limit = 70)
        assertTrue(page.serverOrder)
        assertEquals(70, page.items.size)
        // DESIGN-RULES 3: the count of a big library is its own request, after the first row (count below).
        assertEquals(null, page.total)
        assertEquals("Film 00001", page.items.first().title)
        val only = requests.single()
        assertEquals(0, only["startIndex"])
        assertEquals(70, only["limit"])
        assertEquals(listOf(ItemSortBy.SORT_NAME, ItemSortBy.DATE_CREATED), only["sortBy"])
        assertEquals(false, only["enableTotalRecordCount"])
        // DESIGN-RULES 10: timed, the request (the fake answers in REQUEST_MS) apart from the parsing.
        assertTrue("${page.timing}", page.timing!!.requestMs >= REQUEST_MS && page.timing!!.parseMs >= 0)
        assertEquals(1, only["imageTypeLimit"])
        assertEquals(
            setOf("ChildCount", "Genres", "SortName", "DateCreated"),
            (only["fields"] as Collection<*>).map { it.toString() }.toSet(),
        )
    }

    @Test
    fun `the next pages follow the server's order without counting again`() = runBlocking {
        val page = repository.page(movies, start = 70, limit = 70, serverOrder = true, total = FILMS)
        assertTrue(page.serverOrder)
        // From a few titles back, so a title removed meanwhile is not skipped (the repeats are left out when appended).
        assertEquals("Film 00066", page.items.first().title)
        assertEquals(70 + NixieLibraryPaging.OVERLAP, page.items.size)
        assertEquals(FILMS, page.total)
        val only = requests.single()
        assertEquals(70 - NixieLibraryPaging.OVERLAP, only["startIndex"])
        assertEquals(listOf(ItemSortBy.SORT_NAME, ItemSortBy.DATE_CREATED), only["sortBy"])
        assertEquals(false, only["enableTotalRecordCount"])
    }

    @Test
    fun `the list is counted apart, in one request for no titles`() = runBlocking {
        assertEquals(FILMS, repository.serverPosition(movies))
        val only = requests.single()
        assertEquals(0, only["limit"])
        assertEquals(true, only["enableTotalRecordCount"])
        assertEquals(false, only["enableImages"])
    }

    @Test
    fun `an A-Z jump without the index asks the server where the letter starts, one request for no titles`() =
        runBlocking {
            repository.firstPage(movies, limit = 70)
            requests.clear()
            // Every title is "Film …": F starts at the top, G after the last one (the grid stops at the last title).
            assertEquals(0, repository.serverPosition(movies, 'F'))
            assertEquals(FILMS, repository.serverPosition(movies, 'G'))
            assertEquals(listOf("f", "g"), requests.map { it["nameLessThan"] })
            assertTrue(requests.all { it["limit"] == 0 && it["enableTotalRecordCount"] == true })
            // '#' is the top: nothing to ask.
            requests.clear()
            assertEquals(0, repository.serverPosition(movies, '#'))
            assertTrue(requests.isEmpty())
            // Z to A counts from the end: the list's size, when known, is not asked again.
            val descending = movies.copy(descending = true)
            assertEquals(0, repository.serverPosition(descending, 'F', total = FILMS))
            assertEquals(FILMS, repository.serverPosition(descending, 'E', total = FILMS))
            assertEquals(listOf("g", "f"), requests.map { it["nameLessThan"] })
            assertEquals(null, repository.knownLetters(movies))
        }

    @Test
    fun `latest year first comes with its groups, the titles without a year last`() = runBlocking {
        val byYear = movies.copy(sort = LibrarySort.Year, descending = true)
        val page = repository.firstPage(byYear, limit = 70)
        assertTrue(page.serverOrder)
        assertEquals(
            listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME, ItemSortBy.DATE_CREATED),
            requests.single()["sortBy"],
        )
        assertTrue(page.items.all { it.group == ListGroup.Year(it.year!!) })
        // Equal years in one group each, one after the other.
        val groups = page.items.map { it.group }
        val runs = groups.filterIndexed { i, group -> i == 0 || groups[i - 1] != group }
        assertEquals(groups.distinct(), runs)
        val last = repository.page(byYear, start = FILMS - 70, limit = 70, serverOrder = true, total = FILMS)
        assertTrue(last.items.all { it.year == null && it.group == ListGroup.Unknown })
    }

    @Test
    fun `oldest year first comes from the server too, the titles without a year first as in the app's order`() =
        runBlocking {
            val byYear = movies.copy(sort = LibrarySort.Year)
            val page = repository.firstPage(byYear, limit = 70)
            assertTrue(page.serverOrder)
            val up = SortOrder.ASCENDING
            assertEquals(listOf(up, up, up), requests.single()["sortOrder"])
            assertTrue(page.items.all { it.year == null && it.group == ListGroup.Unknown })
            // Where the server's order meets the titles with a year, the app's order (ListArrangement) has them too.
            val known = films.count { it.year == null }
            val next = repository.page(byYear, known, limit = 70, serverOrder = true, total = FILMS, overlap = false)
            assertEquals(ListGroup.Year(films.mapNotNull { it.year }.min()), next.items.first().group)
        }

    @Test
    fun `unwatched movies come from the server in one small request, filtered there and not grouped`() = runBlocking {
        val unwatched = movies.copy(unwatchedOnly = true)
        val page = repository.firstPage(unwatched, limit = 70)
        assertTrue(page.serverOrder)
        val only = requests.single()
        assertEquals(listOf(ItemFilter.IS_UNPLAYED), only["filters"])
        assertEquals(null, only["collapseBoxSetItems"])
        assertEquals(70, only["limit"])
        assertEquals(false, only["enableTotalRecordCount"])
    }

    @Test
    fun `the index nobody waits for comes in big pages, two at once, one count, uncounted on screen`() = runBlocking {
        repository.firstPage(movies, limit = 70)
        requests.clear()
        mostOpen.set(0)
        val shown = progressDuring { repository.availableLetters(movies, background = true) }
        assertEquals(FILMS / NixieLibraryPaging.BACKGROUND_PAGE, requests.size)
        assertTrue(requests.all { it["limit"] == NixieLibraryPaging.BACKGROUND_PAGE })
        assertEquals(1, requests.count { it["enableTotalRecordCount"] == true })
        assertTrue("${mostOpen.get()} at once", mostOpen.get() <= NixieLibraryPaging.BACKGROUND_PARALLEL)
        assertTrue("progress shown: $shown", shown.isEmpty())
    }

    @Test
    fun `a jump in the same list waits for the index in small pages, counted on screen`() = runBlocking {
        repository.firstPage(movies, limit = 70)
        requests.clear()
        mostOpen.set(0)
        val shown = progressDuring { assertEquals(0, repository.indexOfLetter(movies, 'F')) }
        assertEquals(FILMS / NixieLibraryPaging.WAITED_PAGE, requests.size)
        assertTrue(requests.all { it["limit"] == NixieLibraryPaging.WAITED_PAGE })
        assertTrue("${mostOpen.get()} at once", mostOpen.get() <= NixieLibraryPaging.WAITED_PARALLEL)
        assertTrue("no progress shown", shown.any { it.total == FILMS && it.isFor(movies) })
    }

    @Test
    fun `opening an A-Z list asks the server for its first page and no index`() = runBlocking {
        repository.firstPage(movies, limit = 70)
        assertEquals(null, repository.knownLetters(movies))
        assertEquals(1, requests.size)
    }

    @Test
    fun `each list's index is kept for the session, not fetched again after others were opened`() = runBlocking {
        // A favourites list: its index is never saved on the device, so only memory can keep it.
        val favourites = movies.copy(favoritesOnly = true)
        repository.indexOfLetter(favourites, 'F')
        listOf(movies, movies.copy(unwatchedOnly = true), LibraryQuery(LibraryKind.Shows)).forEach {
            repository.indexOfLetter(it, 'F')
        }
        requests.clear()
        assertEquals(setOf('F'), repository.knownLetters(favourites))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a jump to a letter with no titles lands on the next one, or the last title`() = runBlocking {
        assertEquals(0, repository.indexOfLetter(movies, 'A'))
        assertEquals(FILMS - 1, repository.indexOfLetter(movies, 'Z'))
        assertEquals(0, repository.indexOfLetter(movies.copy(descending = true), 'Z'))
    }

    /** What [repository]'s progress showed while [block] ran. */
    private suspend fun progressDuring(block: suspend () -> Unit): List<LoadProgress> = coroutineScope {
        val shown = Collections.synchronizedList(mutableListOf<LoadProgress>())
        val watch = launch(Dispatchers.Unconfined) { repository.progress.collect { it?.let(shown::add) } }
        block()
        watch.cancel()
        shown.toList()
    }

    @Test
    fun `an index saved on the device does not hold up the first page after a restart`() = runBlocking {
        repository.availableLetters(movies)
        val restarted = newRepository()
        requests.clear()
        val page = restarted.firstPage(movies, limit = 70)
        assertTrue(page.serverOrder)
        assertEquals(70, requests.single()["limit"])
        // The rail's letters then come from the device, without asking the server.
        requests.clear()
        assertEquals(setOf('F'), restarted.availableLetters(movies))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `with the index in memory the list opens in the app's order`() = runBlocking {
        repository.availableLetters(movies)
        requests.clear()
        val page = repository.firstPage(movies, limit = 70)
        assertFalse(page.serverOrder)
        assertEquals("Film 00001", page.items.first().title)
        // Fetched by their ids, as Glacier pages through its index.
        assertTrue(requests.all { (it["ids"] as Collection<*>).isNotEmpty() })
        assertTrue(repository.indexReady(movies))
    }

    private inner class Factory : ApiClientFactory {
        override fun create(
            baseUrl: String?,
            accessToken: String?,
            clientInfo: ClientInfo,
            deviceInfo: DeviceInfo,
            httpClientOptions: HttpClientOptions,
            socketConnectionFactory: SocketConnectionFactory,
        ): ApiClient = NixieTimedApiClient(Client(baseUrl, accessToken, clientInfo, deviceInfo, httpClientOptions))
    }

    private inner class Client(
        override var baseUrl: String?,
        override var accessToken: String?,
        override var clientInfo: ClientInfo,
        override var deviceInfo: DeviceInfo,
        override val httpClientOptions: HttpClientOptions,
    ) : ApiClient() {
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
            requests += HashMap(queryParameters)
            val now = open.incrementAndGet()
            mostOpen.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                delay(REQUEST_MS)
                val ids = (queryParameters["ids"] as? Collection<*>).orEmpty()
                val sortBy = queryParameters["sortBy"] as? Collection<*>
                val pool = when {
                    ids.isNotEmpty() -> films.filter { it.id in ids }

                    // Latest first; as SQLite, a missing year is the smallest, so last; equal years by name.
                    sortBy?.firstOrNull() == ItemSortBy.PRODUCTION_YEAR ->
                        if ((queryParameters["sortOrder"] as? Collection<*>)?.firstOrNull() == SortOrder.ASCENDING) {
                            films.sortedWith(oldestFirst)
                        } else {
                            films.sortedWith(latestFirst)
                        }

                    else -> films
                }.filter { film ->
                    // As the server: the titles whose sort name (lower case) comes before it.
                    val below = queryParameters["nameLessThan"] as? String
                    below == null || film.name.lowercase() < below
                }
                val start = queryParameters["startIndex"] as? Int ?: 0
                val limit = queryParameters["limit"] as? Int ?: pool.size
                val page = pool.drop(start).take(limit)
                val items = page.joinToString(",") {
                    val year = it.year?.let { y -> ""","ProductionYear":$y""" }.orEmpty()
                    """{"Id":"${it.id}","Name":"${it.name}","SortName":"${it.name.lowercase()}",""" +
                        """"Type":"Movie"$year,"DateCreated":"2024-05-01T10:00:00.0000000Z","ImageTags":{"Primary":"p"}}"""
                }
                val total = if (queryParameters["enableTotalRecordCount"] == false) 0 else pool.size
                val body = """{"Items":[$items],"TotalRecordCount":$total,"StartIndex":$start}"""
                return RawResponse(body.toByteArray(), 200, mapOf("content-type" to listOf("application/json")))
            } finally {
                open.decrementAndGet()
            }
        }
    }

    private class MemoryStore(start: AccountState) : DataStore<AccountState> {
        override val data = MutableStateFlow(start)

        override suspend fun updateData(transform: suspend (t: AccountState) -> AccountState): AccountState =
            transform(data.value).also { data.value = it }
    }

    private val latestFirst = compareByDescending<Film, Int?>(nullsFirst()) { it.year }.thenBy { it.name }

    // As SQLite going up: a missing year is the smallest, so first.
    private val oldestFirst = compareBy<Film, Int?>(nullsFirst()) { it.year }.thenBy { it.name }

    private companion object {
        const val FILMS = 10_000
        const val REQUEST_MS = 15L
        val USER: UUID = UUID.nameUUIDFromBytes("anna".toByteArray())
    }
}
