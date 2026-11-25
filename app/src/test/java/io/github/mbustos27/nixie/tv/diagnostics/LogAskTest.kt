// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import android.app.Application
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.lifecycle.SavedStateHandle
import io.github.glacier_jellyfin.androidtv.StartViewModel
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.TrackSelections
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferences
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferencesRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.diagnostics.Diagnostics
import io.github.glacier_jellyfin.androidtv.diagnostics.LogServer
import io.github.glacier_jellyfin.androidtv.player.PlaybackFailureCard
import io.github.glacier_jellyfin.androidtv.player.PlayerUiState
import io.github.glacier_jellyfin.androidtv.player.PlayerViewModel
import io.github.glacier_jellyfin.androidtv.player.VersionChooser
import io.github.mbustos27.nixie.tv.data.skips.LearnedSkipStore
import io.github.mbustos27.nixie.tv.data.skips.SkipChain
import io.github.mbustos27.nixie.tv.data.skips.SkipLearner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Integration 1 (the owner: "Fix all noticed issues"): after a failed playback or a crash, the log was offered on the
 * local network by itself. Now the app asks first, in one plain line with Yes and No, and nothing is served until Yes:
 * the real PlayerViewModel (a title whose opening fails) and StartViewModel (a crash noted at the last run), with
 * Diagnostics faked to count the offers; and the two questions on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class LogAskTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scheduler = TestCoroutineScheduler()
    private val diagnostics = mockk<Diagnostics>(relaxed = true)
    private val servers = mutableListOf<LogServer>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        coEvery { diagnostics.shareOnNetwork() } answers {
            LogServer("127.0.0.1", "nixie-log.txt", "log".encodeToByteArray()).also { servers += it }
        }
    }

    @After
    fun tearDown() {
        servers.forEach { it.close() }
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `a failed playback asks first and offers the log only after Yes`() {
        val player = failedPlayer()
        assertTrue(player.state.value.failed)
        assertTrue("the question shows", player.state.value.logAsk)
        assertNull(player.state.value.logShare)
        coVerify(exactly = 0) { diagnostics.shareOnNetwork() }

        player.answerLog(true)
        run()
        assertFalse(player.state.value.logAsk)
        assertNotNull("the log is offered after Yes", player.state.value.logShare)
        coVerify(exactly = 1) { diagnostics.shareOnNetwork() }
    }

    @Test
    fun `No after a failed playback offers nothing`() {
        val player = failedPlayer()
        player.answerLog(false)
        run()
        assertFalse(player.state.value.logAsk)
        assertNull(player.state.value.logShare)
        assertFalse(player.state.value.logOffline)
        coVerify(exactly = 0) { diagnostics.shareOnNetwork() }
    }

    @Test
    fun `after a crash the app asks first and offers the log only after Yes`() {
        val start = crashedStart()
        assertEquals(CRASHED_AT, start.crashAsk.value)
        assertNull(start.crashShare.value)
        coVerify(exactly = 0) { diagnostics.shareOnNetwork() }

        start.answerCrashLog(true)
        run { start.crashShare.value != null }
        assertEquals(CRASHED_AT, start.crashShare.value?.crashedAt)
        assertNull("the question is gone", start.crashAsk.value)
        coVerify(exactly = 1) { diagnostics.shareOnNetwork() }
    }

    @Test
    fun `No after a crash offers nothing and the first screen opens`() {
        val start = crashedStart()
        start.answerCrashLog(false)
        run()
        assertNull(start.crashAsk.value)
        assertNull(start.crashShare.value)
        coVerify(exactly = 0) { diagnostics.shareOnNetwork() }
    }

    @Test
    fun `the failure card asks in one line with Yes and No`() {
        val answers = mutableListOf<Boolean>()
        compose.setContent {
            GlacierTheme {
                PlaybackFailureCard(
                    PlayerUiState(failed = true, logAsk = true),
                    onRetry = {},
                    onBack = {},
                    onLogAnswer = { answers += it },
                )
            }
        }
        compose.onNodeWithTag(NixieLogQuestion.NO).performClick()
        compose.onNodeWithTag(NixieLogQuestion.YES).performClick()
        assertEquals(listOf(false, true), answers)
    }

    @Test
    fun `the crash question starts on No, so a stray OK offers nothing`() {
        val answers = mutableListOf<Boolean>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme { NixieCrashLogQuestion(CRASHED_AT) { answers += it } }
            }
        }
        settle()
        compose.onNodeWithTag(NixieLogQuestion.NO).assertIsFocused()
        listOf(ACTION_DOWN, ACTION_UP).forEach { action ->
            compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0, 0, action, KEYCODE_DPAD_CENTER, 0)))
            settle()
        }
        assertEquals(listOf(false), answers)
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
    }

    /** Runs the main dispatcher until [done], letting work on other threads (Dispatchers.IO) come back. */
    private fun run(done: () -> Boolean = { false }) {
        repeat(RUN_ROUNDS) {
            scheduler.advanceUntilIdle()
            if (done()) return
            Thread.sleep(RUN_PAUSE_MS)
        }
    }

    /** The player on a title whose opening fails on the server. */
    private fun failedPlayer(): PlayerViewModel {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.settings } returns MutableStateFlow(ProfileSettings())
        coEvery { settings.current() } returns ProfileSettings()
        val serverPreferences = mockk<ServerPreferencesRepository>(relaxed = true)
        every { serverPreferences.preferences } returns MutableStateFlow(ServerPreferences())
        val details = mockk<DetailRepository>(relaxed = true)
        val playback = mockk<PlaybackRepository>(relaxed = true)
        coEvery { playback.open(any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("no")
        val skips = mockk<SkipChain>(relaxed = true)
        every { skips.learner() } returns SkipLearner(LearnedSkipStore(null))
        val versions = mockk<VersionChooser>()
        coEvery { versions.choose(any()) } returns null
        val model = PlayerViewModel(
            SavedStateHandle(mapOf("itemId" to UUID.randomUUID().toString(), "fromStart" to false)),
            RuntimeEnvironment.getApplication(),
            details,
            playback,
            TrackSelections(),
            settings,
            serverPreferences,
            mockk(relaxed = true),
            versions,
            diagnostics,
            skips,
        )
        run { model.state.value.failed }
        return model
    }

    /** The app's start after a crash noted at the last run. */
    private fun crashedStart(): StartViewModel {
        mockkObject(Log)
        every { Log.takeCrashNotice() } returns true
        every { Log.lastCrash() } returns CRASHED_AT
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.settings } returns MutableStateFlow(ProfileSettings())
        val sessions = mockk<SessionManager>(relaxed = true)
        every { sessions.session } returns MutableStateFlow(null)
        val accounts = mockk<AccountRepository>(relaxed = true)
        coEvery { accounts.current() } returns AccountState()
        val start = StartViewModel(
            accounts = accounts,
            settings = settings,
            parental = mockk(relaxed = true),
            updates = mockk(relaxed = true),
            whatsNew = mockk(relaxed = true),
            music = mockk(relaxed = true),
            home = mockk(relaxed = true),
            sessions = sessions,
            browseMemory = mockk(relaxed = true),
            launches = mockk(relaxed = true),
            diagnostics = diagnostics,
            settingsSync = mockk(relaxed = true),
            nixieCheck = mockk(relaxed = true),
            nixieStartLine = mockk(relaxed = true),
            nixieStart = mockk(relaxed = true),
        )
        run { start.crashAsk.value != null }
        return start
    }

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }

    private companion object {
        const val CRASHED_AT = 1_763_800_000_000L
        const val SETTLE_MS = 500L
        const val RUN_ROUNDS = 100
        const val RUN_PAUSE_MS = 10L
    }
}
