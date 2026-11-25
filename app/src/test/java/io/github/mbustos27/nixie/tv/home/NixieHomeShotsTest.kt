// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryPage
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.LibrarySortChoice
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.library.BrowseMemory
import io.github.glacier_jellyfin.androidtv.library.LibraryScreen
import io.github.glacier_jellyfin.androidtv.library.LibraryViewModel
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.TopNav
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import io.github.mbustos27.nixie.tv.library.NixieLibrariesPage
import io.github.mbustos27.nixie.tv.theme.cinematic.CinematicTheme
import io.github.mbustos27.nixie.tv.theme.drivein.DriveInTheme
import io.github.mbustos27.nixie.tv.theme.editorial.EditorialTheme
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home and the Collections page in every theme, on the JVM at 1920 x 1080 (as ThemeShotsTest, whose setup this
 * copies), with the top bar: Home as Continue watching (with an Undo card) and then Up next under the hero (the
 * focused card's picture and facts); Continue watching empty; Up next empty; both empty; the Remove sheet; the
 * Collections page, every collection A to Z (and Z to A by its order button, tv-1.0-r1), and its line when the server
 * has none. Run it with
 * `.\gradlew.bat :app:testNixieDebugUnitTest --tests "*NixieHomeShotsTest*"`; the images land in
 * `app\build\outputs\roborazzi\<theme>\home-*.png` and `collections*.png`. Nothing is compared: it fails only when a
 * screen cannot be drawn or the focus does not land.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class NixieHomeShotsTest(private val theme: NixieThemeId) {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun wear() {
        listOf(DriveInTheme, CinematicTheme, EditorialTheme).forEach(NixieTheme::register)
        NixieTheme.use(theme)
    }

    @After
    fun takeOff() {
        NixieTheme.use(NixieThemeId.Glacier)
        unmockkAll()
    }

    private val harbour = movie("Harbour Lights", progress = 0.4f, left = 31)
    private val ferry = movie("Night Ferry", progress = 0.6f, left = 12)
    private val salt = movie("Salt Flats", progress = 0.2f, left = 72)
    private val upNext = listOf(
        episode("Iron Lighthouses", 3, 5, "Fog Signal"),
        episode("Paper Moons", 1, 2, "Tide"),
        episode("The Long Dark", 2, 1, "Winter Road"),
    )

    @Test
    fun rows() = shot("home-rows", focusOn = "Harbour Lights") {
        Home(listOf(harbour, ferry, salt), upNext, pending = setOf(ferry.id))
    }

    // The hero on an Up next episode: its show's name as the title, the episode below.
    @Test
    fun hero() = shot("home-hero", focusOn = "Paper Moons") { Home(listOf(harbour, salt), upNext) }

    @Test
    fun continueEmpty() = shot("home-continue-empty", focusOn = "Iron Lighthouses") { Home(emptyList(), upNext) }

    @Test
    fun upNextEmpty() = shot("home-up-next-empty", focusOn = "Harbour Lights") {
        Home(listOf(harbour, salt), emptyList())
    }

    // Both empty: the page says what to do next; the top bar's Home entry has the focus.
    @Test
    fun empty() = shot("home-empty", focusOn = "Home") { Home(emptyList(), emptyList()) }

    @Test
    fun removeSheet() = shot("home-remove", focusOn = "Remove") {
        Box(Modifier.fillMaxSize()) {
            Column { MediaRow(title = "Continue watching", index = 0) { item { NixieUndoCard("Night Ferry", {}) } } }
            NixieRemoveSheet("Harbour Lights", onRemove = {}, onCancel = {})
        }
    }

    @Test
    fun collections() = shot("collections", focusOn = "Archive of Light Collection") { Collections(SETS.size) }

    // tv-1.0-r1: the page flipped to Z to A by its order button, which has the focus.
    @Test
    fun collectionsZToA() = shot("collections-z-to-a", focusOn = "Z to A") {
        Collections(SETS.size, descending = true)
    }

    // No collections on the server: the page says so in a line, with the focus where the page puts it.
    @Test
    fun collectionsNone() = shot("collections-none", focusOn = null) { Collections(0) }

    // The top bar's Libraries page: 23 libraries, as on a big shared server, in the server's order.
    @Test
    fun libraries() = shot("libraries", focusOn = "Marvel") {
        Box(Modifier.fillMaxSize()) {
            NixieLibrariesPage(LIBRARIES, loading = false, onOpen = {})
            TopNav(
                active = NavTarget.Libraries,
                kinds = listOf(LibraryKind.Movies, LibraryKind.Shows, LibraryKind.Music),
                userName = "Ana",
                onSelect = {},
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 34.dp),
            )
        }
    }

    /** Home as the app draws it: the page and the top bar over it, Collections after the libraries. */
    @Composable
    private fun Home(
        continueWatching: List<MediaItem>,
        upNext: List<MediaItem>,
        pending: Set<UUID> = emptySet(),
    ) = Box(Modifier.fillMaxSize()) {
        NixieHomePage(
            continueWatching = continueWatching,
            upNext = upNext,
            continueWatchingIn = true,
            upNextIn = true,
            onOpen = {},
            onAskRemove = {},
            onUndo = {},
            pending = pending,
        )
        TopNav(
            active = NavTarget.Home,
            kinds = listOf(LibraryKind.Movies, LibraryKind.Shows, LibraryKind.Music),
            userName = "Ana",
            onSelect = {},
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 34.dp),
        )
    }

    /** The real Collections page (LibraryScreen) with [count] collections, from a faked server; Z to A if [descending]. */
    @Composable
    private fun Collections(count: Int, descending: Boolean = false) =
        LibraryScreen(onNavigate = {}, viewModel = collectionsModel(count, descending))

    private fun collectionsModel(count: Int, descending: Boolean): LibraryViewModel {
        val repository = mockk<LibraryRepository>(relaxed = true)
        val sets = SETS.take(count).mapIndexed { i, name -> collection(name, i) }
            .let { if (descending) it.asReversed() else it }
        coEvery { repository.firstPage(any(), any()) } answers {
            LibraryPage(sets.take(secondArg()), if (count == 0) 0 else null, serverOrder = true)
        }
        coEvery { repository.page(any(), any(), any(), any(), any(), any()) } answers {
            LibraryPage(sets.drop(secondArg<Int>()).take(thirdArg()), count, serverOrder = true)
        }
        coEvery { repository.serverPosition(any()) } returns count
        coEvery { repository.knownLetters(any()) } returns null
        every { repository.progress } returns MutableStateFlow(null)
        // The page's own kept sort (tv-1.0-r1): A to Z, or Z to A.
        val sort = LibrarySortChoice(LibrarySort.Title, descending)
        val kept = ProfileSettings(librarySorts = mapOf(LibraryQuery.NIXIE_COLLECTIONS_KEY to sort))
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.settings } returns MutableStateFlow(kept)
        coEvery { settings.current() } returns kept
        val sessions = mockk<SessionManager>(relaxed = true)
        every { sessions.session } returns MutableStateFlow(null)
        val details = mockk<DetailRepository>(relaxed = true)
        every { details.playedChanged } returns MutableSharedFlow()
        val playback = mockk<PlaybackRepository>(relaxed = true)
        every { playback.stopped } returns MutableSharedFlow()
        val home = mockk<HomeRepository>(relaxed = true)
        every { home.kinds } returns MutableStateFlow(listOf(LibraryKind.Movies, LibraryKind.Shows))
        return LibraryViewModel(
            SavedStateHandle(mapOf("kind" to LibraryKind.Movies.name, "collections" to true)),
            repository,
            sessions,
            settings,
            mockk(relaxed = true),
            mockk(relaxed = true),
            details,
            playback,
            home,
            mockk(relaxed = true),
            BrowseMemory(flowOf(null), Dispatchers.Unconfined),
        )
    }

    /** Draws [content] full screen in the theme, focuses the element labelled [focusOn] if any, and saves the image. */
    private fun shot(
        name: String,
        focusOn: String?,
        content: @Composable () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme {
                    GlacierBackground { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(FRAME_MS)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        if (focusOn != null) {
            compose.onNodeWithText(focusOn).requestFocus()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(SETTLE_MS)
            compose.waitForIdle()
            compose.onNodeWithText(focusOn).assertIsFocused()
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${theme.name}/$name.png")
    }

    private fun movie(
        title: String,
        progress: Float,
        left: Int,
    ) = sample(title, ItemKind.Movie, null, null, null).copy(progress = progress, remainingMinutes = left)

    private fun episode(
        show: String,
        season: Int,
        number: Int,
        title: String,
    ) = sample(title, ItemKind.Episode, show, season, number)

    private fun collection(name: String, i: Int) =
        sample(name, ItemKind.Collection, null, null, null).copy(childCount = 2 + i % 5, genres = emptyList())

    private fun sample(
        title: String,
        kind: ItemKind,
        show: String?,
        season: Int?,
        number: Int?,
    ) = MediaItem(
        id = UUID.nameUUIDFromBytes("$show/$title".toByteArray()),
        kind = kind,
        title = title,
        sortName = title.lowercase(),
        year = 2026,
        communityRating = 7.8f,
        officialRating = "TV-14",
        runtimeMinutes = 48,
        genres = listOf("Drama"),
        overview = "The keeper's logbook turns up on the wrong side of the bay, a week before the fog comes in.",
        parentTitle = show,
        seriesId = show?.let { UUID.nameUUIDFromBytes(it.toByteArray()) },
        seasonNumber = season,
        episodeNumber = number,
        progress = null,
        remainingMinutes = null,
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
        private const val FRAME_MS = 32L
        private const val SETTLE_MS = 700L

        /** Collections as a server names them, A to Z. */
        private val SETS = listOf(
            "Archive of Light Collection", "Blue Harbour Collection", "Cold Summer Collection",
            "Desert Lights Collection", "Ember Road Collection", "Ferry Lines Collection",
            "Glass Coast Collection", "Harvest Moon Collection", "Iron Lighthouses Collection",
            "Juniper Trail Collection", "Kingfisher Collection", "Long Dark Collection",
            "Midnight Market Collection", "North Pier Collection", "Orchard Collection",
            "Paper Moons Collection", "Quiet Harbour Collection", "Red Canyon Collection",
            "Salt Flats Collection", "Tidewater Collection", "Umber Fields Collection",
        )

        /** 23 libraries: films by hub, shows, music and home videos, some still being counted. */
        private val LIBRARIES = listOf(
            "Marvel" to LibraryKind.Movies, "DC" to LibraryKind.Movies, "Kids Movies" to LibraryKind.Movies,
            "Classics" to LibraryKind.Movies, "4K Movies" to LibraryKind.Movies, "Anime Movies" to LibraryKind.Movies,
            "Documentaries" to LibraryKind.Movies, "Holiday" to LibraryKind.Movies, "Horror" to LibraryKind.Movies,
            "Star Wars" to LibraryKind.Movies, "Pixar" to LibraryKind.Movies, "Bond" to LibraryKind.Movies,
            "Westerns" to LibraryKind.Movies, "Concerts" to LibraryKind.Movies, "TV Shows" to LibraryKind.Shows,
            "Kids Shows" to LibraryKind.Shows, "Anime" to LibraryKind.Shows, "Reality" to LibraryKind.Shows,
            "British TV" to LibraryKind.Shows, "Cartoons" to LibraryKind.Shows, "Music" to LibraryKind.Music,
            "Audiobooks" to LibraryKind.Music, "Family videos" to LibraryKind.HomeVideos,
        ).mapIndexed { i, (name, kind) ->
            Library(UUID.nameUUIDFromBytes(name.toByteArray()), name, kind, (i * 397 + 41).takeIf { i % 5 != 4 }, null)
        }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = NixieThemeId.entries.map { arrayOf(it) }
    }
}

/** A TV has no touch screen: in keyboard (D-pad) mode buttons take focus, as on the box. */
private object DpadMode : InputModeManager {
    override val inputMode: InputMode = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
}
