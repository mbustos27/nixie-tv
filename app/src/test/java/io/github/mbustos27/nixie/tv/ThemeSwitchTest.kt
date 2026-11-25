// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.settings.ChoicePills
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import io.github.glacier_jellyfin.androidtv.ui.PosterCard
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import io.github.mbustos27.nixie.tv.theme.cinematic.CinematicTheme
import io.github.mbustos27.nixie.tv.theme.drivein.DriveInTheme
import io.github.mbustos27.nixie.tv.theme.editorial.EditorialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > Appearance's theme picker: which themes it offers, that a pick is kept, and that a switch in the middle
 * of a session keeps the focused item and the data on screen (the state sits above the theme).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class ThemeSwitchTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun register() {
        listOf(DriveInTheme, CinematicTheme, EditorialTheme).forEach(NixieTheme::register)
        NixieTheme.use(NixieThemeId.DriveIn)
    }

    @After
    fun takeOff() = NixieTheme.use(NixieThemeId.Glacier)

    @Test
    fun pickerOffersTheThreeThemesAndGlacierOnlyInDebug() {
        val release = NixieThemes.pickable(debug = false)
        assertEquals(listOf(NixieThemeId.DriveIn, NixieThemeId.Cinematic, NixieThemeId.Editorial), release)
        assertFalse(NixieThemeId.Glacier in release)
        assertEquals(release + NixieThemeId.Glacier, NixieThemes.pickable(debug = true))
        val labels = NixieThemeId.entries.map { context.getString(NixieThemes.labelOf(it)) }
        assertEquals("every theme has its own name", labels.size, labels.toSet().size)
    }

    @Test
    fun aPickIsWornAtOnceAndKeptForTheNextStart() {
        NixieThemes.choose(context, NixieThemeId.Editorial)
        assertEquals(NixieThemeId.Editorial, NixieTheme.current.id)
        NixieTheme.use(NixieThemeId.Glacier)
        NixieThemes.install(context) // as at the next start
        assertEquals(NixieThemeId.Editorial, NixieTheme.current.id)
        NixieThemes.choose(context, NixieThemeId.DriveIn)
    }

    @Test
    fun switchingMidSessionKeepsTheFocusedCardAndTheData() {
        show {
            MediaRow(title = "New in Movies") {
                items(CARDS) { i ->
                    PosterCard(imageUrl = null, caption = "Caption $i", title = "Film $i", onClick = {})
                }
            }
        }
        compose.onNodeWithText("Caption 2").requestFocus()
        settle()
        for (theme in listOf(NixieThemeId.Cinematic, NixieThemeId.Editorial, NixieThemeId.DriveIn)) {
            NixieTheme.use(theme)
            settle()
            compose.onNodeWithText("Caption 2").assertIsFocused()
            compose.onNode(isFocused()).assertExists()
            for (i in 0 until CARDS) compose.onNodeWithText("Caption $i").assertExists()
        }
    }

    @Test
    fun thePickersOwnPillKeepsTheFocusAcrossTheSwitchItMakes() {
        val ids = NixieThemes.pickable(debug = false)
        show {
            ChoicePills(
                options = ids.map { context.getString(NixieThemes.labelOf(it)) },
                selected = { ids[it] == NixieTheme.current.id },
                onSelect = { NixieThemes.choose(context, ids[it]) },
            )
        }
        val cinematic = context.getString(NixieThemes.labelOf(NixieThemeId.Cinematic))
        compose.onNodeWithText(cinematic).requestFocus()
        settle()
        compose.onNodeWithText(cinematic).performClick()
        settle()
        assertEquals(NixieThemeId.Cinematic, NixieTheme.current.id)
        compose.onNodeWithText(cinematic).assertIsFocused()
        NixieThemes.choose(context, NixieThemeId.DriveIn)
    }

    private fun show(content: @Composable () -> Unit) {
        // Infinite animations never let the clock idle: it moves only when the test says so.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // A TV has no touch screen: in keyboard (D-pad) mode buttons take focus, as on the box.
            CompositionLocalProvider(LocalInputModeManager provides RemoteKeysMode) {
                GlacierTheme {
                    GlacierBackground { content() }
                }
            }
        }
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
    }

    private companion object {
        const val CARDS = 5
        const val SETTLE_MS = 600L
    }
}

/** Keyboard input mode, as on a TV box with its remote. */
private object RemoteKeysMode : InputModeManager {
    override val inputMode: InputMode = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
}
