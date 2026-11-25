package io.github.glacier_jellyfin.androidtv.navigation

import android.annotation.SuppressLint
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.detail.DetailScreen
import io.github.glacier_jellyfin.androidtv.detail.PersonScreen
import io.github.glacier_jellyfin.androidtv.favorites.FavoritesScreen
import io.github.glacier_jellyfin.androidtv.home.HomeScreen
import io.github.glacier_jellyfin.androidtv.library.FolderMemory
import io.github.glacier_jellyfin.androidtv.library.FolderScreen
import io.github.glacier_jellyfin.androidtv.library.LibraryScreen
import io.github.glacier_jellyfin.androidtv.library.PhotoScreen
import io.github.glacier_jellyfin.androidtv.livetv.LiveTvPlayerScreen
import io.github.glacier_jellyfin.androidtv.livetv.LiveTvScreen
import io.github.glacier_jellyfin.androidtv.music.MusicPlayerScreen
import io.github.glacier_jellyfin.androidtv.player.PlayerScreen
import io.github.glacier_jellyfin.androidtv.profiles.ProfilesScreen
import io.github.glacier_jellyfin.androidtv.search.SearchScreen
import io.github.glacier_jellyfin.androidtv.seerr.SeerrScreen
import io.github.glacier_jellyfin.androidtv.settings.SettingsScreen
import io.github.glacier_jellyfin.androidtv.setup.QuickConnectScreen
import io.github.glacier_jellyfin.androidtv.setup.ServerAddressScreen
import io.github.glacier_jellyfin.androidtv.setup.ServerListScreen
import io.github.glacier_jellyfin.androidtv.setup.SignInScreen
import io.github.glacier_jellyfin.androidtv.trailer.TrailerScreen
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.mbustos27.nixie.tv.diagnostics.NixieLogText
import io.github.mbustos27.nixie.tv.home.nixieBackToHome
import io.github.mbustos27.nixie.tv.library.NixieLibrariesRoute
import io.github.mbustos27.nixie.tv.library.NixieLibrariesScreen
import io.github.mbustos27.nixie.tv.start.nixieDropServerPages
import io.github.mbustos27.nixie.tv.timing.NixieKeyClock
import io.github.mbustos27.nixie.tv.timing.nixieKeyClock

/** Screens without arguments; navigating to one that is already on top does nothing. */
private val SingleScreens = setOf(
    ServerListRoute,
    ServerAddressRoute,
    HomeRoute,
    SettingsRoute,
    FavoritesRoute,
    LiveTvRoute,
)

/** Pages about one title or person, which a jump to another title through "More like this" leaves. */
private val TitlePages = listOf(DetailRoute::class, PersonRoute::class, SeerrRoute::class)

private const val TAG = "Navigation"

