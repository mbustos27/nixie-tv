// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import java.time.LocalDateTime
import java.util.UUID
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * Nixie: the home stream's settings (Settings › Home), kept apart from Glacier's
 * [io.github.glacier_jellyfin.androidtv.core.data.settings.HomeSettings].
 */
@Serializable
data class NixieHomeSettings(
    /** Continue Watching leaves out what was not played for this many days; 0 keeps everything. */
    val hideStaleDays: Int = 0,
    /** Up next also lists shows being watched again from the start. */
    val rewatching: Boolean = true,
    /** The libraries in the server's order when Home last followed it (ServerOrder). */
    val serverOrder: List<String> = emptyList(),
    /** Libraries starred for the top bar (ids), at most [TopBarLibraries.MAX]; kept per server and profile. */
    val topBarLibraries: List<String> = emptyList(),
) {
    companion object {
        /** The choices Settings offers, in days (0: never). */
        val HideStaleChoices = listOf(0, 30, 60, 90)
    }
}

/**
 * Nixie: what Home's Continue Watching and Up next rows show, as desktop Nixie has them
 * (1.2.0, 1.4.1, 1.5.0). Continue Watching is the server's resume list, Up next its Next Up
 * list; each title shows once, in one of the two.
 */
object ContinueWatchingRules {

    /** Next Up looks this far back for shows being watched. */
    const val NEXT_UP_DAYS = 365L

    /** The oldest "last watched" date Next Up asks for, see [NEXT_UP_DAYS]. */
    fun nextUpCutoff(now: LocalDateTime): LocalDateTime = now.minusDays(NEXT_UP_DAYS)

    /** Not played for more than [days] days (0: nothing is). An item never played is not stale. */
    fun isStale(
        item: BaseItemDto,
        now: LocalDateTime,
        days: Int,
    ): Boolean {
        if (days <= 0) return false
        val last = item.userData?.lastPlayedDate ?: return false
        return last.isBefore(now.minusDays(days.toLong()))
    }

    /**
     * Marked watched and with no resume point: the server answered before a "mark watched" took,
     * say. With [rewatching], an item watched before and being watched again (Jellyfin 12 keeps
     * the watched mark while it plays again) has a resume point and stays; without it any item
     * marked watched goes.
     */
    fun isWatchedNotInProgress(item: BaseItemDto, rewatching: Boolean): Boolean {
        val data = item.userData ?: return false
        return data.played && (!rewatching || data.playbackPositionTicks <= 0)
    }

    /**
     * The two rows from the server's answers: Continue Watching is [resume] less stale and
     * watched items, one entry per show (the most recent, as the server orders them); Up next is
     * [nextUp] less anything in Continue Watching and less a second episode of one show.
     */
    fun rows(
        resume: List<BaseItemDto>,
        nextUp: List<BaseItemDto>,
        settings: NixieHomeSettings,
        now: LocalDateTime,
    ): HomeRows {
        val started = resume
            .filterNot { isStale(it, now, settings.hideStaleDays) || isWatchedNotInProgress(it, settings.rewatching) }
            .distinctBy { it.seriesId ?: it.id }
        val ids = started.mapTo(HashSet()) { it.id }
        val shows = started.mapNotNullTo(HashSet<UUID>()) { it.seriesId }
        val upNext = nextUp
            .filter { it.id !in ids && (it.seriesId == null || it.seriesId !in shows) }
            .distinctBy { it.seriesId ?: it.id }
        return HomeRows(started, upNext)
    }
}

/** Continue Watching and Up next, as [ContinueWatchingRules.rows] makes them. */
data class HomeRows(val continueWatching: List<BaseItemDto>, val upNext: List<BaseItemDto>)

/** Now, as the SDK reads the server's dates: in the device's time zone. */
fun localNow(): LocalDateTime = LocalDateTime.now()
