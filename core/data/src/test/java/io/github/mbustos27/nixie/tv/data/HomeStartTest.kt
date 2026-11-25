// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.media.HomeContent
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tv-home-start (TV-S6), tv-home-two-rows (TV-S11): Home is Continue watching, then Up next. It draws once Continue
 * watching is in, Up next fills in on its own, one slow or failed row never holds the other, and the top bar's
 * libraries are asked only once both rows are in. Nothing else is asked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeStartTest {

    private val movies = Library(
        UUID.nameUUIDFromBytes("movies".toByteArray()),
        "Movies",
        LibraryKind.Movies,
        null,
        null,
    )

    /** Each part answers when its gate opens: [answer] it, [fail] it, or leave it out forever. */
    private inner class Parts : HomeParts {
        val resumeGate = CompletableDeferred<List<BaseItemDto>>()
        val nextUpGate = CompletableDeferred<List<BaseItemDto>>()
        val viewsGate = CompletableDeferred<HomeViews>()
        val calls = mutableListOf<String>()

        override suspend fun views(): HomeViews {
            calls += "views"
            return viewsGate.await()
        }

        override suspend fun resume(): List<BaseItemDto> {
            calls += "resume"
            return resumeGate.await()
        }

        override suspend fun nextUp(): List<BaseItemDto> {
            calls += "next up"
            return nextUpGate.await()
        }

        override suspend fun items(dtos: List<BaseItemDto>) = dtos.map { item(it.name!!, it.id, it.type) }
    }

    private fun dto(name: String, type: BaseItemKind = BaseItemKind.MOVIE) = BaseItemDto(
        id = UUID.nameUUIDFromBytes(name.toByteArray()),
        type = type,
        name = name,
        seriesId = UUID.nameUUIDFromBytes("show $name".toByteArray()).takeIf { type == BaseItemKind.EPISODE },
    )

    private fun item(
        name: String,
        id: UUID = UUID.nameUUIDFromBytes(name.toByteArray()),
        type: BaseItemKind = BaseItemKind.MOVIE,
    ) = MediaItem(
        id = id,
        kind = if (type == BaseItemKind.EPISODE) ItemKind.Episode else ItemKind.Movie,
        title = name,
        sortName = null,
        year = 2026,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = 90,
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

    private fun rows(parts: Parts, previous: HomeContent? = null) =
        HomeStart.rows(parts, previous, NixieHomeSettings(), LocalDateTime.of(2026, 11, 22, 8, 0), clock = { 0L })

    @Test
    fun `Home asks for Continue watching and Up next, then the top bar's libraries, and nothing else`() = runTest {
        val parts = Parts()
        val seen = mutableListOf<HomeProgress>()
        val job = launch { rows(parts).collect { seen += it } }
        runCurrent()
        assertEquals("the two rows at once, nothing else", setOf("resume", "next up"), parts.calls.toSet())

        parts.resumeGate.complete(listOf(dto("A Quiet Harbor")))
        runCurrent()
        assertTrue("no libraries while a row is out", "views" !in parts.calls)

        parts.nextUpGate.complete(listOf(dto("The Lantern", BaseItemKind.EPISODE)))
        runCurrent()
        assertEquals(listOf("views"), parts.calls.drop(2))
        assertTrue(seen.last().settled)
        assertTrue("the libraries do not count as a row", seen.last().content.libraries.isEmpty())

        parts.viewsGate.complete(HomeViews(listOf(movies), liveTv = true))
        runCurrent()
        assertEquals(listOf(movies), seen.last().content.libraries)
        assertEquals(3, parts.calls.size)
        job.cancel()
    }

    @Test
    fun `the top bar's Home back to a Home asks for its two rows only`() = runTest {
        val parts = Parts()
        val seen = mutableListOf<HomeProgress>()
        val job = launch {
            HomeStart.rows(
                parts,
                HomeContent(libraries = listOf(movies), continueWatching = listOf(item("Shown")), latest = emptyList()),
                NixieHomeSettings(),
                LocalDateTime.of(2026, 11, 22, 8, 0),
                clock = { 0L },
                topBar = false,
            ).collect { seen += it }
        }
        parts.resumeGate.complete(listOf(dto("A Quiet Harbor")))
        parts.nextUpGate.complete(emptyList())
        runCurrent()

        assertEquals(listOf("resume", "next up"), parts.calls.sorted().reversed())
        assertTrue(seen.last().settled)
        assertEquals("the top bar keeps what it had", listOf(movies), seen.last().content.libraries)
        job.cancel()
    }

    @Test
    fun `the rows are Continue watching, then Up next, and nothing else`() = runTest {
        val parts = Parts()
        val seen = mutableListOf<HomeProgress>()
        val job = launch { rows(parts).collect { seen += it } }
        parts.resumeGate.complete(listOf(dto("A Quiet Harbor")))
        parts.nextUpGate.complete(listOf(dto("The Lantern", BaseItemKind.EPISODE)))
        parts.viewsGate.complete(HomeViews(listOf(movies), listOf(movies)))
        runCurrent()

        val content = seen.last().content
        assertEquals(listOf("A Quiet Harbor"), content.continueWatching.map { it.title })
        assertEquals(listOf("The Lantern"), content.upNext.map { it.title })
        assertTrue(
            "no other row",
            content.latest.isEmpty() && content.favorites.isEmpty() && content.recentAlbums.isEmpty() &&
                content.favoriteSongs.isEmpty() && content.liveChannels.isEmpty() && content.latestLibraries.isEmpty(),
        )
        job.cancel()
    }

    @Test
    fun `Home draws with Continue watching while Up next is still out`() = runTest {
        val parts = Parts()
        val seen = mutableListOf<HomeProgress>()
        val job = launch { rows(parts).collect { seen += it } }
        runCurrent()
        assertTrue("nothing is drawn before a row is in", seen.none { it.drawable })

        parts.resumeGate.complete(listOf(dto("A Quiet Harbor")))
        runCurrent()

        val first = seen.first { it.drawable }
        assertEquals(listOf("A Quiet Harbor"), first.content.continueWatching.map { it.title })
        assertTrue(first.continueWatchingIn)
        assertFalse("Up next is not known yet: no empty line for it", first.upNextIn)
        assertFalse(first.settled)
        job.cancel()
    }

    @Test
    fun `with Continue watching empty, Home draws once Up next answers or after the grace`() = runTest {
        val parts = Parts()
        val seen = mutableListOf<HomeProgress>()
        val job = launch { rows(parts).collect { seen += it } }
        parts.resumeGate.complete(emptyList())
        runCurrent()
        assertTrue("an empty first row waits a moment for the second", seen.none { it.drawable })
        parts.nextUpGate.complete(listOf(dto("The Lantern", BaseItemKind.EPISODE)))
        runCurrent()
        assertTrue(seen.last().drawable)
        job.cancel()

        val slow = Parts()
        val later = mutableListOf<HomeProgress>()
        val second = launch { rows(slow).collect { later += it } }
        slow.resumeGate.complete(emptyList())
        runCurrent()
        assertTrue(later.none { it.drawable })
        advanceTimeBy(HomeStart.FIRST_ROW_GRACE_MS + 1)
        runCurrent()
        assertTrue("Up next never answers: Home draws anyway, saying Continue watching is empty", later.last().drawable)
        assertFalse(later.last().upNextIn)
        second.cancel()
    }

    @Test
    fun `a failed row leaves the other, and Home fails only when both did`() = runTest {
        val parts = Parts()
        val seen = mutableListOf<HomeProgress>()
        val job = launch { rows(parts).collect { seen += it } }
        parts.nextUpGate.completeExceptionally(IllegalStateException("timeout"))
        parts.resumeGate.complete(listOf(dto("A Quiet Harbor")))
        runCurrent()
        assertTrue(seen.last().settled && seen.last().drawable)
        assertFalse(seen.last().failed)
        job.cancel()

        val down = Parts()
        val failed = mutableListOf<HomeProgress>()
        val second = launch { rows(down).collect { failed += it } }
        listOf(down.resumeGate, down.nextUpGate).forEach { it.completeExceptionally(IllegalStateException("down")) }
        down.viewsGate.completeExceptionally(IllegalStateException("down"))
        runCurrent()
        assertTrue(failed.last().failed)
        second.cancel()
    }

    @Test
    fun `over what Home shows, a row keeps its cards until its own answer, and the rest of an old page goes`() =
        runTest {
            val parts = Parts()
            // A page saved before this stream: "New in" and favourites besides the two rows.
            val previous = HomeContent(
                libraries = listOf(movies),
                continueWatching = listOf(item("Saved")),
                upNext = listOf(item("Saved next")),
                latest = listOf(movies to listOf(item("Saved new"))),
                favorites = listOf(item("Saved favorite")),
                latestLibraries = listOf(movies),
            )
            val seen = mutableListOf<HomeProgress>()
            val job = launch { rows(parts, previous).collect { seen += it } }
            parts.resumeGate.complete(listOf(dto("Fresh")))
            runCurrent()
            val now = seen.last().content
            assertEquals(listOf("Fresh"), now.continueWatching.map { it.title })
            assertEquals(listOf("Saved next"), now.upNext.map { it.title })
            assertEquals(listOf(movies), now.libraries)
            assertTrue(now.latest.isEmpty() && now.favorites.isEmpty())

            parts.nextUpGate.completeExceptionally(IllegalStateException("500"))
            runCurrent()
            val kept = seen.last().content.upNext.map { it.title }
            assertEquals("kept when its request fails", listOf("Saved next"), kept)
            job.cancel()
        }

    @Test
    fun `the draw rule`() {
        assertFalse(HomeStart.drawable(continueWatchingIn = false, anyRow = true, graceOver = false, settled = false))
        assertTrue(HomeStart.drawable(continueWatchingIn = true, anyRow = true, graceOver = false, settled = false))
        assertFalse(HomeStart.drawable(continueWatchingIn = true, anyRow = false, graceOver = false, settled = false))
        assertTrue(HomeStart.drawable(continueWatchingIn = true, anyRow = false, graceOver = true, settled = false))
        assertTrue(HomeStart.drawable(continueWatchingIn = false, anyRow = true, graceOver = true, settled = false))
        assertFalse(HomeStart.drawable(continueWatchingIn = false, anyRow = false, graceOver = true, settled = false))
        assertTrue(HomeStart.drawable(continueWatchingIn = false, anyRow = false, graceOver = false, settled = true))
    }
}
