package io.github.glacier_jellyfin.androidtv.setup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.core.jellyfin.SignInResult
import io.github.glacier_jellyfin.androidtv.core.jellyfin.SignedInUser
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.navigation.QuickConnectRoute
import io.github.glacier_jellyfin.androidtv.navigation.ServerListRoute
import io.github.glacier_jellyfin.androidtv.navigation.SignInRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SignInField { Username, Password }

data class SignInState(
    val server: ServerInfo? = null,
    val username: String = "",
    val password: String = "",
    val field: SignInField = SignInField.Username,
    val busy: Boolean = false,
    /** Shows the Quick Connect button; only when the server allows it. */
    val quickConnectAvailable: Boolean = false,
)

@HiltViewModel
class SignInViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accounts: AccountRepository,
    private val authenticator: Authenticator,
    private val sessions: SessionManager,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<SignInRoute>()

    private val _state = MutableStateFlow(
        SignInState(
            username = route.username.orEmpty(),
            field = if (route.username != null) SignInField.Password else SignInField.Username,
        ),
    )
    val state: StateFlow<SignInState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            // Nixie (tv-fix-a): a server removed from the TV meanwhile: say so and go to the server list.
            val stored =
                accounts.current().servers.firstOrNull { it.id == route.serverId } ?: return@launch serverGone()
            val server = ServerInfo(stored.id, stored.name, stored.address, stored.version)
            _state.update { it.copy(server = server) }
            val available = authenticator.isQuickConnectEnabled(server)
            _state.update { it.copy(quickConnectAvailable = available) }
        }
    }

    fun selectField(field: SignInField) = _state.update { it.copy(field = field) }

    fun setText(value: String) = _state.update {
        if (it.field == SignInField.Username) it.copy(username = value) else it.copy(password = value)
    }

    fun openQuickConnect() {
        viewModelScope.launch { _events.send(UiEvent.Navigate(QuickConnectRoute(route.serverId))) }
    }

    /** Design: "Change server" returns to the server list and starts over. */
    fun changeServer() {
        viewModelScope.launch { _events.send(UiEvent.Navigate(ServerListRoute, clearBackStack = true)) }
    }

    fun signIn() {
        val current = _state.value
        if (current.busy) return
        // Nixie (tv-fix-a): never a Sign in that does nothing: no server saved for this page any more.
        val server = current.server ?: run {
            viewModelScope.launch { if (accounts.current().servers.none { it.id == route.serverId }) serverGone() }
            return
        }
        if (current.username.isBlank()) {
            viewModelScope.launch { _events.send(UiEvent.Toast(R.string.signin_username_required)) }
            _state.update { it.copy(field = SignInField.Username) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            when (val result = authenticator.signIn(server, current.username.trim(), current.password)) {
                is SignInResult.Success -> finish(result.user)

                SignInResult.InvalidCredentials -> {
                    _state.update { it.copy(busy = false, password = "", field = SignInField.Password) }
                    _events.send(UiEvent.Toast(R.string.signin_invalid))
                }

                is SignInResult.Failed -> {
                    _state.update { it.copy(busy = false) }
                    _events.send(
                        UiEvent.Toast(
                            R.string.signin_failed,
                            listOf(result.cause.message ?: result.cause.javaClass.simpleName),
                        ),
                    )
                }
            }
        }
    }

    /** Nixie (tv-fix-a): the page's server is no longer on this TV: said, then the server list. */
    private suspend fun serverGone() {
        _events.send(UiEvent.Toast(R.string.nixie_signin_server_gone))
        _events.send(UiEvent.Navigate(ServerListRoute, clearBackStack = true))
    }

    private suspend fun finish(user: SignedInUser) {
        accounts.rememberSignIn(user)
        sessions.open(user.serverId, user.userId.toString())
        _events.send(UiEvent.Navigate(HomeRoute, clearBackStack = true))
    }
}
