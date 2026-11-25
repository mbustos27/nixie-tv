package io.github.glacier_jellyfin.androidtv.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.profilesFor
import io.github.glacier_jellyfin.androidtv.core.jellyfin.DiscoveredServer
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerFinder
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.navigation.ServerListRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.mbustos27.nixie.tv.data.ServerRemoval
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerEntry(
    val server: ServerInfo,
    /** Profiles as "Who's watching?" lists them: the server's public users and those set up on this device; null while unknown. */
    val profileCount: Int? = null,
    val latencyMillis: Long? = null,
    /** False while a saved server does not answer. */
    val reachable: Boolean = true,
)

data class ServerListState(
    val saved: List<ServerEntry> = emptyList(),
    val discovered: List<ServerEntry> = emptyList(),
    /** Saved servers have been read; the screen can place its initial focus. */
    val savedLoaded: Boolean = false,
    val searching: Boolean = true,
    /** Name of the server being connected to, shown in the busy overlay. */
    val connecting: String? = null,
    /** Server older than 12.0 waiting for "connect anyway". */
    val confirmUnsupported: ServerInfo? = null,
    // Nixie (tv-gate-a): a saved server's menu (its card's Menu key or a long OK), and Remove's question.
    val menuFor: ServerEntry? = null,
    val removeAsked: ServerEntry? = null,
    /** Bumped when a removal took a card away: the screen moves the focus only when it was on that card. */
    val refocus: Int = 0,
    /** The server whose card the last removal took away. */
    val removed: String? = null,
    // Nixie (tv-fix-a): the server being removed; "Removing …" shows meanwhile and Menu is ignored.
    val removing: ServerEntry? = null,
)

@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val finder: ServerFinder,
    private val connector: ServerConnector,
    private val reachability: ServerReachability,
    // Nixie (tv-gate-a): removing a saved server through its menu.
    private val removal: ServerRemoval,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerListState())
    val state: StateFlow<ServerListState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch { loadSaved() }
        viewModelScope.launch {
            // Discovery failing (no network, blocked broadcast) just means an empty list.
            finder.discover().catch { }.collect(::onDiscovered)
            _state.update { it.copy(searching = false) }
        }
    }

    private suspend fun loadSaved() {
        val current = accounts.current()
        val saved = current.servers.sortedByDescending { it.lastUsedAt }
        _state.update { state ->
            state.copy(
                saved = saved.map { stored ->
                    ServerEntry(
                        ServerInfo(stored.id, stored.name, stored.address, stored.version),
                        profileCount = current.profilesFor(stored.id, publicUsers = null).size,
                        reachable = reachability.isReachable(stored.id),
                    )
                },
                savedLoaded = true,
            )
        }
        probeSaved()
    }

    /** Checks the saved servers again every [RECHECK_MILLIS] while the list is shown; one that answers again loses its hint. */
    suspend fun watchReachability() {
        while (true) {
            delay(RECHECK_MILLIS)
            probeSaved()
        }
    }

    private suspend fun probeSaved() = coroutineScope {
        _state.value.saved.map { it.server }.forEach { stored ->
            launch {
                val probe = finder.probe(stored.address)
                reachability.report(stored.id, probe != null)
                val accountState = accounts.current()
                _state.update { state ->
                    state.copy(
                        saved = state.saved.map { entry ->
                            if (entry.server.id != stored.id) {
                                entry
                            } else {
                                probe?.toEntry(accountState) ?: entry.copy(reachable = false, latencyMillis = null)
                            }
                        },
                    )
                }
            }
        }
    }

    private fun onDiscovered(found: DiscoveredServer) = _state.update { state ->
        if (state.saved.any { it.server.id == found.server.id } ||
            state.discovered.any { it.server.id == found.server.id }
        ) {
            state
        } else {
            state.copy(discovered = state.discovered + found.toEntry(AccountState()))
        }
    }

    fun select(entry: ServerEntry) {
        if (!entry.reachable) {
            viewModelScope.launch { _events.send(UiEvent.Toast(R.string.setup_unreachable, listOf(entry.server.name))) }
            return
        }
        if (!entry.server.isSupported) {
            _state.update { it.copy(confirmUnsupported = entry.server) }
            return
        }
        connect(entry.server)
    }

    fun confirmUnsupported(connect: Boolean) {
        val server = _state.value.confirmUnsupported ?: return
        _state.update { it.copy(confirmUnsupported = null) }
        if (connect) connect(server)
    }

    private var connectJob: Job? = null

    private fun connect(server: ServerInfo) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _state.update { it.copy(connecting = server.name) }
            val route = connector.connect(server)
            _state.update { it.copy(connecting = null) }
            _events.send(UiEvent.Navigate(route))
        }
    }

    /** Back while connecting: the list stays, as if no server had been picked. */
    fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
        _state.update { it.copy(connecting = null) }
    }

    // Nixie (tv-gate-a): a saved server's menu. Found servers have none; nothing is saved of them to remove.

    /** The Menu key or a long OK on a saved server's card. */
    fun openMenu(entry: ServerEntry) {
        if (_state.value.removing != null || removal.removing.value.isNotEmpty()) return
        if (_state.value.saved.none { it.server.id == entry.server.id }) return
        _state.update { it.copy(menuFor = entry) }
    }

    /** Back on the menu: nothing done. */
    fun closeMenu() = _state.update { it.copy(menuFor = null) }

    /** Remove in the menu: asks once before anything goes. */
    fun askRemove() = _state.update { it.copy(removeAsked = it.menuFor, menuFor = null) }

    /** Cancel or Back on the question: nothing done. */
    fun dismissRemove() = _state.update { it.copy(removeAsked = null) }

    /**
     * Remove confirmed: "Removing …" at once; the server leaves this TV first and its profiles are signed out on the
     * server after (see [ServerRemoval]), in a scope of its own, so Back meanwhile cannot leave it half-removed. The
     * screen learns of the end ([ServerListState.removed]) to send Back past the removed server's pages.
     */
    fun remove() {
        val entry = _state.value.removeAsked ?: return
        if (_state.value.removing != null) return
        _state.update { it.copy(removeAsked = null, removing = entry) }
        val done = removal.start(entry.server.id)
        viewModelScope.launch {
            val removed = runCatching { done.await() }.getOrDefault(false)
            _state.update { state ->
                state.copy(
                    saved = state.saved.filterNot { it.server.id == entry.server.id },
                    refocus = state.refocus + 1,
                    removed = entry.server.id,
                    removing = null,
                )
            }
            if (removed) {
                _events.send(UiEvent.Toast(R.string.nixie_server_removed, listOf(entry.server.name)))
                // Its "Who's watching?" and sign-in pages may be under this list: Back must not lead to them.
                _events.send(UiEvent.Navigate(ServerListRoute, nixieDropServer = entry.server.id))
            }
        }
    }

    /** Public users and the profiles set up here, each counted once; only the local ones when the server did not list its users. */
    private fun DiscoveredServer.toEntry(accountState: AccountState) = ServerEntry(
        server,
        profileCount = accountState.profilesFor(server.id, publicUsers).size,
        latencyMillis = latencyMillis,
    )

    private companion object {
        const val RECHECK_MILLIS = 10_000L
    }
}
