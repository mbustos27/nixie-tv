// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import android.annotation.SuppressLint
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import io.github.glacier_jellyfin.androidtv.navigation.FOCUS_NAV
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent

/**
 * Nixie (tv-home-two-rows; the coordinator, from stream R's finding: "Home reloads every row at each Home press"). The
 * top bar's Home ([event], as `navEvent(NavTarget.Home)` makes it: Home, the back stack cleared, the focus on the bar)
 * goes back to the Home already on the back stack, if there is one: it shows what it has at once, with no spinner,
 * the focus on its bar entry ([FOCUS_NAV], which the screen takes on its return), and asks the server again for
 * Continue watching and Up next only (HomeScreen, `HomeViewModel.nixieBackHere`). Glacier built a new Home each time:
 * a spinner, then every request again. False (Glacier's way then) with no Home under the current page, or for any
 * other event, such as a profile that opens (its Home must be its own).
 */
// The back stack is readable only through currentBackStack, library-restricted for lint (as in GlacierNavHost).
@SuppressLint("RestrictedApi")
fun NavController.nixieBackToHome(event: UiEvent.Navigate): Boolean {
    if (event.route != HomeRoute || !event.clearBackStack || !event.focusNav) return false
    val stack = currentBackStack.value
    val home = stack.lastOrNull { it.destination.hasRoute(HomeRoute::class) } ?: return false
    if (home == currentBackStackEntry) return false
    home.savedStateHandle[FOCUS_NAV] = true
    // Everything above Home goes, as with Glacier's cleared back stack (Home is at its bottom: every way to it clears it).
    val popped = popBackStack(home.destination.id, inclusive = false)
    return popped && currentBackStackEntry == home
}
