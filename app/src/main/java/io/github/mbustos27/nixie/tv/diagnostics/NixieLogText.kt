// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import io.github.glacier_jellyfin.androidtv.navigation.FolderRoute
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.PersonRoute
import io.github.glacier_jellyfin.androidtv.navigation.SearchRoute
import io.github.glacier_jellyfin.androidtv.navigation.SignInRoute

/**
 * Nixie (tv-1.0-fixes, the prep stream): the log, shared through Download log or Report a problem, may go into a
 * public issue, so no title or name reaches it. A page that opens is logged by its ids, kinds and flags; the names
 * a route carries for its heading (a library's, a folder's, a person's, the film a person was opened from, the role,
 * the user name of a sign-in, typed search text) only by their length.
 */
object NixieLogText {

    /** [route] as the navigation log writes it: `LibraryRoute(kind=Movies, libraryId=…, title=<6 chars>, …)`. */
    fun route(route: Any): String = when (route) {
        is LibraryRoute -> route.copy(title = route.title?.let(::length))

        is FolderRoute -> route.copy(title = route.title?.let(::length))

        is PersonRoute -> route.copy(
            fromTitle = route.fromTitle?.let(::length),
            role = route.role?.let(::length),
            name = route.name?.let(::length),
        )

        is SignInRoute -> route.copy(username = route.username?.let(::length))

        is SearchRoute -> route.copy(query = route.query?.let(::length))

        else -> route
    }.toString()

    /** What stands for a name: its length, enough to tell an empty one or a very long one apart. */
    fun length(text: String): String = "<${text.length} chars>"
}