@Composable
fun GlacierNavHost(
    navController: NavHostController,
    startDestination: Any,
    folderMemory: () -> FolderMemory,
) {
    // The whole back stack is readable only through currentBackStack, which lint reports as
    // library-restricted although Navigation offers no public way to it.
    @SuppressLint("RestrictedApi")
    val navigate: (UiEvent.Navigate) -> Unit = navigate@{ event ->
        // Nixie (tv-1.0-fixes): ids, no names (a shared log may go into a public issue).
        Log.d(TAG, "Open ${NixieLogText.route(event.route)}")
        NixieKeyClock.navigated() // Nixie (TV-S13): the wait from the OK, split
        // Nixie (tv-home-two-rows): the top bar's Home goes back to the Home on the back stack, as it is.
        if (navController.nixieBackToHome(event)) return@navigate
        // Nixie (tv-fix-a): a removed server's pages leave the back stack, so Back never leads to them.
        if (navController.nixieDropServerPages(event)) return@navigate
        val current = navController.currentDestination?.id
        // The screen under the title and cast pages on top, such as a library or search.
        // It learns the title opened last, to focus that one when Back returns to it.
        val base = if (event.leaveTitles) {
            navController.currentBackStack.value.lastOrNull { entry ->
                TitlePages.none {
                    entry.destination.hasRoute(it)
                }
            }
                ?.also { entry ->
                    (event.route as? DetailRoute)?.let { entry.savedStateHandle[RETURN_FOCUS] = it.itemId }
                }
                ?.destination?.id
        } else {
            null
        }
        // "Fotos & Videos" from the bar opens where the user left it: the top and each folder below it get
        // their screen on the back stack, in one go, so only the last one shows.
        val trail = folderMemory().takeIf {
            event.route == FolderRoute() && !event.clearBackStack && !event.replace &&
                navController.currentDestination?.hasRoute(FolderRoute::class) != true
        }?.takeIf { it.path.isNotEmpty() }
        val route = if (trail != null) FolderRoute(focus = trail.path.first().id.toString()) else event.route
        navController.navigate(route) {
            when {
                event.clearBackStack -> popUpTo(0) { inclusive = true }
                base != null -> popUpTo(base)
                (event.replace || event.leaveTitles) && current != null -> popUpTo(current) { inclusive = true }
            }
            // Only parameterless screens are kept single: a library, genre or
            // detail page with other arguments is a new screen of the same kind.
            launchSingleTop = event.route in SingleScreens
        }
        trail?.path?.forEachIndexed { index, folder ->
            val focus = trail.path.getOrNull(index + 1)?.id?.toString() ?: trail.focusId
            navController.navigate(FolderRoute(folder.id.toString(), folder.name, focus))
        }
        if (event.focusNav) navController.currentBackStackEntry?.savedStateHandle?.set(FOCUS_NAV, true)
    }
    val back: () -> Unit = {
        Log.d(TAG, "Back")
        navController.popBackStack()
    }
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // Nixie (TV-S13): every OK is noted (NixieKeyClock), for the detailed log's waits from the key.
        modifier = Modifier.nixieKeyClock(),
        // The design's screen entrance (gScreen): fade in while rising 14px.
        enterTransition = { fadeIn() + slideInVertically { 14 } },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        composable<ServerListRoute> {
            ServerListScreen(onNavigate = navigate, onManualAddress = { navController.navigate(ServerAddressRoute) })
        }
        composable<ServerAddressRoute> { ServerAddressScreen(onNavigate = navigate, onBack = back) }
        composable<SignInRoute> { SignInScreen(onNavigate = navigate) }
        composable<QuickConnectRoute> { QuickConnectScreen(onNavigate = navigate, onBack = back) }
        composable<ProfilesRoute> { ProfilesScreen(onNavigate = navigate) }
        composable<HomeRoute> { entry ->
            HomeScreen(
                onNavigate = navigate,
                takeFocusNav = { entry.savedStateHandle.remove<Boolean>(FOCUS_NAV) == true },
            )
        }
        composable<LibraryRoute> { entry ->
            LibraryScreen(
                onNavigate = navigate,
                takeReturnFocus = { entry.savedStateHandle.remove<String>(RETURN_FOCUS) },
            )
        }
        composable<FolderRoute> { FolderScreen(onNavigate = navigate) }
        // Nixie (tv-home-two-rows): the top bar's Libraries page.
        composable<NixieLibrariesRoute> { NixieLibrariesScreen(onNavigate = navigate) }
        composable<PhotoRoute> { PhotoScreen(onNavigate = navigate) }
        composable<DetailRoute> { DetailScreen(onNavigate = navigate, onBack = back) }
        composable<PersonRoute> { PersonScreen(onNavigate = navigate) }

        // Later development steps; placeholders keep the navigation testable.
        composable<TrailerRoute> { TrailerScreen(onNavigate = navigate, onBack = back) }
        composable<PlayerRoute> { PlayerScreen(onBack = back) }
        composable<MusicRoute> { MusicPlayerScreen(onBack = back) }
        composable<SearchRoute> { SearchScreen(onNavigate = navigate) }
        composable<SeerrRoute> { SeerrScreen(onNavigate = navigate) }
        composable<SettingsRoute> { SettingsScreen(onNavigate = navigate) }
        composable<FavoritesRoute> { FavoritesScreen(onNavigate = navigate) }
        composable<LiveTvRoute> { LiveTvScreen(onNavigate = navigate) }
        composable<LiveTvPlayerRoute> { LiveTvPlayerScreen(onNavigate = navigate, onBack = back) }
    }
}
