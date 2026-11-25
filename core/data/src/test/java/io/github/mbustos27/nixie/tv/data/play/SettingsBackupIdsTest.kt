// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import io.github.glacier_jellyfin.androidtv.core.data.settings.CLIENT
import io.github.glacier_jellyfin.androidtv.core.data.settings.PREFERENCES_ID
import org.junit.Assert.assertEquals
import org.junit.Test

/** The settings copies on the server are Nixie's own: a Glacier install on the same account never restores them. */
class SettingsBackupIdsTest {

    @Test
    fun `the copies live under Nixie's item and client, not Glacier's`() {
        assertEquals("nixie-tv-settings", PREFERENCES_ID)
        assertEquals("nixie-tv", CLIENT)
    }
}
