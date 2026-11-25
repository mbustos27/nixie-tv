// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import android.app.Application
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.ProfileLock
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferencesRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsSync
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateChannel
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateManager
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateState
import io.github.glacier_jellyfin.androidtv.core.updater.WhatsNew
import io.github.glacier_jellyfin.androidtv.settings.SettingsViewModel
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * tv-1.0-privacy, the re-review: the names of servers and profiles removed earlier are kept only so the shared log's
 * older lines lose them. Clear log in Settings forgets them too (clearing the app's data takes them with the accounts).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClearLogTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `Clear log forgets the names kept for it`() {
        Log.install(RuntimeEnvironment.getApplication())
        val accounts = mockk<AccountRepository>(relaxed = true) { every { state } returns emptyFlow() }
        val model = SettingsViewModel(
            mockk<SessionManager>(relaxed = true) { every { session } returns MutableStateFlow(null) },
            accounts,
            mockk<SettingsRepository>(relaxed = true) {
                every { settings } returns MutableStateFlow(ProfileSettings())
            },
            mockk<ServerPreferencesRepository>(relaxed = true) {
                every { preferences } returns MutableStateFlow(null)
            },
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk<ParentalControl>(relaxed = true) {
                every { lock } returns MutableStateFlow(ProfileLock())
                every { settingsUnlocked } returns MutableStateFlow(false)
            },
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk<SettingsSync>(relaxed = true) { every { copies } returns MutableStateFlow(emptyList()) },
            mockk<UpdateManager>(relaxed = true) {
                every { state } returns MutableStateFlow(UpdateState.Unchecked)
                every { channel } returns MutableStateFlow(UpdateChannel.Stable)
                every { autoCheck } returns MutableStateFlow(true)
                every { showPrompt } returns MutableStateFlow(true)
            },
            mockk<WhatsNew>(relaxed = true) { every { enabled } returns MutableStateFlow(true) },
            mockk(relaxed = true),
        )

        model.clearLog()

        coVerify(timeout = 5_000) { accounts.nixieForgetNamesSeen() }
    }
}
