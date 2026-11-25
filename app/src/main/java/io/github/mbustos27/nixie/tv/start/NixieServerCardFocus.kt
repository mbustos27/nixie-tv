// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.start

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Nixie (tv-gate-a, tv-fix-a): the server list's entries' focus. [of] gives each saved server card a requester (a
 * closed menu goes back to its card); [track] notes which entry took the focus last (cards by server id, the other
 * entries by their own key), so a finished removal moves the focus only when it was on the removed card.
 */
class NixieServerCardFocus {
    private val requesters = mutableMapOf<String, FocusRequester>()

    /** The entry that took the focus last; null before any. A dialog's buttons do not count. */
    var lastFocused: String? = null
        private set

    fun of(serverId: String): FocusRequester = requesters.getOrPut(serverId) { FocusRequester() }

    fun track(key: String): Modifier = Modifier.onFocusChanged { if (it.hasFocus) lastFocused = key }

    /** After the removal of [serverId]: true when the focus was on its card (or on no entry yet). */
    fun wasOn(serverId: String?): Boolean = lastFocused == null || lastFocused == serverId
}
