// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
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
import io.github.mbustos27.nixie.tv.theme.cinematic.CinematicTheme
import io.github.mbustos27.nixie.tv.theme.drivein.DriveInTheme
import io.github.mbustos27.nixie.tv.theme.editorial.EditorialTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * tv-1.0-r1: Report a problem's page in every theme, on the JVM at 1920 x 1080: the steps, both versions, the code and
 * Close with the focus. `.\gradlew.bat :app:testNixieDebugUnitTest --tests "*ReportShotsTest*"`; the images land in
 * `app\build\outputs\roborazzi\<theme>\report.png`. Nothing is compared: it fails only when the page cannot be drawn or
 * the focus does not land.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class ReportShotsTest(private val theme: NixieThemeId) {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun wear() {
        listOf(DriveInTheme, CinematicTheme, EditorialTheme).forEach(NixieTheme::register)
        NixieTheme.use(theme)
    }

    @After
    fun takeOff() = NixieTheme.use(NixieThemeId.Glacier)

    @Test
    fun report() {
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme { NixieReportScreen(NixieReportProblem.report("1.0.0", "12.4.0"), onClose = {}) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Close").assertIsFocused()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${theme.name}/report.png")
    }

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = NixieThemeId.entries.map { arrayOf(it) }
    }
}
