// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.media.HomeContent
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nixie: a `UserDataChanged` patches Home's cards in place. */
class LivePatchTest {

    private fun item(
        n: Long,
        progress: Float? = null,
        favorite: Boolean = false,
    ) = MediaItem(
        id = UUID(2, n),
        kind = ItemKind.Movie,
        title = "movie$n",
        sortName = null,
        year = null,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = 100,
        runtimeMs = 6_000_000,
        genres = emptyList(),
        overview = null,
        parentTitle = null,
        seasonNumber = null,
        episodeNumber = null,
        progress = progress,
        remainingMinutes = null,
        unwatchedCount = null,
        isFavorite = favorite,
        played = false,
        childCount = null,
        quality = null,
        posterUrl = null,
        thumbUrl = null,
        backdropUrl = null,
    )

    private fun change(
        n: Long,
        positionTicks: Long = 0,
        percent: Double? = null,
        played: Boolean = false,
        favorite: Boolean = false,
    ) = UserDataChange(UUID(2, n), played, positionTicks, percent, favorite, null, null)

    private val library = Library(UUID(3, 1), "Movies", LibraryKind.Movies, null, null)

    private fun library(): Library = library

    private fun home(
        continueWatching: List<MediaItem> = emptyList(),
        upNext: List<MediaItem> = emptyList(),
        latest: List<MediaItem> = emptyList(),
        favorites: List<MediaItem> = emptyList(),
    ) = HomeContent(
        libraries = emptyList(),
        continueWatching = continueWatching,
        upNext = upNext,
        latest = listOf(library() to latest),
        favorites = favorites,
    )

    @Test
    fun `progress moves the card's bar in every row, without a reload`() {
        val patch = home(continueWatching = listOf(item(1, progress = 0.1f)), latest = listOf(item(1, progress = 0.1f)))
            .withUserData(change(1, positionTicks = 30_000_000_000, percent = 50.0))
        assertEquals(0.5f, patch.content.continueWatching.single().progress)
        assertEquals(0.5f, patch.content.latest.single().second.single().progress)
        assertEquals(50, patch.content.continueWatching.single().remainingMinutes)
        assertFalse(patch.reload)
    }

    @Test
    fun `watched elsewhere, a card leaves Continue watching and the rows reload for what comes next`() {
        val patch = home(continueWatching = listOf(item(1, progress = 0.9f), item(2, progress = 0.2f)))
            .withUserData(change(1, played = true))
        assertEquals(listOf("movie2"), patch.content.continueWatching.map { it.title })
        assertTrue(patch.reload)
    }

    @Test
    fun `a favourite taken back leaves the favourites row, one made elsewhere reloads`() {
        val content = home(favorites = listOf(item(1, favorite = true)), latest = listOf(item(2)))
        assertEquals(emptyList<MediaItem>(), content.withUserData(change(1, favorite = false)).content.favorites)
        val made = content.withUserData(change(2, favorite = true))
        assertTrue(made.content.latest.single().second.single().isFavorite)
        assertTrue(made.reload)
    }

    @Test
    fun `started elsewhere, the rows reload to bring it into Continue watching`() {
        val patch = home(latest = listOf(item(1))).withUserData(change(1, positionTicks = 10, percent = 1.0))
        assertTrue(patch.reload)
    }

    @Test
    fun `a change to an item Home does not show changes nothing`() {
        val content = home(continueWatching = listOf(item(1, progress = 0.1f)))
        val patch = content.withUserData(change(9))
        assertEquals(content, patch.content)
        assertFalse(patch.reload)
    }
}
