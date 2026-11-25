// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.UserItemDataDto

/** What the server says changed in one item's user data (its `UserDataChanged` message). */
data class UserDataChange(
    val itemId: UUID,
    val played: Boolean,
    val positionTicks: Long,
    val playedPercentage: Double?,
    val isFavorite: Boolean,
    val unplayedItemCount: Int?,
    val lastPlayed: LocalDateTime?,
)

internal fun UserItemDataDto.toChange(): UserDataChange? = UserDataChange(
    itemId = itemId ?: return null,
    played = played,
    positionTicks = playbackPositionTicks,
    playedPercentage = playedPercentage,
    isFavorite = isFavorite,
    unplayedItemCount = unplayedItemCount,
    lastPlayed = lastPlayedDate,
)

/** What one connection of the server's websocket delivers. */
sealed interface SocketEvent {
    /** The connection is up. */
    data object Opened : SocketEvent

    /** User data of [userId] changed. */
    data class Changed(val userId: UUID, val changes: List<UserDataChange>) : SocketEvent
}

/** The server refused the handshake: the token is no good. */
class SocketRefused(message: String) : Exception(message)

/**
 * One connection to the server's websocket for [Session]: a cold flow that connects when
 * collected, completes when the server closes, fails when the connection breaks (with
 * [SocketRefused] when the server will not take the token), and closes when cancelled.
 */
fun interface SocketLink {
    fun open(session: Session): Flow<SocketEvent>
}

/**
 * Wait before the next try: [MIN_MS], doubling to [MAX_MS]; back to the start once connected. Nixie (tv-player-4k,
 * TV-S8): up to five minutes, where a minute had the box redial all night when the server could not be found.
 */
class Backoff(private val min: Long = MIN_MS, private val max: Long = MAX_MS) {
    private var next = min

    fun next(): Long = next.also { next = (next * 2).coerceAtMost(max) }

    fun reset() {
        next = min
    }

    companion object {
        const val MIN_MS = 1_000L
        const val MAX_MS = 300_000L
    }
}

/** Where the live updates stand. */
sealed interface LiveState {
    /** No one is signed in. */
    data object Off : LiveState

    data object Connecting : LiveState

    data object Live : LiveState

    /** The connection dropped; the next try is in [waitMs]. */
    data class Waiting(val waitMs: Long) : LiveState

    /** The server refused the token: no more tries for this sign-in. */
    data object Stopped : LiveState

    /** Nixie (TV-S8): the app is in the background: no connection and no tries until it comes back. */
    data object Paused : LiveState
}

/**
 * Nixie: Home's rows follow the server live (desktop Nixie 1.2.0). The server's websocket says
 * when the signed-in profile's progress, watched mark or favourite changes, here or on another
 * device; [changes] hands each change on. The socket reconnects with [Backoff] when it drops,
 * stops when the profile signs out (or another opens), and stops for good when the server
 * refuses the token, at the handshake or when asked after a drop. While the app is in the
 * background ([foreground] false) the socket is closed and nothing redials; it connects again
 * at once when the app comes back (tv-player-4k, TV-S8).
 */
class LiveUserData(
    private val scope: CoroutineScope,
    private val sessions: StateFlow<Session?>,
    private val link: SocketLink,
    /** Whether the server still takes the session's token: null when it cannot be asked. */
    private val tokenAccepted: suspend (Session) -> Boolean?,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    /** Whether the app is in the foreground (tv-player-4k). */
    private val foreground: Flow<Boolean> = flowOf(true),
) {
    private val _changes = MutableSharedFlow<UserDataChange>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<UserDataChange> = _changes.asSharedFlow()

    private val _state = MutableStateFlow<LiveState>(LiveState.Off)
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private var started = false

    /** The sign-in whose token the server refused: no tries for it, in front again or not (tv-player-4k). */
    private var refusedSession: Session? = null

    /** Follows the signed-in profile from now on; calling it again does nothing. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(sessions, foreground.distinctUntilChanged(), ::Pair).collectLatest { (session, inFront) ->
                when {
                    session == null -> _state.value = LiveState.Off

                    session == refusedSession -> _state.value = LiveState.Stopped

                    // Nixie (TV-S8): the socket closed with the last screen; the app's return opens it at once.
                    !inFront -> {
                        if (_state.value != LiveState.Off) log("The app is in the background: live updates pause")
                        _state.value = LiveState.Paused
                    }

                    else -> {
                        if (_state.value == LiveState.Paused) log("The app is back: live updates connect again")
                        _state.value = LiveState.Off
                        follow(session)
                    }
                }
            }
        }
    }

    private suspend fun follow(session: Session) {
        val userId = UUID.fromString(session.user.userId)
        val backoff = Backoff()
        while (true) {
            _state.value = LiveState.Connecting
            val ended = runSuspendCatching {
                coroutineScope {
                    // The wait starts over only once a connection stayed up for [STABLE_MS]: a server or
                    // proxy that opens and closes at once still gets the growing wait, not a redial a second.
                    var stable: Job? = null
                    link.open(session).collect { event ->
                        when (event) {
                            SocketEvent.Opened -> {
                                _state.value = LiveState.Live
                                stable?.cancel()
                                stable = launch {
                                    delay(STABLE_MS)
                                    backoff.reset()
                                }
                            }

                            is SocketEvent.Changed -> if (event.userId == userId) {
                                event.changes.forEach { _changes.emit(it) }
                            }
                        }
                    }
                    stable?.cancel()
                }
            }.exceptionOrNull()
            ended?.let { log("Live updates dropped: ${it.javaClass.simpleName}") }
            val refused = ended is SocketRefused
            if (refused || tokenAccepted(session) == false) {
                log("The server refused the token; live updates stop")
                refusedSession = session
                _state.value = LiveState.Stopped
                return
            }
            val wait = backoff.next()
            _state.value = LiveState.Waiting(wait)
            delay(wait)
        }
    }

    companion object {
        /** A connection up this long counts as working: the wait before a redial starts over. */
        const val STABLE_MS = 30_000L
        private const val TAG = "LiveUserData"
    }
}

/** The app's [LiveUserData]: the server's websocket for the signed-in profile. */
@Singleton
class LiveUpdates @Inject constructor(
    sessions: SessionManager,
    link: OkHttpSocketLink,
    authenticator: Authenticator,
) {
    val live = LiveUserData(
        // Nixie (tv-player-4k, TV-S8): the process's lifecycle: started while any screen of the app shows.
        foreground = flow {
            emitAll(
                ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) },
            )
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        sessions = sessions.session,
        link = link,
        tokenAccepted = { session ->
            val token = session.api.accessToken
            if (token == null) {
                false
            } else {
                runSuspendCatching {
                    authenticator.isTokenValid(session.server.address, token, session.api.deviceInfo.id)
                }.getOrNull()
            }
        },
    )
}
