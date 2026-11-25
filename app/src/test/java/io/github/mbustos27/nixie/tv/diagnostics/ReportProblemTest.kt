// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import android.app.Application
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.glacier_jellyfin.androidtv.BuildConfig
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.ProfileLock
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferencesRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsSync
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateChannel
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateManager
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateState
import io.github.glacier_jellyfin.androidtv.core.updater.WhatsNew
import io.github.glacier_jellyfin.androidtv.settings.SettingsViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.io.File
import java.net.URLDecoder
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * tv-1.0-r1 (decided 2026-11-22 for TV 1.0): Settings › System › Diagnostics › Report a problem shows a code that opens
 * the public repository's Report a problem form with the app's and the server's versions filled in, and nothing else of
 * the TV, the server or its user; the TV sends nothing. The log goes with it through Download log on the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class ReportProblemTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `the code opens the public form with the app's and the server's versions filled in`() {
        assertEquals(
            "https://github.com/mbustos27/nixie-tv/issues/new?template=bug.yml&version=1.0.0&jellyfin=12.4.0",
            NixieReportProblem.url("1.0.0", "12.4.0"),
        )
        // A "+" in a version is a "+" in the form, not a space.
        val url = NixieReportProblem.url("1.0.1-beta.2+7", "10.11.0")
        assertEquals("1.0.1-beta.2+7", fields(url)["version"])
        assertEquals("10.11.0", fields(url)["jellyfin"])
    }

    @Test
    fun `only version numbers go into the public form's address`() {
        // The server's version not known yet: the form's field stays empty.
        val appOnly = fields(NixieReportProblem.url("1.0.0", null))
        assertEquals(mapOf("template" to "bug.yml", "version" to "1.0.0"), appOnly)
        // Anything that is not a version number never goes in: an address, a name, a token.
        listOf(
            "http://192.168.1.20:8096",
            "Ana's server",
            "12.4.0 on nas.example.org",
            "3f2a9c0d4e5b6a7c8d9e0f1a2b3c4d5e",
            "192.168.1.20",
            "",
            " ",
        ).forEach { text ->
            assertNull(text, NixieReportProblem.version(text))
            assertEquals(text, mapOf("template" to "bug.yml"), fields(NixieReportProblem.url(text, text)))
        }
    }

    @Test
    fun `the fields it fills in are the Report a problem form's`() {
        val form = repoFile(".github/ISSUE_TEMPLATE/${NixieReportProblem.TEMPLATE}").readText()
        assertTrue(form.lineSequence().first().startsWith("name: Report a problem"))
        listOf(NixieReportProblem.APP_FIELD, NixieReportProblem.SERVER_FIELD).forEach { id ->
            assertTrue("bug.yml has no field \"$id\"", form.lines().any { it.trim() == "id: $id" })
        }
        val repository = NixieReportProblem.TYPED.substringBefore("/issues")
        assertTrue(NixieReportProblem.FORM.startsWith("https://$repository/"))
    }

    @Test
    fun `Settings opens it with this build's version and the server's as Settings shows it`() {
        val session = mockk<Session>(relaxed = true)
        every { session.server } returns StoredServer("s1", "Living room", "http://192.168.1.20:8096", "12.4.0", 0)
        every { session.api } throws IllegalStateException("no network in this test")
        val sessions = mockk<SessionManager>(relaxed = true)
        every { sessions.session } returns MutableStateFlow(session)
        // What the view model follows from the start, each at its quiet value.
        val accounts = mockk<AccountRepository>(relaxed = true)
        every { accounts.state } returns emptyFlow()
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.settings } returns MutableStateFlow(ProfileSettings())
        val server = mockk<ServerPreferencesRepository>(relaxed = true)
        every { server.preferences } returns MutableStateFlow(null)
        val parental = mockk<ParentalControl>(relaxed = true)
        every { parental.lock } returns MutableStateFlow(ProfileLock())
        every { parental.settingsUnlocked } returns MutableStateFlow(false)
        val sync = mockk<SettingsSync>(relaxed = true)
        every { sync.copies } returns MutableStateFlow(emptyList())
        val updates = mockk<UpdateManager>(relaxed = true)
        every { updates.state } returns MutableStateFlow(UpdateState.Unchecked)
        every { updates.channel } returns MutableStateFlow(UpdateChannel.Stable)
        every { updates.autoCheck } returns MutableStateFlow(true)
        every { updates.showPrompt } returns MutableStateFlow(true)
        val whatsNew = mockk<WhatsNew>(relaxed = true)
        every { whatsNew.enabled } returns MutableStateFlow(true)
        val model = SettingsViewModel(
            sessions,
            accounts,
            settings,
            server,
            mockk(relaxed = true),
            mockk(relaxed = true),
            parental,
            mockk(relaxed = true),
            mockk(relaxed = true),
            sync,
            updates,
            whatsNew,
            mockk(relaxed = true),
        )
        compose.waitForIdle()
        model.showReport()
        val report = model.state.value.diagnostics.report
        assertEquals(NixieReportProblem.version(BuildConfig.VERSION_NAME), report?.appVersion)
        assertEquals("12.4.0", report?.serverVersion)
        assertEquals(NixieReportProblem.url(BuildConfig.VERSION_NAME, "12.4.0"), report?.url)
        assertFalse(report!!.url.contains("192.168") || report.url.contains("Living"))
        model.closeReport()
        assertNull(model.state.value.diagnostics.report)
    }

    @Test
    fun `the page shows the code and both versions, and Close closes it`() {
        var closed = false
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme {
                    NixieReportScreen(NixieReportProblem.report("1.0.0", null), onClose = { closed = true })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(NixieReportTags.CODE).assertExists()
        compose.onNodeWithText("1.0.0").assertExists()
        compose.onNodeWithText("Not known").assertExists()
        compose.onNodeWithText(NixieReportProblem.TYPED).assertExists()
        compose.onNodeWithText("Close").assertIsFocused().performClick()
        assertTrue(closed)
    }

    @Test
    fun `Download log's last step leads to Report a problem, in every language`() {
        val app = RuntimeEnvironment.getApplication()
        for (locale in listOf(Locale.ENGLISH, Locale.GERMAN, Locale("es"))) {
            val resources = app.createConfigurationContext(Configuration().apply { setLocale(locale) }).resources
            val step = resources.getString(R.string.diag_share_step_attach)
            assertTrue("$locale: $step", step.contains(resources.getString(R.string.nixie_report)))
        }
    }

    /** The query of [url], decoded, by name. */
    private fun fields(url: String): Map<String, String> = url.substringAfter('?').split('&').associate {
        val (name, value) = it.split('=', limit = 2)
        name to URLDecoder.decode(value, "UTF-8")
    }

    private fun repoFile(path: String): File {
        val here = File("").absoluteFile
        val root = if (File(here, "settings.gradle.kts").isFile) here else here.parentFile
        return File(root, path)
    }

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }
}
