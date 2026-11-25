// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import android.app.Application
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.settings.NavigationSettings
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.navigation.FolderRoute
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.LocalNavTargets
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.TopNav
import io.github.glacier_jellyfin.androidtv.navigation.navEvent
import io.github.glacier_jellyfin.androidtv.navigation.navTargets
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.mbustos27.nixie.tv.home.LocalTopBarFavourites
import io.mockk.mockk
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * tv-home-two-rows (the coordinator: "a library that can't be reached is a basics bug"; a big shared server can have
 * two dozen libraries): from Home, with the remote only, the top bar's Libraries entry (one press right of Home)
 * opens every library's card, and each card opens its own library. Glacier's real TopNav and Nixie's Libraries page,
 * 23 libraries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class LibrariesReachTest {

    @get:Rule
    val compose = createComposeRule()

    /** 23 libraries as such a server has them: 14 of films, 6 of shows, 2 of music, 1 of home videos. */
    private val libraries: List<Library> = buildList {
        repeat(MOVIES) { add(library("Films ${'A' + it}", LibraryKind.Movies)) }
        repeat(SHOWS) { add(library("Series ${'A' + it}", LibraryKind.Shows)) }
        repeat(MUSIC) { add(library("Music ${'A' + it}", LibraryKind.Music)) }
        add(library("Family videos", LibraryKind.HomeVideos))
    }

    private fun library(name: String, kind: LibraryKind) =
        Library(UUID.nameUUIDFromBytes(name.toByteArray()), name, kind, itemCount = 100, imageUrl = null)

    private val targets = navTargets(NavigationSettings(), libraries.map { it.kind }.distinct().sorted(), liveTv = true)

    @Test
    fun `Libraries is one press right of Home in the top bar, and the per-type entries stay`() {
        assertEquals(NavTarget.Home, targets[0])
        assertEquals(NavTarget.Libraries, targets[1])
        listOf(LibraryKind.Movies, LibraryKind.Shows, LibraryKind.Music, LibraryKind.HomeVideos).forEach {
            assertTrue("$it keeps its entry", NavTarget.Library(it) in targets)
        }
        assertTrue(NavTarget.Collections in targets && NavTarget.LiveTv in targets && NavTarget.Favorites in targets)
        val event = mockk<SessionManager>(relaxed = true).navEvent(NavTarget.Libraries, NavTarget.Home)
        assertEquals(UiEvent.Navigate(NixieLibrariesRoute), event)
    }

    @Test
    fun `from Home, with the remote, every one of 23 libraries opens`() {
        val opened = mutableListOf<Library>()
        var page by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(
                LocalInputModeManager provides DpadMode,
                LocalNavTargets provides targets,
                LocalTopBarFavourites provides emptyList(),
            ) {
                GlacierTheme {
                    Box(Modifier.fillMaxSize()) {
                        val cards = remember { FocusRequester() }
                        if (page) {
                            NixieLibrariesPage(
                                libraries,
                                loading = false,
                                onOpen = { opened += it },
                                cardsModifier = Modifier.focusRequester(cards),
                            )
                            LaunchedEffect(Unit) { cards.requestFocus() }
                        }
                        TopNav(
                            active = if (page) NavTarget.Libraries else NavTarget.Home,
                            kinds = emptyList(),
                            userName = "Ana",
                            onSelect = { if (it == NavTarget.Libraries) page = true },
                            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 34.dp),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Home").requestFocus()
        compose.waitForIdle()

        press(KEYCODE_DPAD_RIGHT)
        assertEquals("one press right of Home", "Libraries", focusedText())
        press(KEYCODE_DPAD_CENTER)
        assertTrue(page)

        // Across each row of cards and down to the next, opening each card on the way.
        val seen = mutableSetOf<String>()
        var right = true
        repeat(libraries.size * 2) {
            seen += focusedText()
            press(KEYCODE_DPAD_CENTER)
            val before = focusedText()
            press(if (right) KEYCODE_DPAD_RIGHT else KEYCODE_DPAD_LEFT)
            if (focusedText() == before) {
                press(KEYCODE_DPAD_DOWN)
                right = !right
            }
        }
        assertEquals("every card had the focus", libraries.size, seen.size)
        assertEquals("every library opened", libraries.toSet(), opened.toSet())
        assertEachLeadsToItsLibrary()
    }

    /** Each card leads to its own library: its grid, or home videos' folders. */
    private fun assertEachLeadsToItsLibrary() {
        libraries.forEach { library ->
            val route = nixieLibraryRoute(library)
            if (library.kind == LibraryKind.HomeVideos) {
                assertEquals(FolderRoute(library.id.toString(), library.name), route)
            } else {
                assertEquals(library.id.toString(), (route as LibraryRoute).libraryId)
            }
        }
    }

    private fun focusedText(): String = compose.onAllNodes(isFocused()).fetchSemanticsNodes().single().let { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            ?: node.children.firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
            ?: ""
    }

    private fun press(code: Int) {
        listOf(ACTION_DOWN, ACTION_UP).forEach { action ->
            compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0, 0, action, code, 0)))
        }
        compose.waitForIdle()
    }

    private companion object {
        const val MOVIES = 14
        const val SHOWS = 6
        const val MUSIC = 2
    }

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }
}
