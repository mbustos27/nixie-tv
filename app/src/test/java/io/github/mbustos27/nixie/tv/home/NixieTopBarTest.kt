// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.navigation.LocalNavTargets
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.TopNav
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Nixie: favourite libraries in the top bar, through TopNav's hook; without any, the entries by kind. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class NixieTopBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val kinds = listOf(LibraryKind.Movies, LibraryKind.Shows, LibraryKind.Music)
    private val entries = listOf(NavTarget.Home) + kinds.map { NavTarget.Library(it) } + NavTarget.Favorites

    private fun library(
        n: Long,
        name: String,
        kind: LibraryKind = LibraryKind.Movies,
    ) = Library(UUID(7, n), name, kind, null, null)

    private fun bar(
        favourites: List<Library>,
        active: NavTarget = NavTarget.Home,
        onSelect: (NavTarget) -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalNavTargets provides entries, LocalTopBarFavourites provides favourites) {
                GlacierTheme { TopNav(active = active, kinds = kinds, userName = "Ana", onSelect = onSelect) }
            }
        }
    }

    @Test
    fun `a starred library's entry on its own page does nothing, another one opens`() {
        val marvel = library(1, "Marvel")
        val anime = library(2, "Anime", LibraryKind.Shows)
        val selected = mutableListOf<NavTarget>()
        val ownPage = NavTarget.Library(LibraryKind.Movies, marvel.id.toString(), "Marvel")
        bar(listOf(marvel, anime), active = ownPage) { selected += it }
        compose.onNodeWithText("Marvel").performClick()
        assertEquals(emptyList<NavTarget>(), selected)
        compose.onNodeWithText("Anime").performClick()
        assertEquals(listOf(NavTarget.Library(LibraryKind.Shows, anime.id.toString(), "Anime")), selected)
    }

    @Test
    fun `starred libraries take the place of Movies and Shows`() {
        bar(listOf(library(1, "Marvel"), library(2, "Anime", LibraryKind.Shows)))
        compose.onNodeWithText("Marvel").assertIsDisplayed()
        compose.onNodeWithText("Anime").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Movie").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Show").fetchSemanticsNodes().size)
        compose.onNodeWithText("Music").assertIsDisplayed()
    }

    @Test
    fun `with none starred the bar keeps Movies and Shows by kind`() {
        bar(emptyList())
        compose.onNodeWithText("Movie").assertIsDisplayed()
        compose.onNodeWithText("Show").assertIsDisplayed()
    }

    @Test
    fun `the starred libraries sit where Movies was, Home first`() {
        val bar = withFavouriteLibraries(entries, listOf(library(1, "Marvel")))
        assertEquals(
            listOf(NavTarget.Home, NavTarget.Library(LibraryKind.Movies, UUID(7, 1).toString(), "Marvel")) +
                NavTarget.Library(LibraryKind.Music) + NavTarget.Favorites,
            bar,
        )
    }
}
