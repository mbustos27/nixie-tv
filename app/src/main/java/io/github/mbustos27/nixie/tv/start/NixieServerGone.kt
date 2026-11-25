// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.start

import android.annotation.SuppressLint
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.QuickConnectRoute
import io.github.glacier_jellyfin.androidtv.navigation.ServerListRoute
import io.github.glacier_jellyfin.androidtv.navigation.SignInRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent

/**
 * Nixie (tv-fix-a): after a server was removed from the server list ([event] with `nixieDropServer`), its pages under
 * the list go: "Who's watching?" opened the list through "Change server", and Back led there, to profiles of a server
 * no longer on the TV whose sign-in did nothing. Every page from the first of that server's ("Who's watching?",
 * sign-in, Quick Connect) up is popped, and the server list shows again on top: Back then leads to what was under that
 * server's pages (another server's), or out. True when the event was handled here (also when nothing of that server
 * was under the list: the list just stays).
 */
// The back stack is readable only through currentBackStack, library-restricted for lint (as in GlacierNavHost).
@SuppressLint("RestrictedApi")
fun NavController.nixieDropServerPages(event: UiEvent.Navigate): Boolean {
    val serverId = event.nixieDropServer ?: return false
    val stack = currentBackStack.value.filter { it.destination.route != null && it.destination.parent != null }
    val first = stack.indexOfFirst { it.isPageOf(serverId) }
    if (first < 0) return true
    val pops = stack.size - first
    if (first == 0) {
        navigate(ServerListRoute) { popUpTo(0) { inclusive = true } }
        return true
    }
    repeat(pops) { popBackStack() }
    navigate(ServerListRoute) { launchSingleTop = true }
    return true
}

/** A page that belongs to the server [serverId]: its "Who's watching?", sign-in or Quick Connect. */
private fun NavBackStackEntry.isPageOf(serverId: String): Boolean = runCatching {
    when {
        destination.hasRoute(ProfilesRoute::class) -> toRoute<ProfilesRoute>().serverId == serverId
        destination.hasRoute(SignInRoute::class) -> toRoute<SignInRoute>().serverId == serverId
        destination.hasRoute(QuickConnectRoute::class) -> toRoute<QuickConnectRoute>().serverId == serverId
        else -> false
    }
}.getOrDefault(false)
