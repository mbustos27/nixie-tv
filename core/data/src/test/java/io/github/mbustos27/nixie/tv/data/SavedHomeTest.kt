// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeContent
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tv-home-start: Home's saved page comes back for its profile, and goes with a profile removed from the device (or
 * with its server), only once that removal is seen (DESIGN-RULES 4).
 */
class SavedHomeTest {

    private val folder = Files.createTempDirectory("saved-home").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val server = StoredServer("s1", "Home", "http://home.invalid", "12.4.0", lastUsedAt = 0)
    private val anna = StoredUser("s1", "a1", "anna", null, "token-a", lastUsedAt = 0)
    private val ben = StoredUser("s1", "b2", "ben", null, "token-b", lastUsedAt = 0)

    @After
    fun tearDown() {
        scope.cancel()
        folder.deleteRecursively()
    }

    private fun page(title: String) = SavedHomePage(
        HomeContent(libraries = emptyList(), continueWatching = listOf(item(title)), latest = emptyList()),
        liveTv = false,
    )

    private val movies = Library(UUID.nameUUIDFromBytes("m".toByteArray()), "Movies", LibraryKind.Movies, null, null)

    @Test
    fun `saved Home has its two rows and the top bar's libraries, and nothing else`() {
        val accounts = MutableStateFlow(AccountState(servers = listOf(server), users = listOf(anna)))
        val shown = HomeContent(
            libraries = listOf(movies),
            continueWatching = listOf(item("A Quiet Harbor")),
            upNext = listOf(item("The Lantern")),
            // What Home showed before: none of it is kept.
            latest = listOf(movies to listOf(item("Salt Flats"))),
            favorites = listOf(item("Desert Lights")),
            latestLibraries = listOf(movies),
        )
        saved(accounts).write(SavedHome.key(anna), SavedHomePage(shown, liveTv = true))

        // A new process: the file read.
        val page = saved(accounts).read(SavedHome.key(anna))!!
        assertEquals(listOf("A Quiet Harbor"), page.content.continueWatching.map { it.title })
        assertEquals(listOf("The Lantern"), page.content.upNext.map { it.title })
        assertEquals(listOf(movies), page.content.libraries)
        assertEquals(true, page.liveTv)
        assertEquals(emptyList<Any>(), page.content.latest + page.content.favorites + page.content.latestLibraries)
        val file = folder.listFiles()!!.single().readText()
        listOf("Salt Flats", "Desert Lights", "latest", "favorites", "spotlight").forEach {
            assertFalse("$it is not written", it in file)
        }
    }

    @Test
    fun `a page saved before the two rows still opens Home with them`() {
        val accounts = MutableStateFlow(AccountState(servers = listOf(server), users = listOf(anna)))
        val key = SavedHome.key(anna)
        saved(accounts).write(key, page("A Quiet Harbor"))
        val file = folder.listFiles()!!.single()
        val (first, json) = file.readText().split('\n', limit = 2).let { it[0] to it[1] }
        // Version 1 also kept "New in", favourites, albums and the hero.
        val hero = """{"id":"${UUID.randomUUID()}","kind":"Movie","title":"Hero"}"""
        val old = json.replaceFirst(
            "{",
            """{"version":1,"latestLibraries":["x"],"latest":[{"library":"x","items":[]}],""" +
                """"favorites":[],"recentAlbums":[],"spotlight":[$hero],""",
        )
        file.writeText(first + "\n" + old)

        val page = saved(accounts).read(key)!!
        assertEquals(listOf("A Quiet Harbor"), page.content.continueWatching.map { it.title })
        assertTrue(page.content.latest.isEmpty())
    }

    private fun item(title: String) = MediaItem(
        id = UUID.nameUUIDFromBytes(title.toByteArray()),
        kind = ItemKind.Movie,
        title = title,
        sortName = null,
        year = 2026,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = 90,
        genres = listOf("Drama"),
        overview = null,
        parentTitle = null,
        seasonNumber = null,
        episodeNumber = null,
        progress = 0.4f,
        remainingMinutes = 50,
        unwatchedCount = null,
        isFavorite = false,
        played = false,
        childCount = null,
        quality = null,
        posterUrl = "http://home.invalid/p",
        thumbUrl = null,
        backdropUrl = null,
    )

    private fun saved(accounts: MutableStateFlow<AccountState>) = SavedHome(folder, accounts, scope)

    @Test
    fun `a page comes back from the device for its profile and its age limits only`() {
        val accounts = MutableStateFlow(AccountState(servers = listOf(server), users = listOf(anna)))
        saved(accounts).write(SavedHome.key(anna), page("A Quiet Harbor"))

        // A new process: nothing in memory, the file read.
        val next = saved(accounts)
        assertEquals(
            listOf("A Quiet Harbor"),
            next.read(SavedHome.key(anna))?.content?.continueWatching?.map { it.title },
        )
        assertEquals(item("A Quiet Harbor"), next.read(SavedHome.key(anna))?.content?.continueWatching?.single())
        val stricter = anna.copy(protection = anna.protection.copy(blockUnrated = true))
        assertNull("saved under other age limits", next.read(SavedHome.key(stricter)))
    }

    @Test
    fun `a profile removed from the device takes its page along, and only once the removal is seen`() {
        // Ben is not in the first state read: nothing is decided on that (his page stays).
        val accounts = MutableStateFlow(AccountState(servers = listOf(server), users = listOf(anna)))
        val home = saved(accounts)
        home.write(SavedHome.key(anna), page("A Quiet Harbor"))
        home.write(SavedHome.key(ben), page("Salt Flats"))
        assertNotNull(home.read(SavedHome.key(ben)))

        accounts.value = accounts.value.copy(users = listOf(anna, ben))
        // Ben is removed from the device.
        accounts.value = accounts.value.copy(users = listOf(anna))

        assertNull("Ben's page went, from memory and the device", home.read(SavedHome.key(ben)))
        assertNull(saved(MutableStateFlow(accounts.value)).read(SavedHome.key(ben)))
        assertNotNull("Anna's stays", home.read(SavedHome.key(anna)))

        // Anna's server is removed: her page goes too.
        accounts.value = AccountState()
        assertNull(saved(MutableStateFlow(accounts.value)).read(SavedHome.key(anna)))
    }
}
