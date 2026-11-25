// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.search

import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankingTest {

    private fun item(
        title: String,
        kind: ItemKind = ItemKind.Movie,
        sortName: String? = null,
    ) = MediaItem(
        id = UUID.nameUUIDFromBytes("$kind:$title".toByteArray()),
        kind = kind,
        title = title,
        sortName = sortName,
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

    private fun episode(
        title: String,
        show: String,
        season: Int,
        number: Int,
    ) = item("$show s${season}e$number $title", ItemKind.Episode).copy(
        title = title,
        parentTitle = show,
        seriesId = UUID.nameUUIDFromBytes("Series:$show".toByteArray()),
        seasonNumber = season,
        episodeNumber = number,
    )

    @Test
    fun `Bri, shows and films that start with it come before episodes, and a show's many episodes fold`() {
        // Bug hunt M4: the server's answer had 20 and more episodes called "Bridge" before any show or film.
        val found = (1..6).map { episode("Bridge", "Route 66", 1, it) } +
            (1..4).map { episode("The Bridge", "Harbour Lights", 2, it) } +
            (1..3).map { episode("Brilliant", "Bright Ashford", 1, it) } +
            listOf(item("Bridge of Avalon"), item("Bright Ashford", ItemKind.Series), item("The Bright Circus"))
        val ranked = SearchRanking.rank("Bri", found)
        val shown = ranked.visible(chip = null, expanded = false)
        // The shows and films first (best match first, a leading "The" set aside), then one episode of each show.
        assertEquals(
            listOf(
                "The Bright Circus" to null,
                "Bright Ashford" to null,
                "Bridge of Avalon" to null,
                "Bridge" to "Route 66",
                "The Bridge" to "Harbour Lights",
            ),
            shown.map { it.title to it.parentTitle },
        )
        // The rest of each show's episodes, and all of Bright Ashford's (the show itself leads), wait behind
        // "Show 11 more".
        assertEquals(11, ranked.hidden(chip = null, expanded = false))
        // "Show N more" keeps what showed in place and adds the rest after it.
        val all = ranked.visible(chip = null, expanded = true)
        assertEquals(shown, all.take(shown.size))
        assertEquals(found.size, all.size)
        // The Episodes chip: one episode of each show that does not lead, the others behind "Show N more".
        val episodes = ranked.visible(SearchKind.Episodes, expanded = false)
        assertEquals(listOf("Route 66", "Harbour Lights"), episodes.map { it.parentTitle })
    }

    @Test
    fun `accents, case, apostrophes and punctuation count as equal`() {
        assertEquals("amelie", SearchText.fold("Amélie"))
        assertEquals("its always sunny", SearchText.fold("It's Always Sunny!"))
        assertEquals("wall e", SearchText.fold("WALL·E"))
        assertEquals("mash", SearchText.fold("M*A*S*H").replace(" ", ""))
        assertEquals("ae ss o", SearchText.fold("Æ ß Ø"))
        assertEquals(SearchTier.Whole, SearchQuery("amelie").match("Amélie").tier)
        assertEquals(SearchTier.Whole, SearchQuery("walle").match("WALL·E").tier)
        assertEquals(SearchTier.Whole, SearchQuery("matrix").match("The Matrix").tier)
        assertEquals(SearchTier.AllWords, SearchQuery("spiderman").match("The Amazing Spider-Man").tier)
        assertEquals(SearchTier.AllWords, SearchQuery("amelie").match("Le Fabuleux Destin d'Amélie Poulain").tier)
    }

    @Test
    fun `the whole title beats its start, which beats all the words, which beats some`() {
        val q = SearchQuery("star")
        val whole = q.match("Star")
        val startWord = q.match("Star Wars")
        val startInside = q.match("Starman")
        val words = SearchQuery("night dead").match("Night of the Living Dead")
        val some = SearchQuery("night shift").match("Night at the Museum")
        assertEquals(SearchTier.Whole, whole.tier)
        assertEquals(SearchTier.Start, startWord.tier)
        assertTrue(startWord < startInside)
        assertEquals(SearchTier.AllWords, words.tier)
        assertEquals(SearchTier.SomeWords, some.tier)
        assertEquals(SearchTier.None, q.match("Moon").tier)
        // The server's padded sort name counts as its title does.
        assertEquals(SearchTier.Whole, SearchQuery("se7en").match("Seven", "se0000000007en").tier)
    }

    @Test
    fun `best match first, whatever the server's order and kind`() {
        val found = listOf(
            item("Matrix Reloaded Making Of", ItemKind.Episode),
            item("The Animatrix"),
            item("The Matrix"),
            item("The Matrix Collection", ItemKind.Collection),
        )
        val ranked = SearchRanking.rank("the matrix", found)
        assertEquals(
            listOf("The Matrix", "The Matrix Collection", "Matrix Reloaded Making Of"),
            ranked.visible(chip = null, expanded = false).map { it.title },
        )
        // "Animatrix" holds the query inside a word: weak, behind "Show 1 more".
        assertEquals(1, ranked.hidden(chip = null, expanded = false))
        assertEquals("The Animatrix", ranked.visible(null, expanded = true).last().title)
        assertEquals(0, ranked.hidden(null, expanded = true))
    }

    @Test
    fun `type chips show one kind, in a fixed order`() {
        val found = listOf(
            item("Sunny Day", ItemKind.Episode),
            item("It's Always Sunny in Philadelphia", ItemKind.Series),
            item("Sunny", ItemKind.Movie),
            item("Sunny Pawar", ItemKind.Person),
        )
        val ranked = SearchRanking.rank("sunny", found)
        assertEquals(listOf(SearchKind.Shows, SearchKind.Movies, SearchKind.Episodes, SearchKind.People), ranked.chips)
        assertEquals(listOf("Sunny"), ranked.visible(SearchKind.Movies, expanded = false).map { it.title })
        assertEquals(listOf("Sunny Pawar"), ranked.visible(SearchKind.People, expanded = false).map { it.title })
    }

    @Test
    fun `titles found through a person or genre wait behind Show N more, and all show when nothing is strong`() {
        val hanks = item("Tom Hanks", ItemKind.Person)
        val film = item("Cast Away")
        val ranked = SearchRanking.rank("tom hanks", listOf(hanks, film), weak = setOf(film.id))
        assertEquals(listOf(hanks), ranked.visible(null, expanded = false))
        assertEquals(1, ranked.hidden(null, expanded = false))
        val nothingStrong = SearchRanking.rank("zzz", listOf(film), weak = setOf(film.id))
        assertEquals(listOf(film), nothingStrong.visible(null, expanded = false))
        assertEquals(0, nothingStrong.hidden(null, expanded = false))
        // Seerr's library hits join as weak matches, once.
        val withExtra = ranked.withExtra(listOf(film, item("Big")))
        assertEquals(2, withExtra.hidden(null, expanded = false))
    }
}
