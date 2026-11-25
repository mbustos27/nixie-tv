// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayLabelsTest {

    private fun item(
        kind: ItemKind = ItemKind.Episode,
        season: Int? = 1,
        episode: Int? = 4,
        last: Int? = null,
        progress: Float? = null,
    ) = MediaItem(
        id = UUID.randomUUID(),
        kind = kind,
        title = "Title",
        sortName = null,
        year = null,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = 44,
        genres = emptyList(),
        overview = null,
        parentTitle = "Show",
        seasonNumber = season,
        episodeNumber = episode,
        progress = progress,
        remainingMinutes = null,
        unwatchedCount = null,
        isFavorite = false,
        played = false,
        childCount = null,
        quality = null,
        posterUrl = null,
        thumbUrl = null,
        backdropUrl = null,
        episodeNumberEnd = last,
    )

    @Test
    fun `an episode in progress resumes by its code, a new one plays by its code`() {
        assertEquals(PlayLabel.Resume(EpisodeCode(1, 4)), PlayLabels.main(item(progress = 0.4f)))
        assertEquals(PlayLabel.Play(EpisodeCode(2, 1)), PlayLabels.main(item(season = 2, episode = 1)))
        assertTrue(PlayLabels.fromStart(item(progress = 0.4f)))
        assertFalse(PlayLabels.fromStart(item()))
    }

    @Test
    fun `a file with several episodes names them all`() {
        val code = PlayLabels.code(item(episode = 4, last = 5))!!
        assertEquals(5, code.through)
        assertNull(PlayLabels.code(item(episode = 4, last = 4))!!.through)
    }

    @Test
    fun `films and episodes without numbers keep Glacier's label`() {
        assertNull(PlayLabels.main(item(kind = ItemKind.Movie, season = null, episode = null, progress = 0.3f)))
        assertNull(PlayLabels.main(item(episode = null)))
        assertNull(PlayLabels.main(null))
    }
}
