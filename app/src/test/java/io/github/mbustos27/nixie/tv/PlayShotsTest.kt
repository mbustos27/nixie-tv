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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.playback.MediaSegment
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.home.Spotlight
import io.github.glacier_jellyfin.androidtv.player.SkipButton
import io.github.glacier_jellyfin.androidtv.player.UpNextCard
import io.github.glacier_jellyfin.androidtv.player.UpNextCountdown
import io.github.glacier_jellyfin.androidtv.ui.ImagePageGround
import io.github.glacier_jellyfin.androidtv.ui.PosterCard
import io.github.mbustos27.nixie.tv.data.search.SearchKind
import io.github.mbustos27.nixie.tv.data.skips.SegmentSource
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import io.github.mbustos27.nixie.tv.play.SearchChips
import io.github.mbustos27.nixie.tv.play.ShowMoreCard
import io.github.mbustos27.nixie.tv.play.playLabelText
import io.github.mbustos27.nixie.tv.play.searchCaption
import io.github.mbustos27.nixie.tv.theme.cinematic.CinematicTheme
import io.github.mbustos27.nixie.tv.theme.drivein.DriveInTheme
import io.github.mbustos27.nixie.tv.theme.editorial.EditorialTheme
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The play stream's screens in Drive-in, on the JVM at 1920 x 1080 (the setup of [ThemeShotsTest]): the Skip button
 * and its source, the Play labels, search. Images in `app\build\outputs\roborazzi\play\`. Run it with
 * `.\gradlew.bat :app:testNixieDebugUnitTest --tests "*PlayShotsTest*"`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class PlayShotsTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun wear() {
        listOf(DriveInTheme, CinematicTheme, EditorialTheme).forEach(NixieTheme::register)
        NixieTheme.use(NixieThemeId.DriveIn)
    }

    @After
    fun takeOff() = NixieTheme.use(NixieThemeId.Glacier)

    @Test
    fun `the skip button says where the skip came from`() {
        val sources = mapOf(
            SegmentSource.Chapters to "Skip intro. From the file's chapters",
            SegmentSource.Learned to "Skip intro. Learned from your own skips this season",
        )
        draw {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(80.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    sources.keys.forEach { source ->
                        SkipButton(
                            segment = MediaSegment(SegmentKind.Intro, 60_000, 150_000, source),
                            positionMs = 75_000,
                            playing = false,
                            onClick = {},
                        )
                    }
                }
            }
        }
        sources.values.forEach { compose.onNodeWithContentDescription(it).assertExists() }
        capture("skip-sources")
    }

    @Test
    fun `without the server's autoplay the up next card has no countdown`() {
        draw {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(80.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    listOf(true, false).forEach { autoPlay ->
                        UpNextCard(
                            next = episode("The Night Shift", season = 2, number = 1),
                            countdown = UpNextCountdown(23_000, 40_000, autoPlay = autoPlay),
                            playing = false,
                            playFocus = remember { FocusRequester() },
                            onPlay = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("NEXT EPISODE IN 23 S").assertExists()
        compose.onNodeWithText("UP NEXT").assertExists()
        capture("up-next-autoplay")
    }

    @Test
    fun `the hero names the episode it resumes, two in one file`() {
        draw {
            Box(Modifier.fillMaxSize().background(ImagePageGround)) {
                Spotlight(
                    items = listOf(episode("Two for the Road", season = 1, number = 4, numberEnd = 5, resume = 0.4f)),
                    index = 0,
                    onSelect = {},
                    onPlay = {},
                    onInfo = {},
                    onFavorite = {},
                    playFocus = remember { FocusRequester() },
                )
            }
        }
        compose.onNodeWithText("Resume S1 E4–5").assertExists()
        capture("hero-resume-two-episodes")
    }

    @Test
    fun `item pages say which episode plays`() {
        draw {
            Column(Modifier.padding(80.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(
                    episode("The Night Shift", season = 2, number = 1),
                    episode("Neon Lights", season = 1, number = 4, resume = 0.5f),
                ).forEach { Text(playLabelText(it).orEmpty(), style = GlacierText.body(28), color = GlacierColors.Ice) }
                Text(
                    playLabelText(null) ?: "(Glacier's own label)",
                    style = GlacierText.body(28),
                    color = GlacierColors.Mist,
                )
            }
        }
        compose.onNodeWithText("Play S2 E1").assertExists()
        compose.onNodeWithText("Resume S1 E4").assertExists()
        capture("play-labels")
    }

    @Test
    fun `search shows type chips and folds weak matches behind Show N more`() {
        draw {
            Column(Modifier.padding(80.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                SearchChips(
                    listOf(SearchKind.Shows, SearchKind.Movies, SearchKind.People),
                    SearchKind.Movies,
                    onPick = {},
                )
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    PosterCard(imageUrl = null, caption = "1999", onClick = {}, title = "The Matrix")
                    PosterCard(imageUrl = null, caption = "Person", onClick = {}, title = "Keanu Reeves")
                    ShowMoreCard(4, onClick = {})
                }
            }
        }
        compose.onNodeWithText("All").assertExists()
        compose.onNodeWithText("Movies").assertExists()
        compose.onNodeWithText("Show 4 more").assertExists()
        capture("search-chips")
    }

    @Test
    fun `an episode in the search results names its show and its place in it`() {
        // Bug hunt M4: episodes called "Bridge" showed only a year, so nothing told them apart.
        val bridge = episode("Bridge", season = 1, number = 8)
        draw {
            Row(Modifier.padding(80.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                PosterCard(
                    imageUrl = null,
                    caption = searchCaption(bridge).orEmpty(),
                    onClick = {},
                    title = bridge.title,
                )
                PosterCard(
                    imageUrl = null,
                    caption = searchCaption(episode("Bridge", season = 2, number = 3, numberEnd = 4)).orEmpty(),
                    onClick = {},
                    title = "Bridge",
                )
            }
        }
        compose.onNodeWithText("Route 66 · S1 E8").assertExists()
        compose.onNodeWithText("Route 66 · S2 E3–4").assertExists()
        capture("search-episodes")
    }

    private fun episode(
        title: String,
        season: Int,
        number: Int,
        numberEnd: Int? = null,
        resume: Float? = null,
    ) = MediaItem(
        id = UUID.nameUUIDFromBytes(title.toByteArray()),
        kind = ItemKind.Episode,
        title = title,
        sortName = title.lowercase(),
        year = 2026,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = 44,
        genres = emptyList(),
        overview = "Mabel works the night shift at the drive-in's ticket booth.",
        parentTitle = "Route 66",
        seasonNumber = season,
        episodeNumber = number,
        progress = resume,
        remainingMinutes = resume?.let { 20 },
        unwatchedCount = null,
        isFavorite = false,
        played = false,
        childCount = null,
        quality = null,
        posterUrl = null,
        thumbUrl = null,
        backdropUrl = null,
        episodeNumberEnd = numberEnd,
    )

    private fun draw(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides PlayDpadMode) {
                GlacierTheme {
                    GlacierBackground { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(FRAME_MS)
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/play/$name.png")
    }

    private companion object {
        const val FRAME_MS = 32L
        const val SETTLE_MS = 600L
    }
}

/** Keyboard input mode, as on a TV box with its remote. */
private object PlayDpadMode : InputModeManager {
    override val inputMode: InputMode = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
}
