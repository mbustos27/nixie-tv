// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** Nixie: up to three starred libraries for the top bar, in the server's order. */
class TopBarLibrariesTest {

    private fun library(n: Long, name: String) = Library(UUID(7, n), name, LibraryKind.Movies, null, null)

    private val server = listOf(library(1, "Movies"), library(2, "Marvel"), library(3, "Kids"), library(4, "Anime"))

    @Test
    fun `the starred libraries in the server's order, at most three, gone ones left out`() {
        val ids = listOf(UUID(7, 4), UUID(7, 2), UUID(9, 9), UUID(7, 1), UUID(7, 3)).map { it.toString() }
        assertEquals(listOf("Movies", "Marvel", "Kids"), TopBarLibraries.favourites(server, ids).map { it.name })
        assertEquals(emptyList<Library>(), TopBarLibraries.favourites(server, emptyList()))
    }

    @Test
    fun `a fourth star is refused, a star taken off frees a place`() {
        val three = listOf("a", "b", "c")
        assertEquals(three, TopBarLibraries.starred(three, "d", star = true))
        assertEquals(listOf("a", "c"), TopBarLibraries.starred(three, "b", star = false))
        assertEquals(listOf("a", "c", "d"), TopBarLibraries.starred(listOf("a", "c"), "d", star = true))
        assertEquals(listOf("a"), TopBarLibraries.starred(listOf("a"), "a", star = true))
    }
}
