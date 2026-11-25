// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.jellyfin

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TV-S1 (DESIGN-RULES 10): the SDK's client marks when an answer is in, for the request that carries a clock only. */
class NixieRequestClockTest {

    private var time = 0L

    private inner class Inner : ApiClient() {
        override val baseUrl = "http://tv.test"
        override val accessToken = "token"
        override val clientInfo = ClientInfo("Nixie for TV", "test")
        override val deviceInfo = DeviceInfo("d", "Test TV")
        override val httpClientOptions = HttpClientOptions()
        override val webSocket: SocketApi get() = error("no sockets here")
        var updated: String? = null

        override fun update(
            baseUrl: String?,
            accessToken: String?,
            clientInfo: ClientInfo,
            deviceInfo: DeviceInfo,
        ) {
            updated = accessToken
        }

        override suspend fun request(
            method: HttpMethod,
            pathTemplate: String,
            pathParameters: Map<String, Any?>,
            queryParameters: Map<String, Any?>,
            requestBody: Any?,
        ): RawResponse {
            time += 700 // the network
            return RawResponse("{}".toByteArray(), 200, emptyMap())
        }
    }

    @Test
    fun `an answer is marked in the clock of the request that carries one`() = runBlocking {
        val inner = Inner()
        val client = NixieTimedApiClient(inner)
        val clock = NixieRequestClock { time }
        time = 100
        withContext(clock) { client.request(HttpMethod.GET, "/Items", emptyMap(), emptyMap(), null) }
        assertEquals(800L, clock.receivedAt)
        // A request without a clock marks nothing.
        client.request(HttpMethod.GET, "/Items", emptyMap(), emptyMap(), null)
        assertEquals(800L, clock.receivedAt)
    }

    @Test
    fun `everything else is the SDK's own client`() {
        val inner = Inner()
        val client = NixieTimedApiClient(inner)
        assertEquals("http://tv.test", client.baseUrl)
        assertEquals("token", client.accessToken)
        assertEquals(inner.clientInfo, client.clientInfo)
        assertEquals(inner.deviceInfo, client.deviceInfo)
        client.update("http://tv.test", "new", inner.clientInfo, inner.deviceInfo)
        assertEquals("new", inner.updated)
        val url = client.createUrl("/Items", queryParameters = mapOf("limit" to 1))
        assertTrue(url, url.startsWith("http://tv.test/Items?limit=1"))
    }
}
