package io.github.glacier_jellyfin.androidtv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.channels.HomeLaunch
import io.github.glacier_jellyfin.androidtv.channels.HomeLaunches
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.settings.AppearanceSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.NavigationSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsSync
import io.github.glacier_jellyfin.androidtv.core.data.settings.UiLanguage
import io.github.glacier_jellyfin.androidtv.core.data.startServerId
import io.github.glacier_jellyfin.androidtv.core.designsystem.Accent
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.core.designsystem.NavDirection
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateManager
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateState
import io.github.glacier_jellyfin.androidtv.core.updater.WhatsNew
import io.github.glacier_jellyfin.androidtv.core.updater.pending
import io.github.glacier_jellyfin.androidtv.diagnostics.Diagnostics
import io.github.glacier_jellyfin.androidtv.diagnostics.LogServer
import io.github.glacier_jellyfin.androidtv.diagnostics.LogShare
import io.github.glacier_jellyfin.androidtv.diagnostics.LogShareScreen
import io.github.glacier_jellyfin.androidtv.library.BrowseMemory
import io.github.glacier_jellyfin.androidtv.library.FolderMemory
import io.github.glacier_jellyfin.androidtv.music.LocalMusicController
import io.github.glacier_jellyfin.androidtv.music.LocalMusicProgress
import io.github.glacier_jellyfin.androidtv.music.LocalNowPlaying
import io.github.glacier_jellyfin.androidtv.music.MusicController
import io.github.glacier_jellyfin.androidtv.music.NowPlaying
import io.github.glacier_jellyfin.androidtv.music.NowPlayingSaver
import io.github.glacier_jellyfin.androidtv.music.SAVER_IDLE_MS
import io.github.glacier_jellyfin.androidtv.navigation.GlacierNavHost
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.navigation.LocalLibraryKinds
import io.github.glacier_jellyfin.androidtv.navigation.LocalLiveTv
import io.github.glacier_jellyfin.androidtv.navigation.LocalNavTargets
import io.github.glacier_jellyfin.androidtv.navigation.LocalProfileImage
import io.github.glacier_jellyfin.androidtv.navigation.LocalUpdatePending
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.ServerListRoute
import io.github.glacier_jellyfin.androidtv.navigation.navTargets
import io.github.glacier_jellyfin.androidtv.parental.LocalUnlockedTitles
import io.github.glacier_jellyfin.androidtv.settings.UiLocale
import io.github.glacier_jellyfin.androidtv.ui.CardSizes
import io.github.glacier_jellyfin.androidtv.ui.LocalCardSizes
import io.github.glacier_jellyfin.androidtv.ui.LocalToaster
import io.github.glacier_jellyfin.androidtv.ui.ToastHost
import io.github.glacier_jellyfin.androidtv.ui.Toaster
import io.github.glacier_jellyfin.androidtv.update.DownloadingOverlay
import io.github.glacier_jellyfin.androidtv.update.InstallingOverlay
import io.github.glacier_jellyfin.androidtv.update.text
import io.github.mbustos27.nixie.tv.NixieThemes
import io.github.mbustos27.nixie.tv.data.StartLine
import io.github.mbustos27.nixie.tv.diagnostics.NixieCrashLogQuestion
import io.github.mbustos27.nixie.tv.play.PlayerReturn
import io.github.mbustos27.nixie.tv.start.StartCheck
import io.github.mbustos27.nixie.tv.start.StartFromSaved
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.imageApi

