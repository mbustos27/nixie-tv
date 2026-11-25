// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import io.github.glacier_jellyfin.androidtv.core.data.playback.UpNextMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodeRuleTest {

    @Test
    fun `the server's autoplay setting decides whether the next episode starts by itself`() {
        assertTrue(NextEpisodeRule.playsByItself(true, UpNextMode.WithCredits))
        assertFalse(NextEpisodeRule.playsByItself(false, UpNextMode.WithCredits))
        assertFalse(NextEpisodeRule.playsByItself(false, UpNextMode.Before(30_000)))
        // Not known yet: Jellyfin's own default, on.
        assertTrue(NextEpisodeRule.playsByItself(null, UpNextMode.WithCredits))
        // The local Off still means no next episode.
        assertFalse(NextEpisodeRule.playsByItself(true, UpNextMode.Off))
    }
}
