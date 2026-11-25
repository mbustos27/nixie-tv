// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import java.io.IOException
import java.net.UnknownHostException
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TV-S8: a box redialled the live updates every minute all night in standby. In the background the socket
 * closes and nothing redials; it connects again at once when the app comes back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveUpdatesBackgroundTest {

    private val anna = UUID(0, 0xA)
    private val session = Session(
        server = StoredServer("s1", "Home", "http://home.invalid", "12.4.0", lastUsedAt = 0),
        user = StoredUser("s1", anna.toString(), "Anna", null, "token", lastUsedAt = 0, deviceId = "d"),
        api = NoRequests,
        installDeviceId = "install",
    )

    /** Each connection runs [plan]; notes when each opened and when one closed. */
    private class Link(private val plan: suspend FlowCollector<SocketEvent>.() -> Unit) : SocketLink {
        val openedAt = mutableListOf<Long>()
        var closes = 0
        var clock: () -> Long = { 0 }

        override fun open(session: Session) = flow {
            openedAt += clock()
            try {
                plan()
            } finally {
                closes++
            }
        }
    }

    private val foreground = MutableStateFlow(true)

    private fun TestScope.live(link: Link): LiveUserData {
        link.clock = { currentTime }
        return LiveUserData(
            backgroundScope,
            MutableStateFlow<Session?>(session),
            link,
            tokenAccepted = { null },
            log = {},
            foreground = foreground,
        ).also { it.start() }
    }

    @Test
    fun `in the background the socket closes and stays closed, back in front it connects at once`() = runTest {
        val link = Link {
            emit(SocketEvent.Opened)
            awaitCancellation()
        }
        val live = live(link)
        runCurrent()
        assertEquals(LiveState.Live, live.state.value)

        foreground.value = false
        runCurrent()
        assertEquals(1, link.closes)
        assertEquals(LiveState.Paused, live.state.value)
        advanceTimeBy(EIGHT_HOURS)
        assertEquals(1, link.openedAt.size)

        foreground.value = true
        runCurrent()
        assertEquals(listOf(0L, EIGHT_HOURS), link.openedAt)
        assertEquals(LiveState.Live, live.state.value)
    }

    @Test
    fun `a server that cannot be found is not redialled in the background`() = runTest {
        val link = Link { throw UnknownHostException("home.invalid") }
        val live = live(link)
        advanceTimeBy(10_000)
        val before = link.openedAt.size
        foreground.value = false
        runCurrent()
        advanceTimeBy(EIGHT_HOURS)
        assertEquals(before, link.openedAt.size)
        assertEquals(LiveState.Paused, live.state.value)

        // Back in front: a try at once, then the growing wait from the start.
        foreground.value = true
        runCurrent()
        assertEquals(before + 1, link.openedAt.size)
        assertEquals(currentTime, link.openedAt.last())
    }

    @Test
    fun `a refused token stays stopped after the background`() = runTest {
        val link = Link { throw SocketRefused("HTTP 401") }
        val live = live(link)
        runCurrent()
        assertEquals(LiveState.Stopped, live.state.value)
        foreground.value = false
        runCurrent()
        foreground.value = true
        runCurrent()
        advanceTimeBy(600_000)
        assertEquals(1, link.openedAt.size)
        assertEquals(LiveState.Stopped, live.state.value)
    }

    @Test
    fun `a drop while in front still redials`() = runTest {
        val link = Link { throw IOException("dropped") }
        live(link)
        advanceTimeBy(3_500)
        assertEquals(listOf(0L, 1_000L, 3_000L), link.openedAt)
    }

    private companion object {
        const val EIGHT_HOURS = 8 * 3_600_000L

        /** No request goes through it here. */
        val NoRequests: org.jellyfin.sdk.api.client.ApiClient = object : org.jellyfin.sdk.api.client.ApiClient() {
            override val baseUrl: String = "http://home.invalid"
            override val accessToken: String = "token"
            override val clientInfo = org.jellyfin.sdk.model.ClientInfo("Nixie for TV", "test")
            override val deviceInfo = org.jellyfin.sdk.model.DeviceInfo("d", "Test TV")
            override val httpClientOptions = org.jellyfin.sdk.api.client.HttpClientOptions()
            override val webSocket: org.jellyfin.sdk.api.sockets.SocketApi get() = error("no sockets in this test")

            override fun update(
                baseUrl: String?,
                accessToken: String?,
                clientInfo: org.jellyfin.sdk.model.ClientInfo,
                deviceInfo: org.jellyfin.sdk.model.DeviceInfo,
            ) = Unit

            override suspend fun request(
                method: org.jellyfin.sdk.api.client.HttpMethod,
                pathTemplate: String,
                pathParameters: Map<String, Any?>,
                queryParameters: Map<String, Any?>,
                requestBody: Any?,
            ): org.jellyfin.sdk.api.client.RawResponse = error("no requests in this test")
        }
    }
}
