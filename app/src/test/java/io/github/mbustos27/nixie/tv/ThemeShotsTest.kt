// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.TopNav
import io.github.glacier_jellyfin.androidtv.setup.SetupButton
import io.github.glacier_jellyfin.androidtv.setup.SetupHeading
import io.github.glacier_jellyfin.androidtv.setup.SetupLayout
import io.github.glacier_jellyfin.androidtv.ui.ImagePageGround
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import io.github.mbustos27.nixie.tv.home.NixieHomePage
import io.github.mbustos27.nixie.tv.theme.cinematic.CinematicTheme
import io.github.mbustos27.nixie.tv.theme.drivein.DriveInTheme
import io.github.mbustos27.nixie.tv.theme.editorial.EditorialTheme
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every theme's sign-in, top bar and Home, rendered on the JVM at the TV's 1920 x 1080 with Glacier's real
 * composables and sample data (no server, no emulator). Run it with
 * `.\gradlew.bat :app:testNixieDebugUnitTest --tests "*ThemeShotsTest*"`; the images land in
 * `app\build\outputs\roborazzi\<theme>\<screen>.png`. A theme stream checks its own folder; nothing is compared yet,
 * so the test fails only when a screen cannot be drawn.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class ThemeShotsTest(private val theme: NixieThemeId, private val fontScale: Float) {

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
    fun signIn() = shot("sign-in", focusOn = "New code") {
        SetupLayout(
            intro = {
                SetupHeading("Quick Connect", "Sign in from a phone or computer that is already signed in.")
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(30.dp)) {
                Text("CODE FOR HOME SERVER", style = GlacierText.body(16), color = GlacierColors.Mist)
                NixieTheme.slots.quickConnectCode?.invoke("482913", 6)
                    ?: Text("482913 (Glacier's code boxes)", style = GlacierText.mono(68))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SetupButton("New code", onClick = {}, primary = true)
                    SetupButton("Use password", onClick = {})
                }
            }
        }
    }

    @Test
    fun topBar() = shot("top-bar", focusOn = "Home") {
        Box(Modifier.fillMaxSize()) {
            Bar(Modifier.align(Alignment.TopCenter).padding(top = 34.dp))
        }
    }

    // tv-home-two-rows: Home is Continue watching, then Up next, under the hero (the focused card's picture and facts),
    // on one screen; at the large text size too.
    @Test
    fun home() = shot("home", focusOn = "The Long Drive Home") {
        Box(Modifier.fillMaxSize().background(ImagePageGround)) {
            NixieHomePage(
                continueWatching = listOf(sample("The Long Drive Home", resume = 0.4f), sample("Desert Lights", 0.7f)),
                upNext = (1..POSTERS).map { sample("Show $it") },
                continueWatchingIn = true,
                upNextIn = true,
                onOpen = {},
                onAskRemove = {},
                onUndo = {},
            )
            Bar(Modifier.align(Alignment.TopCenter).padding(top = 34.dp))
        }
    }

    @Composable
    private fun Bar(modifier: Modifier) = TopNav(
        active = NavTarget.Home,
        kinds = listOf(LibraryKind.Movies, LibraryKind.Shows, LibraryKind.Music),
        userName = "Ana",
        onSelect = {},
        modifier = modifier.fillMaxWidth(),
    )

    /** Draws [content] full screen in the theme, focuses the element labelled [focusOn], and saves the image. */
    private fun shot(
        name: String,
        focusOn: String,
        content: @Composable () -> Unit,
    ) {
        // A fixed clock: the hero's slow zoom and the focus scale settle at the same frame on every run.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // A TV has no touch screen: in keyboard (D-pad) mode buttons take focus, as on the box.
            // The system's text size (1.0, and Android's large text, 1.3), which GlacierTheme keeps.
            val density = Density(LocalDensity.current.density, fontScale = fontScale)
            CompositionLocalProvider(LocalInputModeManager provides DpadMode, LocalDensity provides density) {
                GlacierTheme {
                    GlacierBackground { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(FRAME_MS)
        compose.onNodeWithText(focusOn).requestFocus()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        compose.onNodeWithText(focusOn).assertIsFocused()
        val suffix = if (fontScale == 1f) "" else "@${fontScale}x"
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${theme.name}/$name$suffix.png")
    }

    private fun sample(title: String, resume: Float? = null) = MediaItem(
        id = UUID.nameUUIDFromBytes(title.toByteArray()),
        kind = ItemKind.Movie,
        title = title,
        sortName = title.lowercase(),
        year = 2026,
        communityRating = 7.8f,
        officialRating = "PG-13",
        runtimeMinutes = 118,
        genres = listOf("Drama"),
        overview = "A night at the last drive-in on Route 66, where the screen lights up the desert one more time.",
        parentTitle = null,
        seasonNumber = null,
        episodeNumber = null,
        progress = resume,
        remainingMinutes = resume?.let { 71 },
        unwatchedCount = null,
        isFavorite = false,
        played = false,
        childCount = null,
        quality = null,
        posterUrl = null,
        thumbUrl = null,
        backdropUrl = null,
    )

    companion object {
        private const val POSTERS = 8
        private const val FRAME_MS = 32L
        private const val SETTLE_MS = 600L

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} at {1}x")
        fun themes(): List<Array<Any>> = NixieThemeId.entries.flatMap { theme ->
            FONT_SCALES.map { arrayOf<Any>(theme, it) }
        }

        /** The default text size and Android's large text. */
        private val FONT_SCALES = listOf(1f, 1.3f)
    }
}

/** Keyboard input mode, as on a TV box with its remote. */
internal object DpadMode : InputModeManager {
    override val inputMode: InputMode = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
}
