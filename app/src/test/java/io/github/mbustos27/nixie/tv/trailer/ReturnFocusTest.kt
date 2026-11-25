// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.trailer

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.ActionButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TV-S3: Back from the trailer lands on the Trailer button again, whether the page was composed anew on the way back
 * (from its saved state) or stayed composed under the trailer. A page with Play (the first focus) and Trailer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class ReturnFocusTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var trailerShows by mutableStateOf(false)

    /** The page has a Trailer button (its title has trailers). */
    private var trailerButton by mutableStateOf(true)

    @androidx.compose.runtime.Composable
    private fun Page() {
        val play = remember { FocusRequester() }
        val trailer = rememberReturnFocus()
        Row {
            ActionButton(onClick = {}, label = "Play", modifier = Modifier.testTag("play").focusRequester(play))
            if (trailerButton) {
                ActionButton(
                    onClick = {
                        trailer.opened()
                        trailerShows = true
                    },
                    label = "Trailer",
                    modifier = Modifier.testTag("trailer").focusRequester(trailer.requester),
                )
            }
        }
        // As the detail page: the first focus only the first time, never again on the way back.
        var initialFocusDone by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!initialFocusDone) initialFocusDone = runCatching { play.requestFocus() }.isSuccess
        }
    }

    /** The trailer over the page: it takes the focus while it shows. */
    @androidx.compose.runtime.Composable
    private fun Trailer() {
        val focus = remember { FocusRequester() }
        Box(Modifier.fillMaxSize().testTag("player").focusRequester(focus).focusable()) { Text("Trailer") }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    @Test
    fun `the page composed anew on the way back focuses the Trailer button`() {
        val pages = StateRestorationTester(compose)
        pages.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme { Page() }
            }
        }
        settle()
        compose.onNodeWithTag("play").assertIsFocused()
        compose.onNodeWithTag("trailer").performClick()
        settle()
        // Back: the page is composed again from its saved state, and resumes.
        pages.emulateSavedInstanceStateRestore()
        settle()
        compose.onNodeWithTag("trailer").assertIsFocused()
    }

    @Test
    fun `the page that stayed composed focuses the Trailer button when it resumes`() {
        val owner = TestOwner()
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode, LocalLifecycleOwner provides owner) {
                GlacierTheme {
                    Box {
                        Page()
                        if (trailerShows) Trailer()
                    }
                }
            }
        }
        settle()
        compose.onNodeWithTag("trailer").performClick()
        settle()
        // The trailer shows and has the focus; the page under it is paused.
        owner.registry.currentState = Lifecycle.State.STARTED
        settle()
        compose.onNodeWithTag("player").assertIsFocused()
        // Back: the trailer goes and the page resumes.
        trailerShows = false
        owner.registry.currentState = Lifecycle.State.RESUMED
        settle()
        compose.onNodeWithTag("trailer").assertIsFocused()
    }

    @Test
    fun `after the trailer hands over to the movie, Back from the movie does not land on Trailer`() {
        // Review: "Play movie" in the trailer swaps in the player; Back from the player landed on Trailer.
        val pages = StateRestorationTester(compose)
        pages.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme { Page() }
            }
        }
        settle()
        compose.onNodeWithTag("trailer").performClick()
        settle()
        ReturnFocus.handedOver()
        // Back from the movie: the page is composed again from its saved state, and resumes.
        pages.emulateSavedInstanceStateRestore()
        settle()
        compose.onNodeWithTag("trailer").assertIsNotFocused()
    }

    @Test
    fun `a Trailer button that is not back in time never takes the focus later`() {
        val owner = TestOwner()
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode, LocalLifecycleOwner provides owner) {
                GlacierTheme {
                    Box {
                        Page()
                        if (trailerShows) Trailer()
                    }
                }
            }
        }
        settle()
        compose.onNodeWithTag("trailer").performClick()
        settle()
        owner.registry.currentState = Lifecycle.State.STARTED
        settle()
        // Back to a page without its Trailer button (the trailers gone): nothing to focus within the wait.
        trailerShows = false
        trailerButton = false
        owner.registry.currentState = Lifecycle.State.RESUMED
        settle()
        settle()
        // Later the button is back and the page resumes again: the old mark must not move the focus.
        trailerButton = true
        settle()
        owner.registry.currentState = Lifecycle.State.STARTED
        settle()
        owner.registry.currentState = Lifecycle.State.RESUMED
        settle()
        compose.onNodeWithTag("trailer").assertIsNotFocused()
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }
}
