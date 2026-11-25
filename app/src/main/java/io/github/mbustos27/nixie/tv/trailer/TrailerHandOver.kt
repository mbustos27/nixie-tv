// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.trailer

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import java.util.UUID

/**
 * TV-S13: the trailer's share of the server taken off its path. After the press, the trailer screen asked the server for
 * the title's details (its trailers among them) before YouTube could start: a fraction of a second in a box's log, and
 * every request there can take seconds when the server is busy. The movie or show page has those details already: it
 * hands them over as it shows them, and the trailer screen takes them for that title instead of asking ([take]). So a
 * YouTube trailer asks the server nothing between the press and its first frame. In memory only: nothing goes to
 * YouTube or anywhere else before the press (the video's id still goes to YouTube only then, see [TrailerWarmup]).
 */
object TrailerHandOver {
    private class Offer(val details: ItemDetails, val at: Long)

    @Volatile
    private var offer: Offer? = null

    /** The page shows [details] (again, after a change): the trailer screen may take them. */
    fun offer(details: ItemDetails, now: Long = SystemClock.elapsedRealtime()) {
        offer = Offer(details, now)
    }

    /** The details the page showed for [id], if it showed them less than [FRESH_MS] before [now]; null otherwise. */
    fun take(id: UUID, now: Long = SystemClock.elapsedRealtime()): ItemDetails? =
        offer?.takeIf { it.details.item.id == id && now - it.at in 0..FRESH_MS }?.details

    /** Forgets the offer (the tests). */
    fun clear() {
        offer = null
    }

    /** Details older than this are asked for again (the page refreshes its own after playback). */
    const val FRESH_MS = 30 * 60_000L
}

/** Nixie (TV-S13) on a movie or show page: the details it shows, handed over for its Trailer button. */
@Composable
fun NixieTrailerHandOver(details: ItemDetails) {
    LaunchedEffect(details) { TrailerHandOver.offer(details) }
}
