package io.github.glacier_jellyfin.androidtv.navigation

import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.mbustos27.nixie.tv.library.NixieLibrariesRoute

/**
 * Where an entry of the navigation bar leads, for every screen that shows the bar.
 * Null when [target] is [current], the screen already open, or when no one is
 * signed in. The profile entry ends the session and goes to "Who's watching?".
 */
fun SessionManager.navEvent(target: NavTarget, current: NavTarget?): UiEvent.Navigate? {
    if (target == current) return null
    return when (target) {
        NavTarget.Home -> UiEvent.Navigate(HomeRoute, clearBackStack = true, focusNav = true)

        NavTarget.Search -> UiEvent.Navigate(SearchRoute())

        NavTarget.Settings -> UiEvent.Navigate(SettingsRoute)

        is NavTarget.Library -> UiEvent.Navigate(
            // Nixie: a starred library's entry opens that library (NixieTopBar).
            if (target.kind == LibraryKind.HomeVideos) {
                FolderRoute()
            } else {
                LibraryRoute(target.kind.name, target.libraryId, target.name, fromBar = true)
            },
        )

        NavTarget.Favorites -> UiEvent.Navigate(FavoritesRoute)

        // Nixie (tv-home-two-rows): every collection on the server, A to Z, paged as a library's grid.
        NavTarget.Collections -> UiEvent.Navigate(LibraryRoute(LibraryKind.Movies.name, collections = true))

        // Nixie (tv-home-two-rows): every library's card (NixieLibrariesPage).
        NavTarget.Libraries -> UiEvent.Navigate(NixieLibrariesRoute)

        NavTarget.NowPlaying -> UiEvent.Navigate(MusicRoute())

        NavTarget.LiveTv -> UiEvent.Navigate(LiveTvRoute)

        NavTarget.Profile -> {
            val serverId = session.value?.server?.id ?: return null
            leave()
            UiEvent.Navigate(ProfilesRoute(serverId), clearBackStack = true)
        }
    }
}
