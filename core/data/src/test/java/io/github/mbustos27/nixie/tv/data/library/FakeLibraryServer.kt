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
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.NameIndexStore
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * A signed-in [LibraryRepository] whose SDK client answers every /Items request with [answer] (the request's query
 * parameters in, the JSON body out), its indexes saved in [cache]. For the library tests.
 */
internal class FakeLibraryServer(private val cache: File, private val answer: suspend (Map<String, Any?>) -> String) {
    /** A repository as the app makes one when it starts: nothing in memory, what [cache] holds on the device. */
    fun newRepository(): LibraryRepository {
        val server = StoredServer("s1", "Home", "http://home.invalid", "12.4.0", lastUsedAt = 0)
        val user = StoredUser("s1", USER.toString(), "anna", null, "t", lastUsedAt = 0, deviceId = "d")
        val accounts = AccountRepository(MemoryStore(AccountState(servers = listOf(server), users = listOf(user))))
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
            check(pathTemplate == "/Items") { "unexpected $pathTemplate" }
            val body = answer(HashMap(queryParameters))
            return RawResponse(body.toByteArray(), 200, mapOf("content-type" to listOf("application/json")))
        }
    }

    private class MemoryStore(start: AccountState) : DataStore<AccountState> {
        override val data = MutableStateFlow(start)

        override suspend fun updateData(transform: suspend (t: AccountState) -> AccountState): AccountState =
            transform(data.value).also { data.value = it }
    }

    companion object {
        val USER: UUID = UUID.nameUUIDFromBytes("anna".toByteArray())
    }
}
