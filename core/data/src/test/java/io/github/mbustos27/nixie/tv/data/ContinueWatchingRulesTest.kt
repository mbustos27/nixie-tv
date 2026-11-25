// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import java.time.LocalDateTime
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Test

/** Nixie: Continue Watching and Up next (desktop Nixie 1.2.0, 1.4.1, 1.5.0). */
class ContinueWatchingRulesTest {

    private val now = LocalDateTime.of(2026, 11, 21, 12, 0)

    private fun id(n: Int) = UUID(0, n.toLong())

    private fun data(
        n: Int,
        position: Long = 0,
        played: Boolean = false,
        daysAgo: Long? = null,
    ) = UserItemDataDto(
        playbackPositionTicks = position,
        playCount = 0,
        isFavorite = false,
        played = played,
        key = "k$n",
        itemId = id(n),
        lastPlayedDate = daysAgo?.let { now.minusDays(it) },
    )

    private fun movie(
        n: Int,
        position: Long = 1_000,
        played: Boolean = false,
        daysAgo: Long? = 1,
    ) = BaseItemDto(
        id = id(n),
        type = BaseItemKind.MOVIE,
        name = "movie$n",
        userData = data(n, position, played, daysAgo),
    )

    private fun episode(
        n: Int,
        series: Int,
        position: Long = 0,
        played: Boolean = false,
        daysAgo: Long? = 1,
    ) = BaseItemDto(
        id = id(n),
        type = BaseItemKind.EPISODE,
        name = "ep$n",
        seriesId = id(1000 + series),
        userData = data(n, position, played, daysAgo),
    )

    private fun rows(
        resume: List<BaseItemDto>,
        nextUp: List<BaseItemDto> = emptyList(),
        settings: NixieHomeSettings = NixieHomeSettings(),
    ) = ContinueWatchingRules.rows(resume, nextUp, settings, now)

    @Test
    fun `Continue Watching and Up next are two rows`() {
        val rows = rows(
            resume = listOf(movie(1), episode(2, series = 1, position = 500)),
            nextUp = listOf(episode(3, series = 2)),
        )
        assertEquals(listOf("movie1", "ep2"), rows.continueWatching.map { it.name })
        assertEquals(listOf("ep3"), rows.upNext.map { it.name })
    }

    @Test
    fun `a show in Continue Watching is not in Up next too`() {
        val rows = rows(
            resume = listOf(episode(2, series = 1, position = 500)),
            nextUp = listOf(episode(5, series = 1), episode(2, series = 1), episode(7, series = 3)),
        )
        assertEquals(listOf("ep2"), rows.continueWatching.map { it.name })
        assertEquals(listOf("ep7"), rows.upNext.map { it.name })
    }

    @Test
    fun `each show once in Up next, the server's first`() {
        val rows = rows(resume = emptyList(), nextUp = listOf(episode(3, series = 2), episode(4, series = 2)))
        assertEquals(listOf("ep3"), rows.upNext.map { it.name })
    }

    @Test
    fun `stale items leave Continue Watching after the days picked`() {
        val resume = listOf(
            movie(1, daysAgo = 10),
            movie(2, daysAgo = 45),
            movie(3, daysAgo = 100),
            movie(4, daysAgo = null),
        )
        assertEquals(4, rows(resume).continueWatching.size)
        assertEquals(
            listOf("movie1", "movie4"),
            rows(resume, settings = NixieHomeSettings(hideStaleDays = 30)).continueWatching.map { it.name },
        )
        assertEquals(
            listOf("movie1", "movie2", "movie4"),
            rows(resume, settings = NixieHomeSettings(hideStaleDays = 90)).continueWatching.map { it.name },
        )
    }

    @Test
    fun `a watched item with no resume point is left out, one watched again stays while rewatching is on`() {
        val resume = listOf(movie(1, played = true, position = 0), movie(2, played = true, position = 900), movie(3))
        assertEquals(listOf("movie2", "movie3"), rows(resume).continueWatching.map { it.name })
        assertEquals(
            listOf("movie3"),
            rows(resume, settings = NixieHomeSettings(rewatching = false)).continueWatching.map { it.name },
        )
    }

    @Test
    fun `Next Up looks back 365 days`() {
        assertEquals(now.minusDays(365), ContinueWatchingRules.nextUpCutoff(now))
    }
}
