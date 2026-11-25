// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TV-S2: the library grid asks for artwork at its cards' size, and knows which titles come next. */
class NixieGridArtTest {

    private val poster = "http://tv.test/Items/abc/Images/Primary?tag=t1&maxWidth=480"

    @Test
    fun `a poster is asked at the card's width, rounded up to a step`() {
        assertEquals("http://tv.test/Items/abc/Images/Primary?tag=t1&maxWidth=240", NixieGridArt.sized(poster, 216))
        assertEquals("http://tv.test/Items/abc/Images/Primary?tag=t1&maxWidth=240", NixieGridArt.sized(poster, 188))
        assertEquals("http://tv.test/Items/abc/Images/Primary?tag=t1&maxWidth=360", NixieGridArt.sized(poster, 241))
    }

    @Test
    fun `never wider than asked, and left alone without a width`() {
        assertEquals(poster, NixieGridArt.sized(poster, 432 + 100))
        assertEquals(poster, NixieGridArt.sized(poster, 0))
        val plain = "http://tv.test/Items/abc/Images/Primary?tag=t1"
        assertEquals(plain, NixieGridArt.sized(plain, 216))
        assertEquals(null, NixieGridArt.sized(null, 216))
    }

    @Test
    fun `a poster is asked as the web client asks for it in a 1920-wide window, or at twice its pixels`() {
        val web = "http://tv.test/Items/abc/Images/Primary?tag=t1&fillHeight=320&fillWidth=213&quality=96&format=Webp"
        assertEquals(web, NixieGridArt.poster(poster, 216))
        assertEquals(web, NixieGridArt.poster(poster, 188))
        assertEquals(web, NixieGridArt.poster(poster, 234))
        val twice = "http://tv.test/Items/abc/Images/Primary?tag=t1&fillHeight=639&fillWidth=426&quality=96&format=Webp"
        assertEquals(twice, NixieGridArt.poster(poster, 235))
        assertEquals(twice, NixieGridArt.poster(poster, 432))
        // Wider than the web's pictures: at the card's width, as before.
        assertEquals(poster, NixieGridArt.poster(poster, 470))
        assertEquals(null, NixieGridArt.poster(null, 216))
    }

    @Test
    fun `films, shows and collections take the web's poster, the rest the card's width`() {
        val art = GridArtSizes(poster = 216, posterHeight = 324, wide = 300, wideHeight = 169)
        fun card(kind: ItemKind) = NixieGridArt.cardUrl(item(kind), art)
        listOf(ItemKind.Movie, ItemKind.Series, ItemKind.Collection).forEach {
            assertTrue(card(it)!!.contains("fillWidth=213"))
        }
        assertEquals("http://tv.test/Items/abc/Images/Primary?tag=t1&maxWidth=240", card(ItemKind.Album))
        assertEquals("http://tv.test/Items/abc/Images/Primary?tag=w1&maxWidth=360", card(ItemKind.MusicVideo))
    }

    private fun item(kind: ItemKind) = MediaItem(
        id = UUID.nameUUIDFromBytes("abc".toByteArray()),
        kind = kind,
        title = "A",
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
        posterUrl = poster,
        thumbUrl = "http://tv.test/Items/abc/Images/Primary?tag=w1&maxWidth=720",
        backdropUrl = null,
    )

    @Test
    fun `the titles ahead are the rows after the last one on screen`() {
        // Two header cells, then titles; a heading before title 7 (grid index 9 is the heading).
        val positions = IntArray(30) { if (it < 7) 2 + it else 3 + it }
        // Grid index 16 is title 13 on screen: the next three rows of seven come after it.
        assertEquals(14..29, NixieGridArt.ahead(16, positions, columns = 7, rows = 3))
        assertEquals(0..6, NixieGridArt.ahead(1, positions, columns = 7, rows = 1))
        assertEquals(IntRange.EMPTY, NixieGridArt.ahead(-1, positions, columns = 7, rows = 3))
        assertEquals(IntRange.EMPTY, NixieGridArt.ahead(40, positions, columns = 7, rows = 3))
    }
}
