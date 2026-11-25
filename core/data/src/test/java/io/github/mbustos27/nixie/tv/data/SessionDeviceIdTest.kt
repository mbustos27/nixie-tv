// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
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
import org.junit.Assert.assertEquals
import org.junit.Test

/** Nixie: a profile's session goes out as the device id its token belongs to (DeviceIds). */
class SessionDeviceIdTest {

    private class MemoryStore(start: AccountState) : DataStore<AccountState> {
        override val data = MutableStateFlow(start)

        override suspend fun updateData(transform: suspend (t: AccountState) -> AccountState): AccountState =
            transform(data.value).also { data.value = it }
    }

    private val jellyfin = Jellyfin(
        JellyfinOptions(
            context = ContextWrapper(null),
            clientInfo = ClientInfo("Nixie for TV", "test"),
            deviceInfo = DeviceInfo(id = INSTALL, name = "Test TV"),
            apiClientFactory = FakeFactory,
            socketConnectionFactory = SocketConnectionFactory { _, _ -> error("no sockets here") },
            minimumServerVersion = Jellyfin.minimumVersion,
        ),
    )

    private fun user(id: String, deviceId: String?) =
        StoredUser("s1", id, id, null, accessToken = "token-$id", lastUsedAt = 0, deviceId = deviceId)

    private val store = MemoryStore(
        AccountState(
            servers = listOf(StoredServer("s1", "Home", "http://home.invalid", "12.4.0", lastUsedAt = 0)),
            users = listOf(user("anna", deviceId = "anna-device"), user("jonas", deviceId = null)),
        ),
    )
    private val sessions = SessionManager(jellyfin, AccountRepository(store), Authenticator(jellyfin))

    @Test
    fun `a session opens as the profile's own device id`() = runBlocking {
        sessions.open("s1", "anna")
        val session = sessions.session.value!!
        assertEquals("anna-device", session.api.deviceInfo.id)
        assertEquals(INSTALL, session.installDeviceId)
        assertEquals("anna-device", session.authorization().substringAfter("DeviceId=\"").substringBefore("\""))
    }

    @Test
    fun `a profile signed in before keeps the install's id, written into it`() = runBlocking {
        sessions.open("s1", "jonas")
        assertEquals(INSTALL, sessions.session.value!!.api.deviceInfo.id)
        assertEquals(INSTALL, store.data.value.users.single { it.userId == "jonas" }.deviceId)
    }

    private object FakeFactory : ApiClientFactory {
        override fun create(
            baseUrl: String?,
            accessToken: String?,
            clientInfo: ClientInfo,
            deviceInfo: DeviceInfo,
            httpClientOptions: HttpClientOptions,
            socketConnectionFactory: SocketConnectionFactory,
        ): ApiClient = FakeClient(baseUrl, accessToken, clientInfo, deviceInfo, httpClientOptions)
    }

    private class FakeClient(
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
        ): RawResponse = error("no requests in this test")
    }

    private companion object {
        const val INSTALL = "install-id"
    }
}
