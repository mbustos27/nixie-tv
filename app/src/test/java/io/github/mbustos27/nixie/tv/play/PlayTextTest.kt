// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import android.app.Application
import io.github.glacier_jellyfin.androidtv.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What the play stream's settings say must match what the code does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayTextTest {

    @Test
    fun `the online lookup setting says exactly what each request sends`() {
        // IntroOnline.introDbQuery and aniSkipUrl: the show's public id, the season, the episode and the runtime.
        val text = RuntimeEnvironment.getApplication()
            .getString(R.string.nixie_settings_lookup_online_sub)
        assertEquals(
            "When the server and the file's chapters have none. Sends only the show's public id, the season and " +
                "episode number, and the episode's length to TheIntroDB, and for anime to AniSkip (ARM gets only the " +
                "show's id, to find it there)",
            text,
        )
    }
}
