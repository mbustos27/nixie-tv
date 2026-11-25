// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.jellyfin

import android.content.ContextWrapper
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import io.github.glacier_jellyfin.androidtv.core.jellyfin.QuickConnectState
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.core.jellyfin.SignInResult
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nixie: a device id per sign-in attempt. The fake server below answers Quick Connect and
 * password sign-in and notes the device id of every request.
 */
class QuickConnectDeviceIdTest {

    private val requests = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    private val jellyfin = Jellyfin(
        JellyfinOptions(
            context = ContextWrapper(null),
            clientInfo = ClientInfo("Nixie for TV", "test"),
            deviceInfo = DeviceInfo(id = INSTALL_ID, name = "Test TV"),
            apiClientFactory = FakeFactory(),
            socketConnectionFactory = SocketConnectionFactory { _, _ -> error("no sockets here") },
            minimumServerVersion = Jellyfin.minimumVersion,
        ),
    )

    private val server = ServerInfo(id = SERVER_ID, name = "Test", address = "http://test.invalid", version = "12.4.0")

    @Test
    fun `a Quick Connect attempt keeps one device id of its own from Initiate to the token`() = runBlocking {
        val user = Authenticator(jellyfin).quickConnect(server)
            .filterIsInstance<QuickConnectState.Authorized>().first().user

        val ids = requests.map { it.second }.distinct()
        assertEquals("every request of the attempt has the same id", 1, ids.size)
        assertNotEquals("not the install's id", INSTALL_ID, ids.single())
        assertTrue(requests.any { it.first.endsWith("/Initiate") })
        assertEquals("the profile keeps the id its token was issued to", ids.single(), user.deviceId)
    }

    @Test
    fun `each Quick Connect attempt has another id`() = runBlocking {
        val authenticator = Authenticator(jellyfin)
        suspend fun attempt() =
            authenticator.quickConnect(server).filterIsInstance<QuickConnectState.Authorized>().first()
        val first = async { attempt() }
        val second = async { attempt() }
        assertNotEquals(first.await().user.deviceId, second.await().user.deviceId)
    }

    @Test
    fun `a password sign-in has its own id as well`() = runBlocking {
        val result = Authenticator(jellyfin).signIn(server, "someone", "secret")
        val user = (result as SignInResult.Success).user
        assertEquals(listOf(user.deviceId), requests.map { it.second }.distinct())
        assertNotEquals(INSTALL_ID, user.deviceId)
    }

    @Test
    fun `a stored token is checked as its profile's device`() = runBlocking {
        Authenticator(jellyfin).isTokenValid(server.address, "token", "profile-id")
        Authenticator(jellyfin).isTokenValid(server.address, "token")
        assertEquals(listOf("profile-id", INSTALL_ID), requests.map { it.second })
    }

    private inner class FakeFactory : ApiClientFactory {
        override fun create(
            baseUrl: String?,
            accessToken: String?,
            clientInfo: ClientInfo,
            deviceInfo: DeviceInfo,
            httpClientOptions: HttpClientOptions,
            socketConnectionFactory: SocketConnectionFactory,
        ): ApiClient = FakeClient(baseUrl, accessToken, clientInfo, deviceInfo, httpClientOptions)
    }

    private inner class FakeClient(
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
        ) {
            this.baseUrl = baseUrl
            this.accessToken = accessToken
            this.clientInfo = clientInfo
            this.deviceInfo = deviceInfo
        }

        override suspend fun request(
            method: HttpMethod,
            pathTemplate: String,
            pathParameters: Map<String, Any?>,
            queryParameters: Map<String, Any?>,
            requestBody: Any?,
        ): RawResponse {
            requests += pathTemplate to deviceInfo.id
            val body = when {
                pathTemplate.endsWith("/Enabled") -> "true"
                pathTemplate.endsWith("/Initiate") || pathTemplate.endsWith("/Connect") -> QUICK_CONNECT
                pathTemplate.contains("Authenticate") -> AUTHENTICATED
                pathTemplate.endsWith("/Users/Me") -> USER
                else -> error("unexpected request $pathTemplate")
            }
            return RawResponse(body.toByteArray(), 200, mapOf("Content-Type" to listOf("application/json")))
        }
    }

    private companion object {
        const val INSTALL_ID = "install-id"
        const val SERVER_ID = "0123456789abcdef0123456789abcdef"
        const val USER = """{"Name":"Someone","Id":"38a5a5bb-dc30-49a2-b175-1de0d1488c43","HasPassword":true,
            "HasConfiguredPassword":true,"HasConfiguredEasyPassword":false,"EnableAutoLogin":false}"""
        const val QUICK_CONNECT = """{"Authenticated":true,"Secret":"s","Code":"123456","DeviceId":"d",
            "DeviceName":"Test TV","AppName":"Nixie for TV","AppVersion":"test","DateAdded":"2026-11-21T10:00:00"}"""
        const val AUTHENTICATED = """{"User":$USER,"AccessToken":"token","ServerId":"$SERVER_ID"}"""
    }
}
