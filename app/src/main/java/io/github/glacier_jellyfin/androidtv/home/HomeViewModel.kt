package io.github.glacier_jellyfin.androidtv.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.channels.HomeLaunches
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.AgeFilter
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeContent
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.HomeSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.navigation.DetailRoute
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.ServerListRoute
import io.github.glacier_jellyfin.androidtv.navigation.navEvent
import io.github.glacier_jellyfin.androidtv.navigation.routeFor
import io.github.glacier_jellyfin.androidtv.parental.PinGate
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.mbustos27.nixie.tv.channels.NixieWatchNext
import io.github.mbustos27.nixie.tv.data.ContinueWatchingRemovals
import io.github.mbustos27.nixie.tv.data.ContinueWatchingServer
import io.github.mbustos27.nixie.tv.data.HomeProgress
import io.github.mbustos27.nixie.tv.data.HomeStart
import io.github.mbustos27.nixie.tv.data.HomeViews
import io.github.mbustos27.nixie.tv.data.LiveUpdates
import io.github.mbustos27.nixie.tv.data.RemovalNotice
import io.github.mbustos27.nixie.tv.data.SavedHome
import io.github.mbustos27.nixie.tv.data.SavedHomePage
import io.github.mbustos27.nixie.tv.data.StartLine
import io.github.mbustos27.nixie.tv.data.UserDataChange
import io.github.mbustos27.nixie.tv.data.localNow
import io.github.mbustos27.nixie.tv.data.withUserData
import io.github.mbustos27.nixie.tv.home.NixieHomeTimes
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val content: HomeContent? = null,
    val userName: String = "",
    val settings: HomeSettings = HomeSettings(),
    // Nixie (tv-home-start): every row is in.
    val nixieSettled: Boolean = false,
    // Nixie (tv-home-two-rows): each row's answer is known (saved or from the server), so an empty one says so.
    val nixieContinueWatchingIn: Boolean = false,
    val nixieUpNextIn: Boolean = false,
    // Nixie (tv-1.0-fixes): no profile is signed in (its server was removed, say): Home asks nothing and gives way.
    val nixieSignedOut: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HomeRepository,
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
    private val parental: ParentalControl,
    private val ageFilter: AgeFilter,
    playback: PlaybackRepository,
    private val details: DetailRepository,
    launches: HomeLaunches,
    // Nixie: Remove from Continue watching, with an Undo (NixieHomeRows).
    continueWatchingServer: ContinueWatchingServer,
    // Nixie: rows follow the server live (LiveUserData).
    private val nixieLive: LiveUpdates,
    // Nixie (tv-watchnext): the home screen's Continue watching follows removals and Undo (NixieWatchNext).
    private val nixieWatchNext: NixieWatchNext,
    // Nixie (tv-home-start): Home from what is saved, its rows as they answer, and the start's timings.
    private val nixieSaved: SavedHome,
    private val nixieStartLine: StartLine,
    private val nixieAccounts: AccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeState(userName = sessions.session.value?.user?.name.orEmpty(), settings = settings.settings.value.home),
    )
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val pin = PinGate(viewModelScope, parental) { _events.send(it) }

    // Nixie: removals from Continue watching; the row as the server last answered, and when it was asked.
    val nixieRemovals = ContinueWatchingRemovals(viewModelScope, continueWatchingServer, profile = {
        sessions.session.value?.let { "${it.server.id}/${it.user.userId}" }
    })
    private var nixieAnswer: Pair<List<MediaItem>, Long> = emptyList<MediaItem>() to 0L

    /** Nixie: the card whose "Remove from Continue watching" sheet is open. */
    val nixieRemoveAsk = MutableStateFlow<MediaItem?>(null)

    // Nixie (tv-home-start): declared before init, which starts the first load.
    private var nixieLoaded = false
    private var nixieRun: Job? = null
    val nixieTimes = NixieHomeTimes(
        nixieStartLine,
        profile = { sessions.session.value?.let { "${it.server.id}/${it.user.userId}" } },
        // The live updates' socket, once Home is drawn: never in the way of its first rows.
        firstDrawn = { nixieLive.live.start() },
    )

    // Nixie (tv-1.0-fixes): the server of the profile Home was for, for "Who's watching?" once nobody is signed in.
    private var nixieServerId: String? = sessions.session.value?.server?.id

    init {
        // Nixie (tv-1.0-fixes): a session that ends under Home (the server removed in the server list above it) stops
        // its loads at once; refreshes after it ask nothing (nixieRows).
        viewModelScope.launch {
            sessions.session.collect { session ->
                if (session == null) nixieSignedOut() else nixieServerId = session.server.id
            }
        }
        // A title picked on the Android TV home screen opens on top of Home.
        sessions.session.value?.let { launches.take(it.server.id, it.user.userId) }?.let { launch ->
            viewModelScope.launch {
                // Nixie (tv-watchnext): an entry of the home screen's Continue watching plays from where it was left.
                if (launch.play && nixiePlayLaunch(launch.itemId)) return@launch
                _events.send(UiEvent.Navigate(DetailRoute(launch.itemId)))
            }
        }
        load()
        // Back from the player: "continue watching" and progress bars follow what was just watched.
        viewModelScope.launch { playback.stopped.collect { refresh() } }
        // Marked watched or unwatched on a detail page.
        viewModelScope.launch { details.playedChanged.collect { refresh() } }
        // Nixie (tv-home-two-rows): Home is Continue watching and Up next only; no favorites, Live TV or hero follow.
        nixieFollowRemovals() // Nixie: Undo cards come and go; notices as toasts.
    }

    /** Reloads in the background: no spinner, and what is on screen stays if it fails. */
    private fun refresh() {
        // Nixie (tv-home-start): row by row over what is on screen.
        nixieRows(retry = false)
    }

    /**
     * Nixie (tv-home-two-rows): the top bar's Home came back to this Home (nixieBackToHome): what it shows stays, with no
     * spinner, and Continue watching and Up next are asked again behind it, nothing else. A Home still loading goes on.
     */
    fun nixieBackHere() {
        if (_state.value.content == null) return
        nixieRows(retry = false, topBar = false)
    }

    fun load() {
        // Nixie (tv-home-start): what is saved first, then each row as it answers (nixieRows); Retry shows the spinner.
        val retry = nixieLoaded
        nixieLoaded = true
        if (retry) _state.update { it.copy(loading = true, failed = false) }
        nixieRows(retry = retry)
    }

    /** Starts at once, so the age limit is checked here rather than on a detail page. */
    fun play(item: MediaItem) = pin.openTitle(
        ageFilter,
        id = item.id,
        name = item.title,
        rating = item.officialRating,
        parents = listOfNotNull(item.seriesId),
    ) {
        if (item.kind != ItemKind.Series) {
            navigate(PlayerRoute(item.id.toString()))
            return@openTitle
        }
        // A show has no file of its own: its next episode plays, as on its page. With none
        // (all watched, say), the page opens to pick one.
        viewModelScope.launch {
            val next = runSuspendCatching { details.nextEpisode(item.id) }.getOrNull()
            navigate(if (next != null) PlayerRoute(next.id.toString()) else DetailRoute(item.id.toString()))
        }
    }

    fun openDetails(item: MediaItem) = navigate(routeFor(item))

    /** Design: episodes open their episode page, movies start playing. */
    fun openContinueWatching(item: MediaItem) = if (item.kind == ItemKind.Episode) openDetails(item) else play(item)

    fun onNav(target: NavTarget) {
        val event = sessions.navEvent(target, current = NavTarget.Home) ?: return
        viewModelScope.launch { _events.send(event) }
    }

    private fun navigate(route: Any) {
        viewModelScope.launch { _events.send(UiEvent.Navigate(route)) }
    }

    // ---- Nixie (tv-home-start): Home from what is saved, then each row as it answers (HomeStart, SavedHome) ----

    /**
     * One load of Home's rows: Home as it last showed comes first (saved on the device, or in memory when Home opens
     * again), then each row replaces its part as it answers; with nothing saved, Home draws once its first row is in
     * ([HomeStart.drawable]). Nixie (tv-home-two-rows): Continue watching and Up next only, then the top bar's
     * libraries; the hero shows the focused card and asks for nothing. A newer load (a refresh, a Retry) ends this one.
     * [retry]: the user asked again after a failure, so nothing saved stands in.
     */
    private fun nixieRows(retry: Boolean, topBar: Boolean = true) {
        val session = sessions.session.value ?: run {
            nixieSignedOut() // Nixie (tv-1.0-fixes): was "failed", with a Retry that could only fail again
            return
        }
        nixieRun?.cancel()
        nixieRun = viewModelScope.launch {
            nixieTimes.begin()
            val profileSettings = settings.current()
            val savedKey = nixieSavedKey(session.server.id, session.user.userId)
            _state.update { it.copy(settings = profileSettings.home, nixieSettled = false) }
            if (_state.value.content == null && !retry) nixieShowSaved(savedKey)
            val asked = nixieRemovals.stamp()
            val previous = _state.value.content?.copy(continueWatching = nixieAnswer.first)
            var last: HomeProgress? = null
            HomeStart.rows(
                repository,
                previous,
                profileSettings.nixieHome,
                localNow(),
                timing = { part, ms, ok -> Log.d(TAG, "Home's $part: ${if (ok) "in" else "failed"} after $ms ms") },
                topBar = topBar,
            ).collect { p ->
                last = p
                nixieAnswer = p.content.continueWatching to asked
                nixieDraw(p)
                // Both rows in: Home is settled (the top bar's libraries may still come, after them).
                if (p.settled && !p.failed && !_state.value.nixieSettled) _state.update { it.copy(nixieSettled = true) }
            }
            val done = last ?: return@launch
            if (done.failed) return@launch
            val page = SavedHomePage(done.content.copy(continueWatching = nixieAnswer.first), repository.liveTv.value)
            withContext(Dispatchers.IO) { nixieSaved.write(savedKey, page) }
        }
    }

    /**
     * Nixie (tv-1.0-fixes): nobody is signed in. The owner's TV log: after the signed-in profile's server was removed,
     * Home asked for Continue watching, Up next and the top bar's libraries with no profile, three warnings "No profile
     * is signed in". A load under way ends, none starts, and the screen gives way ([nixieLeave]).
     */
    private fun nixieSignedOut() {
        nixieRun?.cancel()
        nixieRun = null
        _state.update { it.copy(loading = false, failed = false, nixieSignedOut = true) }
    }

    /**
     * Nixie (tv-1.0-fixes): Home shown with nobody signed in gives way to "Who's watching?" of the server it was for, or
     * to the server list when that server is no longer on the TV; the back stack goes with it.
     */
    fun nixieLeave() {
        viewModelScope.launch {
            val serverId = nixieServerId
            val route = if (serverId != null && nixieAccounts.current().servers.any { it.id == serverId }) {
                ProfilesRoute(serverId)
            } else {
                ServerListRoute
            }
            _events.send(UiEvent.Navigate(route, clearBackStack = true))
        }
    }

    /** Home's rows as far as they came: drawn once [HomeProgress.drawable], else the spinner stays. */
    private fun nixieDraw(p: HomeProgress) {
        val (answer, asked) = nixieAnswer
        val content = p.content.copy(continueWatching = nixieRemovals.visible(answer, asked))
        _state.update { state ->
            // A row the server answered is known, as is one the saved page showed.
            val known = state.copy(
                nixieContinueWatchingIn = state.nixieContinueWatchingIn || p.continueWatchingIn,
                nixieUpNextIn = state.nixieUpNextIn || p.upNextIn,
            )
            when {
                // Something is on screen already (saved, or an earlier load): each answer replaces its row.
                state.content != null -> if (p.failed) state else known.copy(content = content, loading = false)

                p.failed -> state.copy(loading = false, failed = true)

                p.drawable -> known.copy(content = content, loading = false, failed = false)

                else -> state
            }
        }
    }

    /** Home as it last showed for this profile, at once; the rows' answers replace it. */
    private suspend fun nixieShowSaved(key: String) {
        val page = withContext(Dispatchers.IO) { nixieSaved.read(key) } ?: return
        if (_state.value.content != null) return
        repository.seed(HomeViews(page.content.libraries, liveTv = page.liveTv))
        // Asked before any removal of this session: those stay out.
        nixieAnswer = page.content.continueWatching to 0L
        _state.update {
            it.copy(
                loading = false,
                content = page.content.copy(
                    continueWatching = nixieRemovals.visible(page.content.continueWatching, 0L),
                ),
                // Both rows are on the saved page, empty or not.
                nixieContinueWatchingIn = true,
                nixieUpNextIn = true,
            )
        }
        nixieStartLine.step("Home: drawn from what was saved", nixieTimes.sinceBegan())
    }

    /** The saved page's key: the profile, under its age limits (a page saved under others is not used). */
    private suspend fun nixieSavedKey(serverId: String, userId: String): String {
        val user = nixieAccounts.current().users.firstOrNull { it.serverId == serverId && it.userId == userId }
        return user?.let(SavedHome::key) ?: SavedHome.key(serverId, userId, "unknown")
    }

    // ---- Nixie: Remove from Continue watching, with an Undo (ContinueWatchingRemovals) ----

    private fun nixieFollowRemovals() {
        // tv-home-start: the socket starts once Home is drawn (NixieHomeTimes); its changes are followed from now.
        viewModelScope.launch { nixieLive.live.changes.collect { nixieApply(it) } }
        viewModelScope.launch {
            nixieRemovals.changes.collect {
                val (answer, asked) = nixieAnswer
                _state.update { state ->
                    state.copy(content = state.content?.copy(continueWatching = nixieRemovals.visible(answer, asked)))
                }
            }
        }
        viewModelScope.launch { nixieRemovals.undone.collect { refresh() } }
        // Nixie (tv-watchnext): the home screen row comes back only for an Undo the server took, unless removed again.
        viewModelScope.launch {
            nixieRemovals.putBack.collect { if (it !in nixieRemovals.pending.value) nixieWatchNext.restored(it) }
        }
        viewModelScope.launch {
            nixieRemovals.notices.collect { notice ->
                val text = when (notice) {
                    RemovalNotice.NotRemoved -> R.string.nixie_cw_not_removed
                    RemovalNotice.ChangedSince -> R.string.nixie_cw_changed_since
                    RemovalNotice.NotPutBack -> R.string.nixie_cw_not_put_back
                }
                _events.send(UiEvent.Toast(text))
            }
        }
    }

    /** A change the server reported: the cards follow in place; what cannot, comes with a quiet reload. */
    private fun nixieApply(change: UserDataChange) {
        nixieRemovals.onUserData(change)
        val (answer, asked) = nixieAnswer
        val raw = HomeContent(libraries = emptyList(), continueWatching = answer, latest = emptyList())
        nixieAnswer = raw.withUserData(change).content.continueWatching to asked
        var reload = false
        _state.update { state ->
            val content = state.content ?: return@update state
            val patch = content.withUserData(change)
            reload = patch.reload
            val continueWatching = nixieRemovals.visible(patch.content.continueWatching, asked)
            state.copy(content = patch.content.copy(continueWatching = continueWatching))
        }
        if (reload) nixieReloadSoon()
    }

    private var nixieReload: Job? = null
    private var nixieReloadedAt = 0L

    /** One quiet reload for a burst of changes, at most one every [NIXIE_RELOAD_GAP_MS]. */
    private fun nixieReloadSoon() {
        if (nixieReload?.isActive == true) return
        nixieReload = viewModelScope.launch {
            val since = System.currentTimeMillis() - nixieReloadedAt
            delay(maxOf(NIXIE_RELOAD_DELAY_MS, NIXIE_RELOAD_GAP_MS - since))
            nixieReloadedAt = System.currentTimeMillis()
            refresh()
        }
    }

    /** Holding OK on a Continue watching card opens its Remove sheet; null closes it. */
    fun nixieAskRemove(item: MediaItem?) {
        nixieRemoveAsk.value = item
    }

    fun nixieConfirmRemove() {
        val item = nixieRemoveAsk.value ?: return
        nixieRemoveAsk.value = null
        val index = _state.value.content?.continueWatching?.indexOfFirst { it.id == item.id } ?: return
        if (index >= 0) {
            nixieRemovals.remove(item, index)
            nixieWatchNext.removed(item.id) // Nixie (tv-watchnext): its home screen row goes too
        }
    }

    /** Nixie (tv-watchnext): plays a title picked in the home screen's Continue watching, through the PIN gate; false when it cannot be found. */
    private suspend fun nixiePlayLaunch(itemId: String): Boolean {
        val id = runCatching { UUID.fromString(itemId) }.getOrNull() ?: return false
        val item = runSuspendCatching { details.details(id).item }
            .onFailure { Log.w(TAG, "Opening a home screen title failed", it) }
            .getOrNull() ?: return false
        play(item)
        return true
    }

    private companion object {
        const val TAG = "Home"
        const val NIXIE_RELOAD_DELAY_MS = 2_000L // Nixie
        const val NIXIE_RELOAD_GAP_MS = 10_000L // Nixie
    }
}
