// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.data.media.AgeFilter
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveTvRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsState
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import java.time.LocalDateTime
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
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
import org.jellyfin.sdk.model.api.ItemFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tv-home-start (TV-S6), tv-home-two-rows (TV-S11): what Home asks the server, on the real HomeRepository with its SDK
 * client faked. Exactly its two rows, Continue watching and Up next, each a small page with only what Home shows of
 * it and no total count; then the top bar's libraries, one request; no count, no other row, nothing for the hero,
 * nothing that reads a whole library (DESIGN-RULES 1, 3).
 */
class HomeRequestsTest {

    private val movies = UUID.nameUUIDFromBytes("movies".toByteArray())
    private val shows = UUID.nameUUIDFromBytes("shows".toByteArray())
    private val film = UUID.nameUUIDFromBytes("film".toByteArray())

    /** Every request: its path and query, in order. */
    private val requests = Collections.synchronizedList(mutableListOf<Pair<String, Map<String, Any?>>>())

    private fun answer(path: String, query: Map<String, Any?>): String = when (path) {
        "/UserViews" ->
            """{"Items":[""" +
                """{"Id":"$movies","Name":"Movies","Type":"CollectionFolder","CollectionType":"movies"},""" +
                """{"Id":"$shows","Name":"Shows","Type":"CollectionFolder","CollectionType":"tvshows"}""" +
                """],"TotalRecordCount":2,"StartIndex":0}"""

        "/Items/Latest" -> """[${movie()}]"""

        "/UserItems/Resume" -> """{"Items":[${movie()}],"TotalRecordCount":0,"StartIndex":0}"""

        "/Shows/NextUp" -> """{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""

        "/Items" -> if (query["limit"] == 0) {
            """{"Items":[],"TotalRecordCount":25000,"StartIndex":0}"""
        } else {
            """{"Items":[${movie()}],"TotalRecordCount":0,"StartIndex":0}"""
        }

        // The user's configuration (latest excludes): a failed read leaves every library its row.
        else -> error("not here: $path")
    }

    private fun movie() = """{"Id":"$film","Name":"Salt Flats","Type":"Movie","ImageTags":{"Primary":"p"}}"""

    private fun repository(): HomeRepository {
        val server = StoredServer("s1", "Home", "http://home.invalid", "12.4.0", lastUsedAt = 0)
        val user = StoredUser("s1", USER.toString(), "anna", null, "t", lastUsedAt = 0, deviceId = "d")
        val accounts = AccountRepository(Memory(AccountState(servers = listOf(server), users = listOf(user))))
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
        val settings = SettingsRepository(Memory(SettingsState()), sessions)
        val ages = AgeFilter(sessions, ParentalControl(accounts, sessions))
        return HomeRepository(sessions, ages, LiveTvRepository(sessions, settings), settings)
    }

    private fun load(repository: HomeRepository) = runBlocking {
        HomeStart.rows(repository, null, NixieHomeSettings(), LocalDateTime.of(2026, 11, 22, 8, 0)).toList()
    }

    @Test
    fun `Home asks for exactly its two rows, then the top bar's libraries`() {
        val progress = load(repository())

        val paths = requests.map { it.first }
        assertEquals(
            "Continue watching and Up next (in any order), then the libraries; nothing else",
            listOf(setOf("/UserItems/Resume", "/Shows/NextUp"), setOf("/UserViews")),
            listOf(paths.take(2).toSet(), paths.drop(2).toSet()),
        )
        assertEquals(3, paths.size)
        assertTrue("no count at all", requests.none { it.second["enableTotalRecordCount"] == true })
        assertEquals(listOf(movies, shows), progress.last().content.libraries.map { it.id })
        assertTrue(progress.last().settled)
    }

    @Test
    fun `each row asks for a small page with only what Home shows of it`() {
        load(repository())

        requests.filter { it.first in Lists }.forEach { (path, query) ->
            val limit = query["limit"] as? Int
            assertTrue("$path asks for a small page, not the whole library ($limit)", limit != null && limit <= SMALL)
            assertEquals("$path asks for no count", false, query["enableTotalRecordCount"])
            // The hero shows the focused card's overview and genre; no stream (the picture's quality) is shown.
            assertEquals("$path", setOf(ItemFields.OVERVIEW, ItemFields.GENRES), (query["fields"] as List<*>).toSet())
            assertEquals(1, query["imageTypeLimit"])
        }
    }

    @Test
    fun `the libraries come without their counts`() = runBlocking {
        val views = repository().views()

        assertEquals(listOf(movies, shows), views.libraries.map { it.id })
        assertTrue(views.libraries.all { it.itemCount == null })
        assertTrue("no count asked", requests.none { it.second["enableTotalRecordCount"] == true })
        assertEquals(
            "one request: the profile's \"latest\" list is not asked",
            listOf("/UserViews"),
            requests.map { it.first },
        )
    }

    private inner class Factory : ApiClientFactory {
        override fun create(
            baseUrl: String?,
            accessToken: String?,
            clientInfo: ClientInfo,
            deviceInfo: DeviceInfo,
            httpClientOptions: HttpClientOptions,
            socketConnectionFactory: SocketConnectionFactory,
        ): ApiClient = Client(baseUrl, accessToken, clientInfo, deviceInfo, httpClientOptions)
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
            requests += pathTemplate to HashMap(queryParameters)
            val body = answer(pathTemplate, queryParameters)
            return RawResponse(body.toByteArray(), 200, mapOf("content-type" to listOf("application/json")))
        }
    }

    private class Memory<T>(start: T) : DataStore<T> {
        override val data = MutableStateFlow(start)

        override suspend fun updateData(transform: suspend (t: T) -> T): T {
            val next = transform(data.value)
            data.value = next
            return next
        }
    }

    private companion object {
        val USER: UUID = UUID.nameUUIDFromBytes("anna".toByteArray())

        /** The biggest page a Home row asks for (16). */
        const val SMALL = 16

        /** Home's two rows. */
        val Lists = setOf("/UserItems/Resume", "/Shows/NextUp")
    }
}
