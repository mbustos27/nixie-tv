// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.media.HomeContent
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem

/**
 * Home's rows with one [UserDataChange] applied in place (desktop Nixie 1.2.0): the cards of the
 * item, in every row, take the new progress, watched mark and favourite. An item leaves Continue
 * watching once it has no resume point (watched, or reset elsewhere) and Up next once it is
 * watched; a favourite taken back leaves the favourites row. What cannot be patched in place,
 * such as a title new to a row, asks for a quiet reload ([LivePatch.reload]).
 */
data class LivePatch(val content: HomeContent, val reload: Boolean)

fun HomeContent.withUserData(change: UserDataChange): LivePatch {
    val id = change.itemId
    fun List<MediaItem>.patched() = map { if (it.id == id) it.withUserData(change) else it }
    val inContinue = continueWatching.any { it.id == id }
    val inUpNext = upNext.any { it.id == id }
    val inFavorites = favorites.any { it.id == id }
    val known = (continueWatching + upNext + latest.flatMap { it.second } + recentAlbums).firstOrNull { it.id == id }
    val content = copy(
        continueWatching = continueWatching.patched().filterNot { it.id == id && change.positionTicks <= 0 },
        upNext = upNext.patched().filterNot { it.id == id && change.played },
        latest = latest.map { (library, items) -> library to items.patched() },
        favorites = favorites.patched().filterNot { it.id == id && !change.isFavorite },
        recentAlbums = recentAlbums.patched(),
    )
    val newFavorite = !inFavorites && change.isFavorite && known?.isFavorite == false
    return LivePatch(content, needsReload(change, inContinue, inUpNext) || newFavorite)
}

/**
 * What cannot be patched in place: an item started or rewatched elsewhere belongs in Continue watching
 * (which needs the server's card), and one watched to the end moves Up next on to the next episode. A
 * favourite made elsewhere (the caller's part) needs its card in the favourites row.
 */
private fun needsReload(
    change: UserDataChange,
    inContinue: Boolean,
    inUpNext: Boolean,
): Boolean = when {
    !inContinue && change.positionTicks > 0 -> true
    !change.played -> false
    else -> inUpNext || (inContinue && change.positionTicks <= 0)
}

/** The cards of the hero, patched like the rows (they stay put: the hero is made on reloads). */
fun List<MediaItem>.withUserData(change: UserDataChange): List<MediaItem> =
    map { if (it.id == change.itemId) it.withUserData(change) else it }

private fun MediaItem.withUserData(change: UserDataChange): MediaItem {
    val positionMs = change.positionTicks / TICKS_PER_MS
    val started = change.positionTicks > 0
    return copy(
        played = change.played,
        isFavorite = change.isFavorite,
        progress = change.playedPercentage?.let { (it / 100).toFloat() }?.takeIf { started && it > 0f },
        resumePositionMs = positionMs,
        remainingMinutes = runtimeMs?.takeIf { started }?.let { ((it - positionMs) / MS_PER_MINUTE).toInt() },
        unwatchedCount = if (kind in UNWATCHED_COUNTED) {
            change.unplayedItemCount?.takeIf { it > 0 }
        } else {
            unwatchedCount
        },
    )
}

private const val TICKS_PER_MS = 10_000L
private const val MS_PER_MINUTE = 60_000L
private val UNWATCHED_COUNTED = setOf(ItemKind.Series, ItemKind.Season, ItemKind.Collection)
