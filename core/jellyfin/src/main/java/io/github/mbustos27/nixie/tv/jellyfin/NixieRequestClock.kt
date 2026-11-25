// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.jellyfin

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.ApiClientFactory
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.sockets.SocketConnectionFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

/**
 * TV-S1 (DESIGN-RULES 10): when the answer to a request made in a coroutine that carries this clock was in, read whole
 * from the network, before the SDK turned its JSON into objects. A library's first page splits its time with it into
 * the request and the parsing, for the detailed log. Set by [NixieTimedApiClient]; [now] is the clock both read.
 */
class NixieRequestClock(val now: () -> Long = System::nanoTime) : AbstractCoroutineContextElement(Key) {
    /** When the last answer was in ([now]'s time), 0 before any. */
    @Volatile
    var receivedAt: Long = 0L
        private set

    internal fun received() {
        receivedAt = now()
    }

    companion object Key : CoroutineContext.Key<NixieRequestClock>
}

/**
 * The SDK's client, with [NixieRequestClock] marked when an answer is in: the SDK reads the whole body in [request] and
 * parses it after. Everything else goes straight to [inner].
 */
class NixieTimedApiClient(private val inner: ApiClient) : ApiClient() {
    override val baseUrl: String? get() = inner.baseUrl
    override val accessToken: String? get() = inner.accessToken
    override val clientInfo: ClientInfo get() = inner.clientInfo
    override val deviceInfo: DeviceInfo get() = inner.deviceInfo
    override val httpClientOptions: HttpClientOptions get() = inner.httpClientOptions
    override val webSocket get() = inner.webSocket

    override fun update(
        baseUrl: String?,
        accessToken: String?,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
    ) = inner.update(baseUrl, accessToken, clientInfo, deviceInfo)

    override suspend fun request(
        method: HttpMethod,
        pathTemplate: String,
        pathParameters: Map<String, Any?>,
        queryParameters: Map<String, Any?>,
        requestBody: Any?,
    ): RawResponse = inner.request(method, pathTemplate, pathParameters, queryParameters, requestBody).also {
        coroutineContext[NixieRequestClock]?.received()
    }

    companion object {
        /** [factory]'s clients, each a [NixieTimedApiClient]. */
        fun wrap(factory: ApiClientFactory): ApiClientFactory = object : ApiClientFactory {
            override fun create(
                baseUrl: String?,
                accessToken: String?,
                clientInfo: ClientInfo,
                deviceInfo: DeviceInfo,
                httpClientOptions: HttpClientOptions,
                socketConnectionFactory: SocketConnectionFactory,
            ): ApiClient = NixieTimedApiClient(
                factory.create(
                    baseUrl,
                    accessToken,
                    clientInfo,
                    deviceInfo,
                    httpClientOptions,
                    socketConnectionFactory,
                ),
            )
        }
    }
}
