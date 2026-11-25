// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.cinematic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Where the shots go (-Pnixie.shots=<folder>, or the module's build/shots/cinematic). */
internal fun shotFile(name: String): File =
    File(System.getProperty("nixie.shots") ?: "build/shots/cinematic").apply { mkdirs() }.resolve(name)

/**
 * Cinematic's slots on stand-ins for sign-in, a page without a picture and Home, at 1080p on the JVM with Roborazzi.
 * The app's ThemeShotsTest draws Glacier's real screens in every theme with no artwork; these add what it cannot
 * show yet: the hero over a bright full-bleed backdrop and over a pure white one, the filmstrip waiting for its code,
 * and the stage under a page. Each records an image; focus must land where a TV puts it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-television-mdpi")
class CinematicScreensTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun wear() {
        NixieTheme.register(CinematicTheme)
        NixieTheme.use(NixieThemeId.Cinematic)
    }

    @After
    fun takeOff() = NixieTheme.use(NixieThemeId.Glacier)

    @Test
    fun signInQuickConnect() = shoot("01-signin-quickconnect", focused = "New code") { SignInStandIn(code = "482916") }

    @Test
    fun signInWaitingForCode() = shoot("02-signin-waiting", focused = "New code") { SignInStandIn(code = null) }

    @Test
    fun topBarOnTheStage() = shoot("03-topbar-stage") { StagePageStandIn() }

    @Test
    fun home() = shoot("04-home", focused = "Resume") { HomeStandIn { BrightBackdrop(it) } }

    @Test
    fun homeOverWhiteArt() = shoot("05-home-white-art", focused = "Resume") {
        HomeStandIn { Box(it.background(Color.White)) }
    }

    private fun shoot(
        name: String,
        focused: String? = null,
        content: @Composable () -> Unit,
    ) {
        // A fixed clock, so the focus zoom settles at the same frame on every run.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // A TV has no touch screen: in keyboard (D-pad) mode controls take focus, as on the box.
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme { Screen { content() } }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        if (focused != null) compose.onNodeWithText(focused).assertIsFocused()
        compose.onRoot().captureRoboImage(shotFile("$name.png").path)
    }

    private companion object {
        const val SETTLE_MS = 700L
    }
}

/** Keyboard input mode, as on a TV box with its remote. */
private object DpadMode : InputModeManager {
    override val inputMode: InputMode = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
}
