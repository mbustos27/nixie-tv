package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryTest {

    @Test
    fun `genres ignore a descending sort`() {
        assertFalse(LibraryQuery(LibraryKind.Movies, scope = LibraryScope.Genres, descending = true).orderDescending)
    }

    @Test
    fun `other scopes keep the chosen order`() {
        assertTrue(LibraryQuery(LibraryKind.Movies, scope = LibraryScope.All, descending = true).orderDescending)
        assertFalse(LibraryQuery(LibraryKind.Movies, scope = LibraryScope.All, descending = false).orderDescending)
        assertTrue(LibraryQuery(LibraryKind.Music, scope = LibraryScope.Albums, descending = true).orderDescending)
        assertTrue(LibraryQuery(LibraryKind.Music, scope = LibraryScope.Artists, descending = true).orderDescending)
    }

    @Test
    fun `music has a songs tab between artists and playlists`() {
        assertEquals(
            listOf(LibraryScope.Albums, LibraryScope.Artists, LibraryScope.Songs, LibraryScope.Playlists),
            LibraryQuery.scopes(LibraryKind.Music, inGenre = false),
        )
    }

    @Test
    fun `unwatched and favorites are filters, not tabs, and leave genres alone`() {
        assertEquals(
            listOf(LibraryScope.All, LibraryScope.Collections, LibraryScope.Genres),
            LibraryQuery.scopes(LibraryKind.Movies, inGenre = false),
        )
        assertEquals(
            listOf(LibraryScope.All, LibraryScope.Collections, LibraryScope.Genres),
            LibraryQuery.scopes(LibraryKind.Shows, inGenre = false),
        )
        assertTrue(LibraryQuery(LibraryKind.Movies).filterable)
        assertFalse(LibraryQuery(LibraryKind.Movies, scope = LibraryScope.Genres).filterable)
    }

    @Test
    fun `each tab offers only the sorts that fit it`() {
        assertEquals(
            listOf(LibrarySort.DateAdded, LibrarySort.Title),
            LibraryQuery(LibraryKind.Music, scope = LibraryScope.Artists).sorts,
        )
        assertTrue(LibrarySort.Album in LibraryQuery(LibraryKind.Music, scope = LibraryScope.Songs).sorts)
        assertFalse(LibrarySort.Artist in LibraryQuery(LibraryKind.Movies).sorts)
    }

    @Test
    fun `movies group into collections unless narrowed - unwatched, favorites and genres list the titles themselves`() {
        // Nixie (TV-S1): unwatched too: the server does not group with a played filter, and the app no longer lists
        // the whole library to group it itself.
        assertTrue(LibraryQuery(LibraryKind.Movies).groupsCollections)
        assertFalse(LibraryQuery(LibraryKind.Movies, unwatchedOnly = true).groupsCollections)
        assertFalse(LibraryQuery(LibraryKind.Movies, favoritesOnly = true).groupsCollections)
        assertFalse(LibraryQuery(LibraryKind.Movies, genreId = java.util.UUID.randomUUID()).groupsCollections)
        assertFalse(LibraryQuery(LibraryKind.Movies, groupCollections = false).groupsCollections)
        assertFalse(LibraryQuery(LibraryKind.Shows).groupsCollections)
    }

    @Test
    fun `only the collections tab of shows narrows to collections holding a show`() {
        assertTrue(LibraryQuery(LibraryKind.Shows, scope = LibraryScope.Collections).showCollections)
        assertFalse(LibraryQuery(LibraryKind.Movies, scope = LibraryScope.Collections).showCollections)
        assertFalse(LibraryQuery(LibraryKind.Shows).showCollections)
    }

    @Test
    fun `rail letters ignore case and accents`() {
        assertEquals('E', railLetter("étoile"))
        assertEquals('B', railLetter(" bend"))
        assertEquals('#', railLetter("0001 - 0003 - Bend"))
        assertEquals('#', railLetter(""))
    }

    @Test
    fun `music tabs keep their own sort, albums under the old key`() {
        assertEquals("Music", LibraryQuery(LibraryKind.Music, scope = LibraryScope.Albums).sortKey)
        assertEquals("Music.Artists", LibraryQuery(LibraryKind.Music, scope = LibraryScope.Artists).sortKey)
        assertEquals("Movies", LibraryQuery(LibraryKind.Movies, scope = LibraryScope.Genres).sortKey)
    }
}
