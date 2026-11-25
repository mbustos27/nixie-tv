// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import io.github.glacier_jellyfin.androidtv.core.data.media.HomeContent
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** Nixie: every row Home builds from its own lists is kept by the one keep-focus hook. */
class NixieRowsTest {

    private fun item(n: Long) = MediaItem(
        id = UUID(4, n),
        kind = ItemKind.Episode,
        title = "e$n",
        sortName = null,
        year = null,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = null,
        genres = emptyList(),
        overview = null,
        parentTitle = null,
        seasonNumber = null,
        episodeNumber = null,
        progress = null,
        remainingMinutes = null,
        unwatchedCount = null,
        isFavorite = false,
        played = false,
        childCount = null,
        quality = null,
        posterUrl = null,
        thumbUrl = null,
        backdropUrl = null,
    )

    @Test
    fun `Continue Watching and Up next both pass their cards' keys to the keep-focus hook`() {
        val content = HomeContent(
            libraries = emptyList(),
            continueWatching = listOf(item(1), item(2)),
            upNext = listOf(item(3)),
            latest = emptyList(),
        )
        assertEquals(
            mapOf(
                NixieRow.ContinueWatching to listOf("continue-${UUID(4, 1)}", "continue-${UUID(4, 2)}"),
                NixieRow.UpNext to listOf("nixie-up-next-${UUID(4, 3)}"),
            ),
            nixieRows(content),
        )
    }
}
