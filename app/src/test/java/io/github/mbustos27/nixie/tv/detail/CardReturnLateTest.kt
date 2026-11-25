// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.detail

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import java.time.Duration
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * tv-fix-b (the review of the merged TV fixes): Back's return to the card left. A card that came late (the page loaded
 * again after the app was in the background) took the focus after the user had moved on, and any OK was remembered,
 * one that opened nothing too. A page whose cards come [loadMs] after it is drawn, under its Play and Trailer.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class CardReturnLateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController

    /** How long the page's cards take to come, each time it is composed. */
    private var loadMs = 0L

    /** The cards whose OK opens the next page. */
    private var opening = setOf(CARD_A, CARD_B)

    @Test
    fun `a key pressed before the card comes back keeps the focus where it moved`() {
        show()
        openFrom(CARD_B)
        loadMs = LATE_MS

        back()
        idle(ARRIVED_MS) // the page in, its own first focus on Play; its cards not yet
        compose.onNodeWithText(PLAY).assertIsFocused()
        press(Key.DirectionDown)
        compose.onNodeWithText(TRAILER).assertIsFocused()
        idle(LATE_MS)

        compose.onNodeWithText(CARD_B).assertIsNotFocused()
        compose.onNodeWithText(TRAILER).assertIsFocused()
    }

    @Test
    fun `a card that comes back after two seconds does not take the focus`() {
        show()
        openFrom(CARD_B)
        loadMs = RETURN_MS + LATE_MS

        back()
        idle(RETURN_MS + 2 * LATE_MS)

        compose.onNodeWithText(CARD_B).assertIsNotFocused()
        compose.onNodeWithText(PLAY).assertIsFocused()
    }

    @Test
    fun `Back returns to the card whose OK opened the next page`() {
        show()
        openFrom(CARD_B)

        back()

        compose.onNodeWithText(CARD_B).assertIsFocused()
    }

    @Test
    fun `an OK that opens nothing is not where Back returns`() {
        opening = setOf(CARD_B)
        show()
        compose.onNodeWithText(CARD_A).requestFocus()
        press(Key.DirectionCenter)
        assertEquals("OK on a card that opens nothing", PAGE, nav.currentDestination?.route)
        idle(OPENS_MS + LATE_MS)
        // The page left another way (the app's own, no key): Back comes back to the page's own first focus.
        compose.runOnIdle { nav.navigate(NEXT) }
        idle(TRANSITION_MS)

        back()

        compose.onNodeWithText(CARD_A).assertIsNotFocused()
        compose.onNodeWithText(PLAY).assertIsFocused()
    }

    private fun openFrom(card: String) {
        compose.onNodeWithText(card).requestFocus()
        press(Key.DirectionCenter)
        assertEquals("OK on $card opened the next page", NEXT, nav.currentDestination?.route)
        // The next page fully in: the page left goes from the composition, and Back composes it anew.
        idle(TRANSITION_MS)
    }

    @Composable
    private fun LatePage() {
        val memory = rememberCardReturn()
        var ready by remember { mutableStateOf(loadMs == 0L) }
        val play = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { play.requestFocus() } // the page's own first focus
            if (!ready) {
                delay(loadMs)
                ready = true
            }
        }
        Column(Modifier.cardReturnPage(memory)) {
            Text(PLAY, Modifier.focusRequester(play).focusable())
            Text(TRAILER, Modifier.focusable())
            if (ready) {
                Row {
                    for (card in listOf(CARD_A, CARD_B)) {
                        Text(
                            card,
                            Modifier.cardReturn(memory, card).clickable { if (card in opening) nav.navigate(NEXT) },
                        )
                    }
                }
            }
        }
    }

    private fun show() {
        // The clock goes only as the test says: the page's late cards and the return's wait are timed against it.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            nav = rememberNavController()
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme {
                    NavHost(nav, startDestination = PAGE) {
                        composable(PAGE) { LatePage() }
                        composable(NEXT) { Text("Next page", Modifier.focusable()) }
                    }
                }
            }
        }
        settle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    /** The remote's Back, through the activity as the system sends it. */
    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun settle() = idle(SETTLE_MS)

    /** [ms] go by: the composition's clock (its effects' delays) and the main looper's. */
    private fun idle(ms: Long) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ms)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }

    private companion object {
        const val PAGE = "page"
        const val NEXT = "next"
        const val PLAY = "Play"
        const val TRAILER = "Trailer"
        const val CARD_A = "Card A"
        const val CARD_B = "Card B"
        const val SETTLE_MS = 200L

        /** Late, but within the time a return waits: the key, not the clock, drops it. */
        const val LATE_MS = 1_500L

        /** The page back in after Back, before its late cards. */
        const val ARRIVED_MS = 700L

        /** Longer than the pages' cross-fade. */
        const val TRANSITION_MS = 1_000L
    }
}
