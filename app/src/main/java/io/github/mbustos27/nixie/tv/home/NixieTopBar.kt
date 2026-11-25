// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.mbustos27.nixie.tv.data.TopBarLibraries
import java.util.UUID

/*
 * Nixie: favourite libraries in the top bar (desktop Nixie 1.6; the rule in TopBarLibraries).
 * Glacier's TopNav calls [nixieTopBar] through a hook marked "// Nixie:".
 */

/** The starred libraries; null reads them from the app (tests and screenshots provide their own). */
val LocalTopBarFavourites = compositionLocalOf<List<Library>?> { null }

/** The kinds whose entries the starred libraries replace. */
private val ReplacedKinds = setOf(LibraryKind.Movies, LibraryKind.Shows)

/**
 * [targets] (the bar's entries between search and settings) with the starred libraries in place
 * of the Movies and Shows entries; unchanged with none starred. Null stays null (not known yet).
 */
@Composable
fun nixieTopBar(targets: List<NavTarget>?): List<NavTarget>? =
    targets?.let { withFavouriteLibraries(it, topBarFavourites()) }

/** The bar's entry for a library page: the starred library's own one, else its kind's. */
@Composable
fun nixieLibraryTarget(kind: LibraryKind, libraryId: UUID?): NavTarget =
    topBarFavourites().firstOrNull { it.id == libraryId }?.let(::favouriteTarget) ?: NavTarget.Library(kind)

/**
 * [target] is the starred library whose page is open ([active], see [nixieLibraryTarget]): selecting it again
 * does nothing, as Glacier's navigation does for its current tab, so Back still takes one press.
 */
fun isOwnStarredPage(target: NavTarget, active: NavTarget): Boolean =
    target is NavTarget.Library && target.libraryId != null && target == active

fun withFavouriteLibraries(targets: List<NavTarget>, favourites: List<Library>): List<NavTarget> {
    if (favourites.isEmpty()) return targets
    val byKind = { target: NavTarget ->
        target is NavTarget.Library && target.libraryId == null && target.kind in ReplacedKinds
    }
    val kept = targets.filterNot(byKind)
    // Where the first Movies or Shows entry was, else after Home.
    val at = targets.indexOfFirst(byKind).takeIf { it >= 0 } ?: (kept.indexOf(NavTarget.Home) + 1)
    return kept.toMutableList().apply { addAll(at.coerceIn(0, size), favourites.map(::favouriteTarget)) }
}

private fun favouriteTarget(library: Library) = NavTarget.Library(library.kind, library.id.toString(), library.name)

@Composable
private fun topBarFavourites(): List<Library> {
    LocalTopBarFavourites.current?.let { return it }
    val context = LocalContext.current.applicationContext
    // Outside the Hilt app (a screenshot test) there are no stars to read.
    val flow = remember(context) {
        runCatching {
            EntryPointAccessors.fromApplication(context, TopBarEntryPoint::class.java).topBarLibraries().favourites
        }.getOrNull()
    }
    val state: State<List<Library>>? = flow?.collectAsState()
    return state?.value.orEmpty()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TopBarEntryPoint {
    fun topBarLibraries(): TopBarLibraries
}
