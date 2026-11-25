// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import android.annotation.SuppressLint
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.navigation.FOCUS_NAV
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.navEvent
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * tv-home-two-rows (the coordinator, from stream R: "Home reloads every row at each Home press"): the top bar's Home goes
 * back to the Home on the back stack, the same screen with its view model and what it shows, and asks it to focus its
 * bar entry; Glacier cleared the back stack and built a new Home. A real NavHostController with Nixie's routes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NixieHomeAgainTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun host(start: Any = HomeRoute) {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = start) {
                composable<HomeRoute> { }
                composable<LibraryRoute> { }
            }
        }
        compose.waitForIdle()
    }

    /** The top bar's Home on a library page, as the app makes it. */
    private val homePress: UiEvent.Navigate =
        mockk<SessionManager>(relaxed = true).navEvent(NavTarget.Home, NavTarget.Library(LibraryKind.Movies))!!

    @SuppressLint("RestrictedApi")
    private fun stack() = nav.currentBackStack.value.filter { it.destination.id != nav.graph.id }

    @Test
    fun `the top bar's Home goes back to the Home on the back stack, as it is`() {
        host()
        val home = nav.currentBackStackEntry!!
        compose.runOnIdle { nav.navigate(LibraryRoute(LibraryKind.Movies.name)) }
        compose.runOnIdle { nav.navigate(LibraryRoute(LibraryKind.Movies.name, collections = true)) }
        compose.waitForIdle()

        val back = compose.runOnIdle { nav.nixieBackToHome(homePress) }

        assertTrue(back)
        assertSame("the same Home, with its view model", home, nav.currentBackStackEntry)
        assertEquals("the pages above it are gone", listOf(home), stack())
        assertEquals("it is asked to focus its bar entry", true, home.savedStateHandle.get<Boolean>(FOCUS_NAV))
    }

    @Test
    fun `with no Home on the back stack, or for another way to Home, Glacier's way stays`() {
        host(start = LibraryRoute(LibraryKind.Movies.name))
        assertFalse(compose.runOnIdle { nav.nixieBackToHome(homePress) })

        // A profile that opens has a Home of its own: no focus on the bar, the back stack cleared, a new Home.
        val profile = UiEvent.Navigate(HomeRoute, clearBackStack = true)
        compose.runOnIdle { nav.navigate(HomeRoute) }
        compose.runOnIdle { nav.navigate(LibraryRoute(LibraryKind.Movies.name)) }
        assertFalse(compose.runOnIdle { nav.nixieBackToHome(profile) })
    }
}
