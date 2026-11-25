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
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsSync
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nixie (tv-gate-a, tv-fix-a): removing a saved server from the server list's menu. The TV's part first, then the
 * sign-outs, best effort, all at once, each with a timeout, from the removal's own scope. Revert checks: without
 * accounts.removeServer the first three fail; with the sign-outs sent before the TV's part (as before tv-fix-a) the
 * hanging server's and the parallel ones fail.
 */
class ServerRemovalTest {

    /** Updates one at a time, as DataStore makes them (opening a session writes "used last" from another thread). */
    private class MemoryStore(start: AccountState) : DataStore<AccountState> {
        override val data = MutableStateFlow(start)
        private val lock = Mutex()

        override suspend fun updateData(transform: suspend (t: AccountState) -> AccountState): AccountState =
            lock.withLock { transform(data.value).also { data.value = it } }
    }

    /** Every request the profiles' API clients send: base address, path, token and device id. */
    private val sent = java.util.Collections.synchronizedList(mutableListOf<List<String?>>())
    private var answers = true

    /** A server that is down and never answers: every request waits until it is cancelled. */
    @Volatile private var hangs = false

    private fun logouts() = synchronized(sent) { sent.filter { it[1] == "/Sessions/Logout" } }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(20)
        }
        return true
    }

    private val jellyfin = Jellyfin(
        JellyfinOptions(
            context = ContextWrapper(null),
            clientInfo = ClientInfo("Nixie for TV", "test"),
            deviceInfo = DeviceInfo(id = "install-id", name = "Test TV"),
            apiClientFactory = Factory(),
            socketConnectionFactory = SocketConnectionFactory { _, _ -> error("no sockets here") },
            minimumServerVersion = Jellyfin.minimumVersion,
        ),
    )

    private fun user(
        server: String,
        id: String,
        token: String?,
    ) = StoredUser(server, id, id, null, accessToken = token, lastUsedAt = 0, deviceId = "$id-device")

    private val store = MemoryStore(
        AccountState(
            servers = listOf(
                StoredServer("one", "First", "http://one.invalid", "12.4.0", lastUsedAt = 2),
                StoredServer("two", "Second", "http://two.invalid", "12.4.0", lastUsedAt = 1),
            ),
            users = listOf(
                user("one", "anna", "token-anna"),
                user("one", "jonas", token = null),
                user("one", "carl", "token-carl"),
                user("two", "bea", "token-bea"),
            ),
        ),
    )
    private val accounts = AccountRepository(store)
    private val sessions = SessionManager(jellyfin, accounts, Authenticator(jellyfin))
    private val settings = mockk<SettingsSync>(relaxed = true)
    private val removal = ServerRemoval(accounts, Authenticator(jellyfin), sessions, settings)

    @Test
    fun `the server in use goes with its profiles, then each is signed out on the server, the other server stays`() =
        runBlocking {
            sessions.open("one", "anna")

            assertTrue(removal.remove("one"))

            assertNull("the session on the removed server is left", sessions.session.value)
            assertEquals(listOf("two"), store.data.value.servers.map { it.id })
            assertEquals(listOf("bea"), store.data.value.users.map { it.userId })
            // Anna's and Carl's tokens ended on the server, each as its own device; Jonas had none; nothing to two.
            assertTrue("the sign-outs went out", waitFor { logouts().size == 2 })
            assertEquals(
                setOf(
                    listOf("http://one.invalid", "/Sessions/Logout", "token-anna", "anna-device"),
                    listOf("http://one.invalid", "/Sessions/Logout", "token-carl", "carl-device"),
                ),
                logouts().toSet(),
            )
            coVerify { settings.forget("one", "anna") }
            coVerify { settings.forget("one", "jonas") }
            coVerify { settings.forget("one", "carl") }
            coVerify(exactly = 0) { settings.forget("two", any()) }
        }

    @Test
    fun `a server that does not answer is still removed here`() = runBlocking {
        answers = false

        assertTrue(removal.remove("two"))

        assertEquals(listOf("one"), store.data.value.servers.map { it.id })
        assertEquals(listOf("anna", "jonas", "carl"), store.data.value.users.map { it.userId })
        assertTrue(waitFor { logouts().size == 1 })
        assertEquals(listOf("http://two.invalid", "/Sessions/Logout", "token-bea", "bea-device"), logouts().single())
    }

    @Test
    fun `a server that never answers is gone with its tokens though the screen's job is cancelled mid-way`() =
        runBlocking {
            hangs = true
            // The screen's job: it waits for the removal and is cancelled (Back) while a sign-out hangs.
            val screen = launch(Dispatchers.Default) { removal.start("one").await() }
            assertTrue("a sign-out was sent", waitFor { logouts().isNotEmpty() })
            screen.cancelAndJoin()

            assertEquals(listOf("two"), store.data.value.servers.map { it.id })
            assertTrue(
                "no token of the removed server is left",
                store.data.value.users.none { it.serverId == "one" },
            )
        }

    @Test
    fun `the sign-outs go out at once and none is waited for beyond the timeout`() = runBlocking {
        hangs = true
        removal.signOutTimeoutMs = 300
        val started = System.currentTimeMillis()

        // Bounded: a removal that waits for the server would hang here.
        assertTrue(withTimeoutOrNull(3_000) { removal.remove("one") } == true)

        assertTrue("the TV's part does not wait for the server", System.currentTimeMillis() - started < 1_000)
        assertTrue("both sign-outs were sent together", waitFor(1_000) { logouts().size == 2 })
    }

    @Test
    fun `a removal already running is not started twice`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        coEvery { settings.forget(any(), any()) } coAnswers { gate.await() }
        val first = removal.start("one")
        assertTrue(waitFor { "one" in removal.removing.value })

        assertFalse("a second removal of the same server", removal.start("one").await())
        gate.complete(Unit)
        assertTrue(first.await())
        assertTrue(removal.removing.value.isEmpty())
    }

    @Test
    fun `an unknown server changes nothing`() = runBlocking {
        val before = store.data.value

        assertFalse(removal.remove("three"))

        assertEquals(before, store.data.value)
        assertTrue(sent.isEmpty())
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
            sent += listOf(baseUrl, pathTemplate, accessToken, deviceInfo.id)
            if (hangs) awaitCancellation()
            if (!answers) error("the server does not answer")
            return RawResponse(ByteArray(0), 204, emptyMap())
        }
    }
}
