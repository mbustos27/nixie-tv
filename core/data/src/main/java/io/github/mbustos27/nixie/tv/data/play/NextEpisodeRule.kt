// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import io.github.glacier_jellyfin.androidtv.core.data.playback.UpNextMode

/** Desktop Nixie's rule for the next episode (1.2.0). */
object NextEpisodeRule {

    /**
     * Whether the next episode starts by itself when this one ends. The user's own Jellyfin setting
     * (`EnableNextEpisodeAutoPlay`, the same in every client; true when not known) decides, over the local "Up next"
     * default. The local setting switched to Off still means no card and no next episode.
     */
    fun playsByItself(serverAutoPlay: Boolean?, local: UpNextMode): Boolean =
        local != UpNextMode.Off && serverAutoPlay != false
}
