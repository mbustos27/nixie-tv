// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import android.app.Application
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.glacier_jellyfin.androidtv.StartViewModel
import io.github.glacier_jellyfin.androidtv.channels.HomeLaunch
import io.github.glacier_jellyfin.androidtv.channels.HomeLaunches
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.EpisodeNeighbours
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.TrackSelections
import io.github.glacier_jellyfin.androidtv.core.data.media.Trailers
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackPosition
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferences
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferencesRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayback
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import io.github.glacier_jellyfin.androidtv.navigation.DetailRoute
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
import io.github.glacier_jellyfin.androidtv.player.PlayerScreen
import io.github.glacier_jellyfin.androidtv.player.PlayerViewModel
import io.github.glacier_jellyfin.androidtv.player.VersionChooser
import io.github.mbustos27.nixie.tv.data.skips.LearnedSkipStore
import io.github.mbustos27.nixie.tv.data.skips.SkipChain
import io.github.mbustos27.nixie.tv.data.skips.SkipLearner
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper

/**
 * TV-S12: the owner paused a title, pressed Home, came back through the launcher's Continue watching, and the title
 * loaded three times (the player screen opened it again by itself, was dropped when the card sent the app to Home, and
 * Home opened a second player screen). Here the real player screen and view model, StartViewModel's handling of the
 * card and the activity's routing of it, in a NavHost on a real activity whose lifecycle the box's paths drive; the
 * server and Media3 are fakes that count what they are asked. Each path back loads once: one open asked of the server,
 * one player prepared (one media item set), one start reported at the place it stood, one player screen, no Home.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class ResumeOnceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scheduler = TestCoroutineScheduler()
    private val launches = HomeLaunches()
    private lateinit var start: StartViewModel
    private lateinit var nav: NavHostController
    private lateinit var playback: PlaybackRepository

    /** What the server and Media3 were asked, in order. */
    private var opens = 0
    private var detailsAsked = 0
    private val starts = mutableListOf<PlaybackPosition>()
    private val stops = mutableListOf<Long>()
    private val players = mutableListOf<ExoPlayer>()
    private val listeners = mutableMapOf<ExoPlayer, MutableList<Player.Listener>>()

    /** Player screens made (view models), and times Home was opened. */
    private val screens = mutableListOf<PlayerViewModel>()
    private var homes = 0
    private var inFront = true

    private val filmId = UUID.nameUUIDFromBytes("film".toByteArray())
    private val card = HomeLaunch(SERVER, USER, filmId.toString(), play = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        PlayerReturn.app.forget()
        ShadowLog.clear()
        playback = mockk(relaxed = true)
        coEvery { playback.open(any(), any(), any(), any(), any(), any(), any()) } answers {
            opens++
            source
        }
        every { playback.reportStart(any(), any()) } answers { starts += secondArg<PlaybackPosition>() }
        every { playback.reportStopped(any(), any(), any(), any()) } answers { stops += secondArg<Long>() }
        coEvery { playback.segments(any()) } returns emptyList()
        every { playback.device } returns mockk(relaxed = true)
        every { playback.iec61937 } returns false
        mockkObject(GlacierPlayer)
        every { GlacierPlayer.create(any(), any()) } answers {
            val exo = mockk<ExoPlayer>(relaxed = true)
            every { exo.currentPosition } returns PLACE_MS
            every { exo.duration } returns RUNTIME_MS
            every { exo.addListener(any()) } answers {
                listeners.getOrPut(exo) { mutableListOf() } += firstArg<Player.Listener>()
            }
            players += exo
            mockk<GlacierPlayback>(relaxed = true).also { every { it.player } returns exo }
        }
        start = startViewModel()
        compose.mainClock.autoAdvance = false
        compose.setContent { App() }
        pump()
    }

    @After
    fun tearDown() {
        PlayerReturn.app.forget()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `Home, then the launcher's Continue watching, loads the title once on the same screen, paused`() {
        play()
        val before = Counts()
        home()
        assertEquals(listOf(PLACE_MS), stops)
        // The box: the activity starts, then the card's intent arrives (onNewIntent).
        backInFront()
        start.open(card)
        pump()
        frame()

        Counts().loadedOnceSince(before)
        assertNull(launches.pending)
        verify { players.last().playWhenReady = false }
        assertEquals(PLACE_MS, starts.last().positionMs)
        assertTrue(starts.last().paused)
        val line = ShadowLog.getLogsForTag(PlayerReturn.TAG).map { it.msg }.single { it.startsWith("Way back") }
        val expected = Regex(
            """Way back to the player: first frame \d+\.\d\d s, """ + "1 open asked of the server, 1 player prepared",
        )
        assertTrue(line, expected.matches(line))
    }

    @Test
    fun `standby, waking to the launcher, then its card, loads the title once on the same screen`() {
        play()
        val before = Counts()
        home() // the screen goes off: the activity stops
        // Waking shows the launcher; its card reaches the stopped activity before it starts.
        start.open(card)
        pump()
        backInFront()
        frame()

        Counts().loadedOnceSince(before)
        verify { players.last().playWhenReady = false }
        assertEquals(PLACE_MS, starts.last().positionMs)
    }

    @Test
    fun `another app in front brings the title back once, and a panel over it loads nothing`() {
        play()
        // A panel or the assistant over the player: the activity pauses only, the player is kept.
        moveTo(Lifecycle.State.STARTED)
        backInFront()
        assertEquals(1, opens)
        assertEquals(1, players.size)
        verify(exactly = 0) { players.single().release() }

        val before = Counts()
        home() // another app opens in front: the activity stops
        backInFront() // it closes, and Nixie is in front again
        frame()

        Counts().loadedOnceSince(before)
        verify { players.last().playWhenReady = false }
    }

    @Test
    fun `Back, then the title again, gives one stop, one new screen and one load from the server's resume point`() {
        play()
        // Newer Android may send Back as a key and to the back callback as well: the screen closes once.
        compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        repeat(2) {
            compose.mainClock.advanceTimeBy(FRAME_MS)
            compose.waitForIdle()
        }
        compose.activity.onBackPressedDispatcher.onBackPressed()
        pump()
        assertEquals(listOf(PLACE_MS), stops)
        assertTrue(nav.currentDestination?.hasRoute<HomeRoute>() == true)

        // Back on the title: Continue watching on Home opens it again, at the resume point the server keeps.
        val before = Counts()
        nav.navigate(PlayerRoute(filmId.toString()))
        pump()
        frame()

        val now = Counts()
        assertEquals(1, now.screens - before.screens)
        assertEquals(1, now.opens - before.opens)
        assertEquals(1, now.players - before.players)
        assertEquals(1, now.starts - before.starts)
        assertEquals(1, now.details - before.details)
        assertEquals(0, now.homes - before.homes)
        assertEquals(PLACE_MS, starts.last().positionMs)
    }

    /** The film plays from Home's Continue watching and shows its first frame. */
    private fun play() {
        nav.navigate(PlayerRoute(filmId.toString()))
        pump()
        frame()
        assertEquals(1, screens.size)
        assertEquals(1, players.size)
    }

    /** The app leaves the screen: the Home button, standby, another app in front. */
    private fun home() {
        moveTo(Lifecycle.State.CREATED)
        verify { players.last().release() }
    }

    private fun backInFront() = moveTo(Lifecycle.State.RESUMED)

    /** Media3 has the newest player ready and shows its first frame. */
    private fun frame() {
        val listener = listeners.getValue(players.last()).first()
        listener.onPlaybackStateChanged(Player.STATE_READY)
        listener.onRenderedFirstFrame()
        pump()
    }

    /**
     * The view models' work, then the screens' frames, until navigation's fades are over. Out of the front the activity
     * draws nothing (and Compose's test waits for a drawn root): only the main thread's queue runs then.
     */
    private fun pump() {
        repeat(PUMP_FRAMES) {
            scheduler.runCurrent()
            if (inFront) {
                compose.mainClock.advanceTimeBy(FRAME_MS)
                compose.waitForIdle()
            } else {
                ShadowLooper.idleMainLooper()
            }
        }
        scheduler.runCurrent()
    }

    private fun moveTo(state: Lifecycle.State) {
        inFront = state == Lifecycle.State.RESUMED
        compose.activityRule.scenario.moveToState(state)
        pump()
    }

    private inner class Counts(
        val opens: Int = this@ResumeOnceTest.opens,
        val players: Int = this@ResumeOnceTest.players.size,
        val starts: Int = this@ResumeOnceTest.starts.size,
        val details: Int = detailsAsked,
        val screens: Int = this@ResumeOnceTest.screens.size,
        val homes: Int = this@ResumeOnceTest.homes,
    ) {
        /** Since [before]: one open, one player, one start report, no details, the same screen, no Home. */
        fun loadedOnceSince(before: Counts) {
            val way = "opens ${opens - before.opens}, players ${players - before.players}, " +
                "starts ${starts - before.starts}, details ${details - before.details}, " +
                "new screens ${screens - before.screens}, Home ${homes - before.homes}"
            assertEquals("opens asked of the server ($way)", 1, opens - before.opens)
            assertEquals("players prepared ($way)", 1, players - before.players)
            assertEquals("starts reported ($way)", 1, starts - before.starts)
            assertEquals("details asked ($way)", 0, details - before.details)
            assertEquals("new player screens ($way)", 0, screens - before.screens)
            assertEquals("Home opened ($way)", 0, homes - before.homes)
            assertTrue(nav.currentDestination?.hasRoute<PlayerRoute>() == true)
        }
    }

    /** As the app: Home, the title's page, the player; the activity's routing of StartViewModel's screens. */
    @Composable
    private fun App() {
        GlacierTheme {
            val controller = rememberNavController()
            nav = controller
            NavHost(
                navController = controller,
                startDestination = HomeRoute,
                // GlacierNavHost's.
                enterTransition = { fadeIn() + slideInVertically { 14 } },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { fadeOut() },
            ) {
                composable<HomeRoute> {
                    val home = viewModel { HomeStandIn() }
                    LaunchedEffect(home) {
                        home.pending?.let {
                            controller.navigate(if (it.play) PlayerRoute(it.itemId) else DetailRoute(it.itemId))
                        }
                        home.pending = null
                    }
                    Box(Modifier.fillMaxSize())
                }
                composable<DetailRoute> { Box(Modifier.fillMaxSize()) }
                composable<PlayerRoute> {
                    val model = viewModel { player(createSavedStateHandle()) }
                    PlayerScreen(onBack = { controller.popBackStack() }, viewModel = model)
                }
            }
            // MainActivity's collector of StartViewModel.relaunch, as it is.
            LaunchedEffect(controller) {
                start.relaunch.collect { route ->
                    if (route is PlayerRoute) {
                        controller.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                        controller.navigate(route)
                    } else {
                        controller.navigate(route) { popUpTo(0) { inclusive = true } }
                    }
                }
            }
        }
    }

    /** HomeViewModel's part here: it takes the launcher's waiting title as it opens, and plays it. */
    private inner class HomeStandIn : ViewModel() {
        var pending: HomeLaunch? = launches.take(SERVER, USER)

        init {
            homes++
        }
    }

    private fun player(handle: SavedStateHandle): PlayerViewModel {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.settings } returns MutableStateFlow(ProfileSettings())
        coEvery { settings.current() } returns ProfileSettings()
        val serverPreferences = mockk<ServerPreferencesRepository>(relaxed = true)
        every { serverPreferences.preferences } returns MutableStateFlow(ServerPreferences())
        val details = mockk<DetailRepository>(relaxed = true)
        coEvery { details.details(any()) } answers {
            detailsAsked++
            ItemDetails(
                item = film,
                cast = emptyList(),
                tracks = null,
                trailers = Trailers(localCount = 0, youTube = emptyList()),
                seriesId = null,
                seasonId = null,
                premiereDate = null,
                seasonCount = null,
                episodeCount = null,
            )
        }
        coEvery { details.neighbours(any(), any()) } returns EpisodeNeighbours(null, null)
        val skips = mockk<SkipChain>(relaxed = true)
        every { skips.learner() } returns SkipLearner(LearnedSkipStore(null))
        coEvery { skips.withLocalSources(any(), any(), any()) } answers { secondArg() }
        coEvery { skips.lookupOnline(any(), any(), any()) } returns emptyList()
        every { skips.learnedKey(any()) } returns null
        val versions = mockk<VersionChooser>()
        coEvery { versions.choose(any()) } returns null
        return PlayerViewModel(
            handle,
            RuntimeEnvironment.getApplication(),
            details,
            playback,
            TrackSelections(),
            settings,
            serverPreferences,
            mockk(relaxed = true),
            versions,
            mockk(relaxed = true),
            skips,
        ).also { screens += it }
    }

    private fun startViewModel(): StartViewModel {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.settings } returns MutableStateFlow(ProfileSettings())
        val sessions = mockk<SessionManager>(relaxed = true)
        every { sessions.session } returns MutableStateFlow(
            Session(
                server = StoredServer(SERVER, "Test", "http://127.0.0.1:1", version = null, lastUsedAt = 0),
                user = StoredUser(SERVER, USER, "tester", primaryImageTag = null, accessToken = "t", lastUsedAt = 0),
                api = mockk(relaxed = true),
                installDeviceId = "device",
            ),
        )
        val accounts = mockk<AccountRepository>(relaxed = true)
        coEvery { accounts.current() } returns AccountState()
        return StartViewModel(
            accounts = accounts,
            settings = settings,
            parental = mockk(relaxed = true),
            updates = mockk(relaxed = true),
            whatsNew = mockk(relaxed = true),
            music = mockk(relaxed = true),
            home = mockk(relaxed = true),
            sessions = sessions,
            browseMemory = mockk(relaxed = true),
            launches = launches,
            diagnostics = mockk(relaxed = true),
            settingsSync = mockk(relaxed = true),
            nixieCheck = mockk(relaxed = true),
            nixieStartLine = mockk(relaxed = true),
            nixieStart = mockk(relaxed = true),
        )
    }

    private val source = PlaybackSource(
        itemId = filmId,
        mediaSourceId = "file",
        playSessionId = "session",
        url = "http://127.0.0.1:1/none",
        isHls = false,
        method = PlaybackMethod.DirectPlay,
        headers = emptyMap(),
        audioIndex = 1,
        subtitleIndex = 3,
        audioTracks = emptyList(),
        subtitles = emptyList(),
    )

    private val film = MediaItem(
        id = filmId,
        kind = ItemKind.Movie,
        title = "Film",
        sortName = null,
        year = null,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = 120,
        runtimeMs = RUNTIME_MS,
        genres = emptyList(),
        overview = null,
        parentTitle = null,
        seriesId = null,
        seasonNumber = null,
        episodeNumber = null,
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
        resumePositionMs = PLACE_MS,
    )

    private companion object {
        const val SERVER = "server-1"
        val USER = UUID.nameUUIDFromBytes("tester".toByteArray()).toString()

        /** Where the title was paused: 1330 s, as in the log. */
        const val PLACE_MS = 1_330_000L
        const val RUNTIME_MS = 7_200_000L
        const val FRAME_MS = 16L

        /** About 1.3 s of frames: navigation's fades end well within it. */
        const val PUMP_FRAMES = 80
    }
}