/** Picks the first screen: "Who's watching?" (or the start profile) for the start server, otherwise setup. */
@HiltViewModel
class StartViewModel @Inject constructor(
    accounts: AccountRepository,
    settings: SettingsRepository,
    parental: ParentalControl,
    val updates: UpdateManager,
    private val whatsNew: WhatsNew,
    val music: MusicController,
    home: HomeRepository,
    private val sessions: SessionManager,
    private val browseMemory: BrowseMemory,
    private val launches: HomeLaunches,
    private val diagnostics: Diagnostics,
    settingsSync: SettingsSync,
    // Nixie (tv-home-start): the checks after a start from what is saved may send the app to sign-in; the timings.
    nixieCheck: StartCheck,
    private val nixieStartLine: StartLine,
    private val nixieStart: StartFromSaved,
) : ViewModel() {
    /** Where "Fotos & Videos" was left, for the way back into it. */
    fun folderMemory(): FolderMemory = browseMemory.folders()

    /** Library kinds of the server, for every navigation bar. */
    val libraryKinds: StateFlow<List<LibraryKind>> = home.kinds

    /** Whether the server offers Live TV, for every navigation bar. */
    val liveTv: StateFlow<Boolean> = home.liveTv

    /** The signed-in profile's look; defaults on the setup and profile screens. */
    val appearance: StateFlow<AppearanceSettings> = settings.settings
        .map { it.appearance }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.settings.value.appearance)

    val navigation: StateFlow<NavigationSettings> = settings.settings
        .map { it.navigation }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.settings.value.navigation)

    /** The signed-in profile's picture for the navigation bar; null when it has none. */
    val profileImage: StateFlow<String?> = sessions.session
        .map { session ->
            val tag = session?.user?.primaryImageTag ?: return@map null
            runCatching {
                session.api.imageApi.getUserImageUrl(UUID.fromString(session.user.userId), tag = tag)
            }.getOrNull()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The song for the mini player. */
    val nowPlaying: StateFlow<NowPlaying?> = music.nowPlaying

    /** Titles the PIN unlocked in this session; their cards drop the lock. */
    val unlockedTitles: StateFlow<Set<String>> = parental.unlockedItems

    /** The signed-in profile's interface language; null while no one is signed in, which keeps the last one. */
    val uiLanguage: Flow<UiLanguage?> = settings.active.map { it?.uiLanguage }

    private val _start = MutableStateFlow<Any?>(null)
    val start = _start.asStateFlow()

    private val _relaunch = Channel<Any>(Channel.CONFLATED)

    /** Screens to open from scratch while the app runs: a title was picked on the home screen. */
    val relaunch: Flow<Any> = _relaunch.receiveAsFlow()

    private val _crashShare = MutableStateFlow<LogShare?>(null)

    /** After a crash, the log offered for download before the first screen opens. */
    val crashShare = _crashShare.asStateFlow()

    private var crashServer: LogServer? = null

    // Nixie (integration 1): after a crash, the question first (when the app closed); nothing is served before Yes.
    private val _crashAsk = MutableStateFlow<Long?>(null)

    /** After a crash, the question whether to offer the log on the network, with when the app closed (epoch ms). */
    val crashAsk = _crashAsk.asStateFlow()

    private var crashSharing: Job? = null

    /** Shown once after a crash, when the log could not be offered on the network. */
    private val _crashNotice = Channel<Unit>(Channel.CONFLATED)
    val crashNotice: Flow<Unit> = _crashNotice.receiveAsFlow()

    init {
        updates.start()
        whatsNew.start()
        settingsSync.start()
        viewModelScope.launch {
            val began = nixieStartLine.now() // Nixie (tv-home-start)
            offerCrashLog()
            val server = accounts.current().startServerId()
            // Nixie (tv-home-start): the start profile opens from what is saved, and Home is the first screen.
            val saved = (launches.pending?.serverId ?: server)?.let { id ->
                HomeRoute.takeIf { nixieStart.open(id, launches.pending?.userId) }
            }
            val first = launches.pending?.let { ProfilesRoute(it.serverId, appStart = true, userId = it.userId) }
                ?: server?.let { ProfilesRoute(it, appStart = true) }
                ?: ServerListRoute
            _start.value = saved ?: first // Nixie (tv-home-start)
            nixieStartLine.step("Start: first screen chosen", nixieStartLine.now() - began) // Nixie (tv-home-start)
        }
        viewModelScope.launch { nixieCheck.redirects.collect { _relaunch.trySend(it) } } // Nixie (tv-home-start)
    }

    /** A title picked on the home screen while the app runs: Home opens it, through the profile picker for another profile. */
    fun open(launch: HomeLaunch) {
        // Nixie (TV-S12): a card for the title the player screen holds: the screen brings it back, opened once.
        if (PlayerReturn.app.keeps(launch, sessions.session.value)) return
        launches.set(launch)
        val session = sessions.session.value
        val same = session != null && session.server.id == launch.serverId && session.user.userId == launch.userId
        _relaunch.trySend(
            if (same) HomeRoute else ProfilesRoute(launch.serverId, appStart = true, userId = launch.userId),
        )
    }

    /** Debug builds: plays a title straight away, from [startMs], for tests that cannot press buttons reliably. */
    fun play(itemId: String, startMs: Long) {
        _relaunch.trySend(PlayerRoute(itemId, startMs = startMs))
    }

    fun closeCrashShare() {
        crashServer?.close()
        crashServer = null
        _crashShare.value = null
    }

    override fun onCleared() {
        crashServer?.close()
    }

    /** Nixie (integration 1): the crash question's answer; Yes offers the log on the local network, No nothing. */
    fun answerCrashLog(yes: Boolean) {
        val crashedAt = _crashAsk.value ?: return
        // A Yes is being answered: the page stays until the log is offered.
        if (crashSharing?.isActive == true) return
        if (!yes) {
            _crashAsk.value = null
            return
        }
        crashSharing = viewModelScope.launch {
            try {
                shareCrashLog(crashedAt)
            } finally {
                _crashAsk.value = null
            }
        }
    }

    /** After a crash, the question first (Nixie, integration 1): [answerCrashLog] offers the log. */
    private suspend fun offerCrashLog() {
        val crashedAt = withContext(Dispatchers.IO) { if (Log.takeCrashNotice()) Log.lastCrash() else null } ?: return
        _crashAsk.value = crashedAt
    }

    /** After a crash, the log for a bug report; without a network, a notice pointing to Settings › System. */
    private suspend fun shareCrashLog(crashedAt: Long) {
        val server = try {
            diagnostics.shareOnNetwork()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Offering the log after a crash failed", e)
            null
        }
        if (server == null) {
            _crashNotice.trySend(Unit)
            return
        }
        crashServer = server
        _crashShare.value = LogShare(server.url, server.fileName, server.sizeBytes, crashedAt)
    }

    private companion object {
        const val TAG = "Start"
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val startViewModel: StartViewModel by viewModels()

    @Inject
    lateinit var launches: HomeLaunches

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        StartLine.mark("Start: the activity begins") // Nixie (tv-home-start): the cold start's split
        // After Android ended the process in the background, the saved screens would open without a signed-in profile.
        // The app starts over instead, through the profile screen like on a normal launch.
        super.onCreate(savedInstanceState.takeIf { processAlive })
        processAlive = true
        // Before the start screen is chosen; a recreated activity has already handled it.
        if (savedInstanceState == null) HomeLaunch.from(intent)?.let(launches::set)
        // Nixie: debug builds switch themes with --es nixie.theme <id>.
        if (savedInstanceState == null) NixieThemes.fromIntent(this, intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                startViewModel.uiLanguage.filterNotNull().collect { language ->
                    if (language.tag != UiLocale.current(this@MainActivity)) {
                        UiLocale.set(this@MainActivity, language.tag)
                        recreate()
                    }
                }
            }
        }
        setContent {
            val appearance by startViewModel.appearance.collectAsStateWithLifecycle()
            val unlockedTitles by startViewModel.unlockedTitles.collectAsStateWithLifecycle()
            val nowPlaying by startViewModel.nowPlaying.collectAsStateWithLifecycle()
            val profileImage by startViewModel.profileImage.collectAsStateWithLifecycle()
            val libraryKinds by startViewModel.libraryKinds.collectAsStateWithLifecycle()
            val liveTv by startViewModel.liveTv.collectAsStateWithLifecycle()
            val navigation by startViewModel.navigation.collectAsStateWithLifecycle()
            val update by startViewModel.updates.state.collectAsStateWithLifecycle()
            GlacierTheme(accent = Accent.valueOf(appearance.accent.name), reduceMotion = appearance.reduceMotion) {
                val toaster = remember { Toaster() }
                CompositionLocalProvider(
                    LocalToaster provides toaster,
                    LocalCardSizes provides if (appearance.compact) CardSizes.Compact else CardSizes.Comfortable,
                    LocalUnlockedTitles provides unlockedTitles,
                    LocalNowPlaying provides nowPlaying,
                    LocalProfileImage provides profileImage,
                    LocalLibraryKinds provides libraryKinds,
                    LocalLiveTv provides liveTv,
                    LocalNavTargets provides navTargets(navigation, libraryKinds, liveTv),
                    LocalUpdatePending provides update.pending,
                    LocalMusicProgress provides startViewModel.music.progress,
                    LocalMusicController provides startViewModel.music,
                ) {
                    // While music plays the screen stays on; idle, the now-playing saver takes over.
                    val musicPlaying = nowPlaying?.playing == true
                    // A music video on the player screen is watched, like a film: no saver over it.
                    val videoVisible by startViewModel.music.videoVisible.collectAsStateWithLifecycle()
                    val view = LocalView.current
                    DisposableEffect(view, musicPlaying) {
                        view.keepScreenOn = musicPlaying
                        onDispose { view.keepScreenOn = false }
                    }
                    var lastInput by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
                    var saverOn by remember { mutableStateOf(false) }
                    var swallowKeyUp by remember { mutableStateOf(false) }
                    // When the Back that ends the saver came, so that its key events and dispatcher Back do nothing else.
                    var saverBackAt by remember { mutableLongStateOf(0L) }
                    val backGuard = remember {
                        BackGuard(onBackPressedDispatcher) {
                            saverBackAt = SystemClock.uptimeMillis()
                            saverOn = false
                        }
                    }
                    // While the saver shows, and a moment after, Back is the saver's: the pages' handlers never see it.
                    LaunchedEffect(saverOn) { if (saverOn) backGuard.raise() else backGuard.lapseSoon() }
                    DisposableEffect(backGuard) { onDispose { backGuard.release() } }
                    LaunchedEffect(musicPlaying, videoVisible, lastInput) {
                        if (!musicPlaying || videoVisible) {
                            saverOn = false
                            return@LaunchedEffect
                        }
                        delay(SAVER_IDLE_MS - (SystemClock.uptimeMillis() - lastInput))
                        saverOn = true
                    }
                    GlacierBackground(
                        Modifier.onPreviewKeyEvent { event ->
                            lastInput = SystemClock.uptimeMillis()
                            // The key that ends the saver does nothing else; media keys still reach the music.
                            if (saverOn && event.type == KeyEventType.KeyDown && !startViewModel.music.ownsKey(
                                    event.nativeKeyEvent.keyCode,
                                )
                            ) {
                                saverOn = false
                                swallowKeyUp = true
                                if (event.key == Key.Back) saverBackAt = SystemClock.uptimeMillis()
                                return@onPreviewKeyEvent true
                            }
                            // Newer Android may hand the dispatcher this Back before its key: the key then belongs to the saver as well.
                            if (event.key == Key.Back && SystemClock.uptimeMillis() - saverBackAt < BACK_GUARD_MS) {
                                swallowKeyUp = false
                                return@onPreviewKeyEvent true
                            }
                            if (swallowKeyUp && event.type == KeyEventType.KeyUp) {
                                swallowKeyUp = false
                                return@onPreviewKeyEvent true
                            }
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionUp, Key.DirectionDown -> NavDirection.vertical = true
                                    Key.DirectionLeft, Key.DirectionRight -> NavDirection.vertical = false
                                }
                            }
                            // Nothing reacts to keys while an update downloads for the installation or Android installs it.
                            // Back still reaches the download dialog, which cancels the download (newer Android sends no Back key events at all).
                            if ((update as? UpdateState.Downloading)?.install == true) {
                                return@onPreviewKeyEvent event.key != Key.Back
                            }
                            update is UpdateState.Installing
                        }.onKeyEvent { event ->
                            // Media keys no screen used go to the music, wherever the user is.
                            val code = event.nativeKeyEvent.keyCode
                            when (event.type) {
                                KeyEventType.KeyDown -> startViewModel.music.onMediaKey(code)
                                KeyEventType.KeyUp -> startViewModel.music.ownsKey(code)
                                else -> false
                            }
                        },
                    ) {
                        val start by startViewModel.start.collectAsStateWithLifecycle()
                        val crashShare by startViewModel.crashShare.collectAsStateWithLifecycle()
                        val crashAsk by startViewModel.crashAsk.collectAsStateWithLifecycle() // Nixie (integration 1)
                        // The first screen waits for the crash page, so it cannot take the focus from it.
                        if (crashShare == null && crashAsk == null) { // Nixie (integration 1): or the question
                            start?.let {
                                val navController = rememberNavController()
                                GlacierNavHost(
                                    navController,
                                    startDestination = it,
                                    folderMemory = startViewModel::folderMemory,
                                )
                                LaunchedEffect(navController) {
                                    startViewModel.relaunch.collect { route ->
                                        // A title played straight away (debug builds) gets Home beneath it, so Back has a screen to return to.
                                        if (route is PlayerRoute) {
                                            navController.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                                            navController.navigate(route)
                                        } else {
                                            navController.navigate(route) { popUpTo(0) { inclusive = true } }
                                        }
                                    }
                                }
                            }
                        }
                        UpdateLayer(startViewModel.updates, toaster)
                        crashShare?.let { LogShareScreen(it, onClose = startViewModel::closeCrashShare) }
                        // Nixie (integration 1): after a crash, asked before the log goes on the network.
                        crashAsk?.takeIf { crashShare == null }?.let {
                            NixieCrashLogQuestion(it, onAnswer = startViewModel::answerCrashLog)
                        }
                        CrashNotice(startViewModel.crashNotice, toaster)
                        ToastHost(toaster)
                        NowPlayingSaver(saverOn, nowPlaying, startViewModel.music.state, startViewModel.music.progress)
                    }
                }
            }
        }
        // Nixie (tv-home-start): the cold start's split, up to the first frame drawn.
        StartLine.mark("Start: the activity's content set")
        androidx.core.view.OneShotPreDrawListener.add(window.decorView) { StartLine.mark("Start: the first frame") }
    }

    override fun onResume() {
        super.onResume()
        startViewModel.updates.onAppResumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        HomeLaunch.from(intent)?.let(startViewModel::open)
        // Nixie: debug builds switch themes with --es nixie.theme <id>.
        NixieThemes.fromIntent(this, intent)
        // adb shell am start -n <package>/...MainActivity -a io.github.glacier_jellyfin.androidtv.PLAY_TITLE --es item <id> --el startms <ms>
        if (BuildConfig.DEBUG && intent.action == PLAY_ACTION) {
            intent.getStringExtra("item")?.let { startViewModel.play(it, intent.getLongExtra("startms", 0)) }
        }
    }

    /**
     * A Back callback for the saver: [onBack] ends it and nothing else sees that Back. It is added fresh each time, since the
     * dispatcher asks the newest callback first and a page opened later would otherwise take Back before a fixed one.
     */
    private class BackGuard(private val dispatcher: OnBackPressedDispatcher, private val onBack: () -> Unit) {
        private val handler = Handler(Looper.getMainLooper())
        private val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        }
        private val expire = Runnable { callback.remove() }

        fun raise() {
            handler.removeCallbacks(expire)
            callback.remove()
            dispatcher.addCallback(callback)
        }

        /** Older Android sends only the key and never calls it, so it lapses on its own; the late Back of a press still finds it. */
        fun lapseSoon() {
            handler.removeCallbacks(expire)
            handler.postDelayed(expire, BACK_GUARD_MS)
        }

        fun release() {
            handler.removeCallbacks(expire)
            callback.remove()
        }
    }

    private companion object {
        const val BACK_GUARD_MS = 600L

        /** False until the first activity of this process is created, so a restore after the process ended can be told apart. */
        var processAlive = false

        const val PLAY_ACTION = "io.github.glacier_jellyfin.androidtv.PLAY_TITLE"
    }
}

/** What the updater shows on any screen: its notices as toasts, the download before an installation and the overlay while Android installs. */
@Composable
private fun UpdateLayer(updates: UpdateManager, toaster: Toaster) {
    val context = LocalContext.current
    LaunchedEffect(updates) { updates.notices.collect { toaster.show(it.text(context)) } }
    val state by updates.state.collectAsStateWithLifecycle()
    when (val update = state) {
        is UpdateState.Downloading -> if (update.install) {
            DownloadingOverlay(
                update.candidate.version,
                update.bytes,
                update.candidate.apk.sizeBytes,
                onCancel = updates::cancelDownload,
            )
        }

        is UpdateState.Installing -> InstallingOverlay(update.candidate.version)

        else -> Unit
    }
}

/** After a crash without a network for the log page, points to sending the log (Settings › System › Diagnostics). */
@Composable
private fun CrashNotice(notices: Flow<Unit>, toaster: Toaster) {
    val resources = LocalResources.current
    LaunchedEffect(notices) { notices.collect { toaster.show(resources.getString(R.string.diag_crash_notice)) } }
}
