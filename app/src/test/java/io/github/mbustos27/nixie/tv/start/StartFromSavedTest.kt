// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.start

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StartMode
import io.github.glacier_jellyfin.androidtv.core.data.StartProfile
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerFinder
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.profiles.ProfilesViewModel
import io.github.glacier_jellyfin.androidtv.setup.ServerConnector
import io.github.glacier_jellyfin.androidtv.setup.ServerReachability
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.mbustos27.nixie.tv.data.SavedHome
import io.github.mbustos27.nixie.tv.data.StartLine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * tv-home-start: an app start opens the start profile from what is saved on the device and goes to Home at once;
 * the server (its id, its users, the token) is asked afterwards. On a slow box after standby those three requests
 * kept Home away for up to tens of seconds (DESIGN-RULES 1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StartFromSavedTest {

    private val server = StoredServer("s1", "Home", "http://home.invalid", "12.4.0", lastUsedAt = 0)
    private val anna = StoredUser("s1", "a1", "anna", null, "token", lastUsedAt = 2, deviceId = "d")
    private val ben = StoredUser("s1", "b2", "ben", null, "token-b", lastUsedAt = 1, deviceId = "d")

    /** A server that never answers: anything that waits on it never gets to Home. */
    private val finder = mockk<ServerFinder> { coEvery { identify(any()) } coAnswers { awaitCancellation() } }
    private val authenticator = mockk<Authenticator>(relaxed = true) {
        coEvery { isTokenValid(any(), any(), any()) } coAnswers { awaitCancellation() }
        coEvery { publicUsers(any()) } coAnswers { awaitCancellation() }
    }
    private val sessions = mockk<SessionManager>(relaxed = true) { coEvery { open(any(), any()) } returns true }
    private val check = mockk<StartCheck>(relaxed = true)

    private fun profiles(state: AccountState, remembered: String?): ProfilesViewModel {
        val settings = mockk<SettingsRepository> { every { accents(any()) } returns flowOf(emptyMap()) }
        val memory = mockk<StartMemory>(relaxed = true) { every { single(any()) } returns remembered }
        val accounts = mockk<AccountRepository> {
            coEvery { current() } returns state
            every { this@mockk.state } returns flowOf(state)
        }
        return ProfilesViewModel(
            SavedStateHandle(mapOf("serverId" to "s1", "appStart" to true)),
            accounts,
            authenticator,
            sessions,
            settings,
            ServerReachability(),
            finder,
            mockk<ServerConnector>(relaxed = true),
            StartFromSaved(accounts, sessions, memory, mockk<SavedHome>(relaxed = true), check, StartLine()),
            memory,
        )
    }

    private fun firstEvent(model: ProfilesViewModel): UiEvent? = runBlocking {
        withTimeoutOrNull(2_000) {
            var event: UiEvent? = null
            while (event == null) {
                ShadowLooper.idleMainLooper()
                event = withTimeoutOrNull(50) { model.events.first() }
            }
            event
        }
    }

    @Test
    fun `the single profile opens from what is saved, and Home comes without a request`() {
        val model = profiles(AccountState(servers = listOf(server), users = listOf(anna)), remembered = "a1")

        val event = firstEvent(model) as? UiEvent.Navigate

        assertEquals(HomeRoute, event?.route)
        coVerify(exactly = 0) { finder.identify(any()) }
        coVerify(exactly = 0) { authenticator.isTokenValid(any(), any(), any()) }
        coVerify(exactly = 0) { authenticator.publicUsers(any()) }
        coVerify { sessions.open("s1", "a1") }
        // The server is asked after, in the background.
        verify { check.verify(server, match { it.userId == "a1" }) }
    }

    @Test
    fun `the profile used last opens from what is saved`() {
        val state = AccountState(
            servers = listOf(server),
            users = listOf(anna, ben),
            startProfile = StartProfile(mode = StartMode.Last),
        )

        val event = firstEvent(profiles(state, remembered = null)) as? UiEvent.Navigate

        assertEquals(HomeRoute, event?.route)
        coVerify { sessions.open("s1", "a1") }
    }

    @Test
    fun `with nothing remembered for the single profile the start asks the server, as before`() {
        val model = profiles(AccountState(servers = listOf(server), users = listOf(anna)), remembered = null)

        assertNull("no Home before the server answered", firstEvent(model))
        coVerify { finder.identify("http://home.invalid") }
    }

    @Test
    fun `a profile without a token never opens from what is saved`() {
        val signedOut = anna.copy(accessToken = null)
        assertNull(
            SavedStart.choice(AccountState(servers = listOf(server), users = listOf(signedOut)), "s1", null, "a1"),
        )
        val last = AccountState(
            servers = listOf(server),
            users = listOf(signedOut),
            startProfile = StartProfile(StartMode.Last),
        )
        assertNull(SavedStart.choice(last, "s1", null, null))
        assertEquals(
            "a1",
            SavedStart.choice(AccountState(servers = listOf(server), users = listOf(anna)), "s1", "a1", null)?.userId,
        )
    }
}
