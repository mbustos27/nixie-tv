// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv

import android.app.Application
import io.github.glacier_jellyfin.androidtv.AppModule
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateManager
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Nixie: a debug build's updater never asks GitHub by itself (UPDATE_CHECK_AT_START, AppModule's
 * `autoCheckDefault`), so the emulator, the basics gate and the box's test builds never meet the update
 * dialog. The release checks at every start from 1.0: app/build.gradle.kts sets it for the release build
 * type only, and staging stays off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NixieUpdaterDefaultTest {

    @Test
    fun `a debug build's check at start is off until the user turns it on`() {
        // A release version, so the updater would otherwise check.
        val config = AppModule.provideUpdaterConfig().copy(versionName = "0.1.0")
        assertFalse(config.autoCheckDefault)
        val updater = UpdateManager(RuntimeEnvironment.getApplication(), config)
        assertFalse(updater.autoCheck.value)
        updater.start()
        assertEquals(UpdateState.Unchecked, updater.state.value)
    }
}
