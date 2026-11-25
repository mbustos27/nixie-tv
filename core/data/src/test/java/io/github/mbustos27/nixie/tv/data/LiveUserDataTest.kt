// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** Nixie: rows follow the server's `UserDataChanged`; the socket backs off, and stops on sign-out or a refused token. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveUserDataTest {

    private val anna = UUID(0, 0xA)
    private val other = UUID(0, 0xB)

    private fun session(user: UUID = anna) = Session(
        server = StoredServer("s1", "Home", "http://home.invalid", "12.4.0", lastUsedAt = 0),
        user = StoredUser("s1", user.toString(), "Anna", null, "token", lastUsedAt = 0, deviceId = "d"),
        api = FakeApi,
        installDeviceId = "install",
    )

    private fun change(n: Long, position: Long = 0) = UserDataChange(
        itemId = UUID(1, n),
        played = false,
        positionTicks = position,
        playedPercentage = null,
        isFavorite = false,
        unplayedItemCount = null,
        lastPlayed = null,
    )

    /** A link whose connections each run one of [plans] (the last repeats); notes when each opened. */
    private class FakeLink(
        private val plans:
        List<suspend kotlinx.coroutines.flow.FlowCollector<SocketEvent>.() -> Unit>,
    ) : SocketLink {
        var opens = 0
        val openedAt = mutableListOf<Long>()
        var clock: () -> Long = { 0 }

        override fun open(session: Session) = flow {
            openedAt += clock()
            val plan = plans[opens.coerceAtMost(plans.lastIndex)]
            opens++
            plan()
        }
    }

    private fun TestScope.live(
        link: FakeLink,
        sessions: MutableStateFlow<Session?> = MutableStateFlow(session()),
        accepted: suspend (Session) -> Boolean? = { null },
    ): LiveUserData {
        link.clock = { currentTime }
        return LiveUserData(backgroundScope, sessions, link, accepted, log = {}).also { it.start() }
    }

    @Test
    fun `changes of the signed-in profile are handed on, another user's are not`() = runTest {
        val link = FakeLink(
            listOf({
                emit(SocketEvent.Opened)
                emit(SocketEvent.Changed(other, listOf(change(1))))
                emit(SocketEvent.Changed(anna, listOf(change(2, position = 50))))
                awaitCancellation()
            }),
        )
        val live = live(link)
        val seen = mutableListOf<UserDataChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { live.changes.toList(seen) }
        runCurrent()
        assertEquals(listOf(change(2, position = 50)), seen)
        assertEquals(LiveState.Live, live.state.value)
    }

    @Test
    fun `a dropped socket comes back after 1, 2, 4 s and so on, up to five minutes`() = runTest {
        val link = FakeLink(listOf({ throw IOException("no route") }))
        live(link)
        advanceTimeBy(1_200_000)
        val waits = link.openedAt.zipWithNext { a, b -> b - a }
        // Nixie (tv-player-4k, TV-S8): five minutes at most, where a minute had the box redial all night.
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 128_000L, 256_000L, 300_000L, 300_000L),
            waits.take(11),
        )
    }

    @Test
    fun `the wait starts over once a connection stayed up for a while`() = runTest {
        val link = FakeLink(
            listOf(
                { throw IOException("down") },
                { throw IOException("down") },
                {
                    emit(SocketEvent.Opened)
                    delay(LiveUserData.STABLE_MS + 1_000)
                    throw IOException("dropped")
                },
                { throw IOException("down") },
            ),
        )
        live(link)
        advanceTimeBy(60_000)
        val waits = link.openedAt.zipWithNext { a, b -> b - a }.take(4)
        assertEquals(listOf(1_000L, 2_000L, LiveUserData.STABLE_MS + 2_000L, 2_000L), waits)
    }

    @Test
    fun `a server that opens and closes at once does not get a redial every second`() = runTest {
        val link = FakeLink(
            listOf({
                emit(SocketEvent.Opened)
                throw IOException("closed at once")
            }),
        )
        var checks = 0
        live(link, accepted = {
            checks++
            null
        })
        advanceTimeBy(60_000)
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L),
            link.openedAt.zipWithNext { a, b -> b - a }.take(5),
        )
        assertEquals(link.opens, checks)
    }

    @Test
    fun `a token refused at the handshake stops it for good`() = runTest {
        val link = FakeLink(listOf({ throw SocketRefused("HTTP 401") }))
        val live = live(link)
        advanceTimeBy(600_000)
        assertEquals(1, link.opens)
        assertEquals(LiveState.Stopped, live.state.value)
    }

    @Test
    fun `a token the server no longer takes after a drop stops it, an unreachable server does not`() = runTest {
        val refused = FakeLink(listOf({ throw IOException("dropped") }))
        val stopped = live(refused, accepted = { false })
        advanceTimeBy(600_000)
        assertEquals(1, refused.opens)
        assertEquals(LiveState.Stopped, stopped.state.value)

        val unreachable = FakeLink(listOf({ throw IOException("dropped") }))
        live(unreachable, accepted = { null })
        advanceTimeBy(10_000)
        assertEquals(4, unreachable.opens)
    }

    @Test
    fun `signing out closes the socket and stops the tries`() = runTest {
        val closed = CompletableDeferred<Unit>()
        val link = FakeLink(
            listOf({
                emit(SocketEvent.Opened)
                try {
                    awaitCancellation()
                } finally {
                    closed.complete(Unit)
                }
            }),
        )
        val sessions = MutableStateFlow<Session?>(session())
        val live = live(link, sessions)
        runCurrent()
        sessions.value = null
        runCurrent()
        advanceTimeBy(600_000)
        assertEquals(true, closed.isCompleted)
        assertEquals(1, link.opens)
        assertEquals(LiveState.Off, live.state.value)
    }

    @Test
    fun `the server's messages are read as Jellyfin sends them`() {
        val text = """{"MessageType":"UserDataChanged","MessageId":"m","Data":{"UserId":"$anna",
            "UserDataList":[{"PlaybackPositionTicks":12000000000,"PlayCount":1,"IsFavorite":true,"Played":false,
            "Key":"k","ItemId":"${UUID(
            1,
            7,
        )}","PlayedPercentage":25.5,"LastPlayedDate":"2026-11-21T19:00:00.0000000Z"}]}}"""
        val message = SocketMessages.parse(text) as SocketMessage.UserData
        assertEquals(anna, message.userId)
        val change = message.changes.single()
        assertEquals(UUID(1, 7), change.itemId)
        assertEquals(12_000_000_000L, change.positionTicks)
        assertEquals(true, change.isFavorite)
        assertEquals(
            SocketMessage.ForceKeepAlive(60),
            SocketMessages.parse("""{"MessageType":"ForceKeepAlive","Data":60}"""),
        )
        assertEquals(SocketMessage.Other, SocketMessages.parse("""{"MessageType":"Sessions","Data":[]}"""))
        assertEquals(SocketMessage.Other, SocketMessages.parse("not json"))
        assertEquals("wss://home.example/jf/socket", SocketMessages.socketUrl("https://home.example/jf/"))
    }

    private companion object {
        /** No request goes through it here. */
        val FakeApi: ApiClient = object : ApiClient() {
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
