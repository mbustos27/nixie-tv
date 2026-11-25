// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.start

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerFinder
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.QuickConnectRoute
import io.github.glacier_jellyfin.androidtv.navigation.ServerListRoute
import io.github.glacier_jellyfin.androidtv.navigation.SignInRoute
import io.github.glacier_jellyfin.androidtv.setup.ServerListViewModel
import io.github.glacier_jellyfin.androidtv.setup.ServerReachability
import io.github.glacier_jellyfin.androidtv.setup.SignInViewModel
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.mbustos27.nixie.tv.data.ServerRemoval
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Nixie (tv-fix-a): the review's two Majors in removing a server. While a removal runs, Menu opens nothing and
 * "Removing …" shows; at its end the removed server's pages leave the back stack, so Back never shows its profiles;
 * a sign-in whose server is gone says so and leads to the server list. Revert checks: without openMenu's guard the
 * first fails; with nixieDropServerPages doing nothing the back stack ones fail; without the sign-in's check the last.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServerGoneTest {
    @get:Rule val compose = createComposeRule()

    private val saved = AccountState(
        servers = listOf(
            StoredServer("x", "Gone soon", "http://x.invalid", "12.4.0", lastUsedAt = 2),
            StoredServer("y", "Staying", "http://y.invalid", "12.4.0", lastUsedAt = 1),
        ),
        users = listOf(StoredUser("x", "anna", "anna", null, accessToken = "token", lastUsedAt = 0)),
    )

    private val accounts = mockk<AccountRepository> { coEvery { current() } returns saved }

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    private fun serverList(removal: ServerRemoval): ServerListViewModel {
        val finder = mockk<ServerFinder> {
            every { discover() } returns emptyFlow()
            coEvery { probe(any()) } returns null
        }
        return ServerListViewModel(accounts, finder, mockk(relaxed = true), ServerReachability(), removal)
    }

    private fun removal(done: CompletableDeferred<Boolean>) = mockk<ServerRemoval> {
        every { removing } returns MutableStateFlow(emptySet())
        every { start("x") } returns done
    }

    @Test
    fun `while a removal runs Removing shows and Menu opens nothing`() = runTest {
        val done = CompletableDeferred<Boolean>()
        val vm = serverList(removal(done))
        val x = vm.state.value.saved.single { it.server.id == "x" }
        val y = vm.state.value.saved.single { it.server.id == "y" }

        vm.openMenu(x)
        vm.askRemove()
        vm.remove()
        assertEquals("Removing shows at once", "x", vm.state.value.removing?.server?.id)
        vm.openMenu(y)
        assertNull("Menu during the removal opened a menu", vm.state.value.menuFor)

        done.complete(true)
        assertNull(vm.state.value.removing)
        assertEquals(listOf("y"), vm.state.value.saved.map { it.server.id })
        assertEquals("x", vm.state.value.removed)
    }

    @Test
    fun `the end of a removal asks for the server's pages to leave the back stack`() = runTest {
        val done = CompletableDeferred<Boolean>()
        val vm = serverList(removal(done))
        vm.openMenu(vm.state.value.saved.single { it.server.id == "x" })
        vm.askRemove()
        vm.remove()
        done.complete(true)

        val events = vm.events.take(2).toList()
        assertEquals(UiEvent.Toast(R.string.nixie_server_removed, listOf("Gone soon")), events[0])
        assertEquals(UiEvent.Navigate(ServerListRoute, nixieDropServer = "x"), events[1])
    }

    private fun navHost(): NavHostController {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = HomeRoute) {
                composable<HomeRoute> {}
                composable<ProfilesRoute> {}
                composable<ServerListRoute> {}
                composable<SignInRoute> {}
                composable<QuickConnectRoute> {}
            }
        }
        compose.waitForIdle()
        return nav
    }

    private fun NavHostController.showsProfilesOf(serverId: String) = currentBackStackEntry?.let {
        it.destination.hasRoute(ProfilesRoute::class) && it.toRoute<ProfilesRoute>().serverId == serverId
    } == true

    @Test
    fun `Back after removing the server whose profiles were under the list does not show them`() {
        val nav = navHost()
        compose.runOnIdle {
            nav.navigate(ProfilesRoute("x")) { popUpTo(0) { inclusive = true } }
            nav.navigate(ServerListRoute) // "Change server"
            assertTrue(nav.nixieDropServerPages(UiEvent.Navigate(ServerListRoute, nixieDropServer = "x")))
        }
        compose.runOnIdle {
            assertTrue("the server list stays", nav.currentDestination!!.hasRoute(ServerListRoute::class))
            nav.popBackStack() // Back
        }
        compose.runOnIdle { assertFalse("Back showed the removed server's profiles", nav.showsProfilesOf("x")) }
    }

    @Test
    fun `Back after the removal leads to another server's profiles under them`() {
        val nav = navHost()
        compose.runOnIdle {
            nav.navigate(ProfilesRoute("y")) { popUpTo(0) { inclusive = true } }
            nav.navigate(ServerListRoute)
            nav.navigate(ProfilesRoute("x"))
            nav.navigate(SignInRoute("x"))
            nav.navigate(ServerListRoute)
            nav.nixieDropServerPages(UiEvent.Navigate(ServerListRoute, nixieDropServer = "x"))
        }
        compose.runOnIdle { nav.popBackStack() }
        compose.runOnIdle { assertTrue("Back did not reach the other server's profiles", nav.showsProfilesOf("y")) }
    }

    @Test
    fun `a sign-in whose server is gone says so and leads to the server list`() = runTest {
        val vm = SignInViewModel(
            SavedStateHandle(mapOf("serverId" to "removed")),
            accounts,
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        vm.signIn()

        val events = vm.events.take(2).toList()
        assertEquals(UiEvent.Toast(R.string.nixie_signin_server_gone), events[0])
        assertEquals(UiEvent.Navigate(ServerListRoute, clearBackStack = true), events[1])
    }
}
