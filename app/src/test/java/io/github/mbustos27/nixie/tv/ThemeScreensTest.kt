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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.playback.MediaSegment
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
import io.github.glacier_jellyfin.androidtv.core.designsystem.ActionButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.detail.Crumb
import io.github.glacier_jellyfin.androidtv.detail.EpisodeCard
import io.github.glacier_jellyfin.androidtv.detail.Overview
import io.github.glacier_jellyfin.androidtv.detail.SeenButton
import io.github.glacier_jellyfin.androidtv.detail.Title
import io.github.glacier_jellyfin.androidtv.library.AlphabetRail
import io.github.glacier_jellyfin.androidtv.player.OsdButton
import io.github.glacier_jellyfin.androidtv.player.PlayerOsd
import io.github.glacier_jellyfin.androidtv.player.PlayerProgress
import io.github.glacier_jellyfin.androidtv.player.PlayerUiState
import io.github.glacier_jellyfin.androidtv.player.SkipButton
import io.github.glacier_jellyfin.androidtv.player.UpNextCard
import io.github.glacier_jellyfin.androidtv.player.UpNextCountdown
import io.github.glacier_jellyfin.androidtv.ui.DetailBackdrop
import io.github.glacier_jellyfin.androidtv.ui.FactsRow
import io.github.glacier_jellyfin.androidtv.ui.FilterChip
import io.github.glacier_jellyfin.androidtv.ui.GridCard
import io.github.glacier_jellyfin.androidtv.ui.ImagePageGround
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import io.github.mbustos27.nixie.tv.designsystem.nixiePlayArea
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
 * Phase 2's screens in every theme: a library page, a movie page, a series page with its episodes, and the player's
 * OSD with the Skip button and the Up next card, drawn on the JVM at 1920 x 1080 from Glacier's composables and
 * sample data. Glacier's private page layouts are stood in for by their public parts in the same places; every
 * slot of `slots.browse`, `slots.item` and `slots.player` is reached. Images:
 * `app\build\outputs\roborazzi\<theme>\<library|movie|series|player>.png`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class ThemeScreensTest(private val theme: NixieThemeId, private val fontScale: Float) {

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
    fun library() = shot("library", focusOn = "Unwatched") {
        Row(Modifier.fillMaxSize().padding(start = PageEdge.dp, top = 120.dp, end = 24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                NixieTheme.slots.browse.libraryHeader?.invoke("Movies", "320 titles", Modifier) ?: Column {
                    Text("Movies", style = GlacierText.display(50), color = GlacierColors.Ice)
                    Text("320 titles", style = GlacierText.body(19), color = GlacierColors.Mist)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FilterChip("All", active = true, onClick = {})
                    FilterChip("Unwatched", active = false, onClick = {})
                    FilterChip("Favorites", active = false, onClick = {})
                }
                repeat(2) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        repeat(GRID_COLUMNS) { i ->
                            GridCard(
                                imageUrl = null,
                                title = "Film ${row * GRID_COLUMNS + i + 1}",
                                caption = "2026",
                                onClick = {},
                            )
                        }
                    }
                }
            }
            AlphabetRail(
                letters = ('A'..'Z').toSet() - setOf('Q', 'X', 'Z'),
                descending = false,
                current = 'D',
                focus = remember { FocusRequester() },
                onLetter = {},
            )
        }
    }

    @Test
    fun movie() = shot("movie", focusOn = "Resume") {
        ItemPage(sample("The Long Drive Home", resume = 0.4f), crumb = "Movies") {
            ActionButton(onClick = {}, label = "Resume", icon = GlacierIcons.Play, primary = true)
            ActionButton(onClick = {}, label = "Play from the start")
            ActionButton(onClick = {}, icon = GlacierIcons.Heart, contentDescription = "Favourite")
            SeenButton(played = false, onClick = {})
        }
    }

    @Test
    fun series() = shot("series", focusOn = "Season 1") {
        Column {
            ItemPage(
                sample("Desert Lights", kind = ItemKind.Series),
                crumb = "Shows · Season 1",
                compact = true,
            ) {
                ActionButton(onClick = {}, label = "Play S1 E3", icon = GlacierIcons.Play, primary = true)
                SeenButton(played = false, onClick = {})
            }
            Row(
                Modifier.padding(start = PageEdge.dp, top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FilterChip("Season 1", active = true, onClick = {}, height = 50, fontSize = 18)
                FilterChip("Season 2", active = false, onClick = {}, height = 50, fontSize = 18, checked = true)
                FilterChip("Season 3", active = false, onClick = {}, height = 50, fontSize = 18)
            }
            Row(
                Modifier.padding(start = PageEdge.dp, top = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                repeat(EPISODES) { i ->
                    EpisodeCard(
                        episode = episode(i + 1),
                        onClick = {},
                        current = i == 2,
                    )
                }
            }
        }
    }

    @Test
    fun player() = shot("player", focusOn = "Skip", substring = true) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Color(0xFF2B4A6B), Color(0xFF8A5A3C), Color(0xFF1B1B24))),
            ),
        ) {
            val intro = MediaSegment(SegmentKind.Intro, INTRO_START_MS, INTRO_END_MS)
            PlayerOsd(
                state = PlayerUiState(
                    loading = false,
                    playing = true,
                    segments = listOf(intro, MediaSegment(SegmentKind.Outro, OUTRO_START_MS, LENGTH_MS)),
                ),
                progress = PlayerProgress(positionMs = POSITION_MS, durationMs = LENGTH_MS, bufferedMs = BUFFERED_MS),
                scrubMs = null,
                seekFocus = remember { FocusRequester() },
                playFocus = remember { FocusRequester() },
                buttonFocus = remember { OsdButton.entries.associateWith { FocusRequester() } },
                onPlayFocused = {},
                onScrub = {},
                onCommitScrub = {},
                onTogglePlay = {},
                onSeekBy = {},
                onOpen = {},
                onPrevious = {},
                onNext = {},
            )
            SkipButton(
                segment = intro,
                positionMs = INTRO_START_MS + (INTRO_END_MS - INTRO_START_MS) / 2,
                playing = false,
                onClick = {},
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 80.dp, bottom = 330.dp),
            )
            UpNextCard(
                next = episode(4),
                countdown = UpNextCountdown(remainingMs = 12_000, leadMs = 20_000),
                playing = false,
                playFocus = remember { FocusRequester() },
                onPlay = {},
                onDismiss = {},
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 80.dp, top = 140.dp),
            )
        }
    }

    /** An item page's top as Glacier lays it out: backdrop, crumb, title, facts, overview, the Play row. */
    @Composable
    private fun ItemPage(
        item: MediaItem,
        crumb: String,
        compact: Boolean = false,
        buttons: @Composable () -> Unit,
    ) {
        Box(Modifier.background(ImagePageGround)) {
            DetailBackdrop(url = null, height = if (compact) BACKDROP_COMPACT else BACKDROP)
            Column(
                Modifier.padding(start = PageEdge.dp, end = PageEdge.dp, top = if (compact) 90.dp else 130.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 22.dp),
            ) {
                Crumb(crumb)
                Title(item.title, size = if (compact) 64 else 84, maxWidth = 1180)
                FactsRow(item, listOf("2026", "1 h 58 min", "Drama"), tech = "4K · HDR10")
                if (!compact) item.overview?.let { Overview(it, maxWidth = 900) }
                Row(
                    Modifier.padding(top = 10.dp).nixiePlayArea(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) { buttons() }
            }
        }
    }

    /** Draws [content] full screen in the theme, focuses the element labelled [focusOn], and saves the image. */
    private fun shot(
        name: String,
        focusOn: String,
        substring: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // The system's text size (1.0, and Android's large text, 1.3), which GlacierTheme keeps.
            val density = Density(LocalDensity.current.density, fontScale = fontScale)
            CompositionLocalProvider(LocalInputModeManager provides DpadMode, LocalDensity provides density) {
                GlacierTheme {
                    GlacierBackground { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(FRAME_MS)
        compose.onNodeWithText(focusOn, substring = substring).requestFocus()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        compose.onNodeWithText(focusOn, substring = substring).assertIsFocused()
        val suffix = if (fontScale == 1f) "" else "@${fontScale}x"
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${theme.name}/$name$suffix.png")
    }

    private fun sample(
        title: String,
        kind: ItemKind = ItemKind.Movie,
        resume: Float? = null,
    ) = MediaItem(
        id = UUID.nameUUIDFromBytes(title.toByteArray()),
        kind = kind,
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

    private fun episode(number: Int) = sample("Night Shift $number", kind = ItemKind.Episode).copy(
        seasonNumber = 1,
        episodeNumber = number,
        runtimeMinutes = 42,
        parentTitle = "Desert Lights",
        played = number == 1,
        progress = if (number == 3) 0.35f else null,
    )

    companion object {
        private const val GRID_COLUMNS = 6
        private const val EPISODES = 4
        private const val BACKDROP = 760
        private const val BACKDROP_COMPACT = 520
        private const val INTRO_START_MS = 60_000L
        private const val INTRO_END_MS = 150_000L
        private const val OUTRO_START_MS = 5_400_000L
        private const val LENGTH_MS = 5_700_000L
        private const val POSITION_MS = 1_234_000L
        private const val BUFFERED_MS = 1_800_000L
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
