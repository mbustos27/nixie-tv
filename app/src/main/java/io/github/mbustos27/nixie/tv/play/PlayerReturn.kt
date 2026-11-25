// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import android.os.SystemClock
import io.github.glacier_jellyfin.androidtv.channels.HomeLaunch
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.util.Locale
import java.util.UUID

/**
 * TV-S12: the way back to a paused title loads it once. The owner paused, pressed Home and came back through the
 * launcher's Continue watching, and the title loaded three times. That card is a new intent ([HomeLaunch]), and the app
 * answered it by opening Home from scratch: the player screen, which had just opened the title again by itself when the
 * app came back (TV-S8), was dropped; Home was drawn and asked for its rows, took the card and opened a second player
 * screen, which asked the server and prepared a player once more.
 *
 * Now the player screen says which title it holds ([attach]); a card that plays that title, in the profile signed in,
 * opens nothing ([keeps]): the screen brings the title back by itself, once, paused where it was.
 *
 * The way back is timed for the detailed log (design rule 10): from the return to the first frame, with the opens asked
 * of the server and the players prepared on the way, whichever player screen made them. Durations and counts only.
 */
class PlayerReturn(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = { Log.d(TAG, it) },
) {
    /** The player screen open now: the title it would bring back, or null while it closes or shows a failure. */
    fun interface Holder {
        fun holding(): UUID?
    }

    @Volatile
    private var holder: Holder? = null

    /** When the app came back last, while no first frame has followed; null otherwise. */
    @Volatile
    private var backAt: Long? = null
    private var asked = 0
    private var prepared = 0

    /** A player screen opened: from now on it is the one a card can bring back. */
    fun attach(holder: Holder) {
        this.holder = holder
    }

    /** That screen is gone; a newer one that attached meanwhile stays. */
    fun detach(holder: Holder) {
        if (this.holder === holder) this.holder = null
    }

    /**
     * Whether [launch], a card on the launcher, asks to play the title the player screen holds, in the profile of
     * [session]: then nothing else opens, and the screen brings the title back.
     */
    fun keeps(launch: HomeLaunch, session: Session?): Boolean {
        if (!launch.play || session == null) return false
        if (session.server.id != launch.serverId || session.user.userId != launch.userId) return false
        val held = holder?.holding() ?: return false
        if (held.toString() != launch.itemId) return false
        Log.i(TAG, "The card is for the title the player holds: it comes back there, nothing else opens")
        return true
    }

    /**
     * The app came back in front and the player screen opens its title again. What was counted since an earlier
     * return without a first frame (the app sent away again on the way) stays counted.
     */
    fun back() {
        if (backAt == null) {
            asked = 0
            prepared = 0
        }
        backAt = clock()
    }

    /** A player screen asks the server to open a title (its PlaybackInfo). */
    fun asked() {
        if (backAt != null) asked++
    }

    /** A player screen made a player and prepared it on the title's stream. */
    fun prepared() {
        if (backAt != null) prepared++
    }

    /** A first frame: a way back under way is over, and its line goes to the log. */
    fun firstFrame() {
        val at = backAt ?: return
        backAt = null
        log(
            "Way back to the player: first frame ${seconds(clock() - at)}, " +
                "${count(asked, "open")} asked of the server, ${count(prepared, "player")} prepared",
        )
    }

    /** The player screen closed: no way back is under way any more. */
    fun dropped() {
        backAt = null
    }

    /** Tests: as when the app starts. */
    internal fun forget() {
        holder = null
        backAt = null
    }

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.2f s", ms / 1000.0)

    private fun count(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"

    companion object {
        const val TAG = "WayBack"

        /** The app's: the player screens and the launcher's cards meet here. */
        val app = PlayerReturn()
    }
}
