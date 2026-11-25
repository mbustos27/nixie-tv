// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.start

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.Profile
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StartChoice
import io.github.glacier_jellyfin.androidtv.core.data.StartMode
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.profileChoice
import io.github.glacier_jellyfin.androidtv.core.data.profilesFor
import io.github.glacier_jellyfin.androidtv.core.data.startChoice
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerFinder
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.SignInRoute
import io.github.glacier_jellyfin.androidtv.setup.ServerConnector
import io.github.mbustos27.nixie.tv.data.SavedHome
import io.github.mbustos27.nixie.tv.data.StartLine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Nixie (tv-home-start): which profile "the single profile" ([StartMode.Single]) opened at the last start that asked
 * the server, per server, so the next start opens it from what is saved. Kept in a small file of its own.
 */
@Singleton
class StartMemory @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun single(serverId: String): String? = prefs.getString(serverId, null)

    /** [userId] opened by itself on [serverId]; null: the profiles were shown, so nothing opens before the server says. */
    fun rememberSingle(serverId: String, userId: String?) {
        if (prefs.getString(serverId, null) == userId) return
        prefs.edit().apply { if (userId == null) remove(serverId) else putString(serverId, userId) }.apply()
    }

    private companion object {
        const val PREFS = "nixie_start"
    }
}

/**
 * Nixie (tv-home-start): the profile an app start opens from what is saved on the device, without the server: its
 * address, user list and token are checked after Home opened ([StartCheck]). Before, the start waited on three
 * requests (the server's id, its users, the token); on a slow box after standby they took up to tens of seconds.
 * Null when the start must ask the server (the profiles are shown, a PIN is needed, nothing remembered yet).
 */
object SavedStart {
    fun choice(
        state: AccountState,
        serverId: String,
        userId: String?,
        rememberedSingle: String?,
    ): Profile? {
        val profiles = state.profilesFor(serverId, null)
        val choice = when {
            userId != null -> profileChoice(userId, profiles)
            state.startProfile.mode == StartMode.Single -> rememberedSingle?.let { profileChoice(it, profiles) }
            else -> state.startChoice(serverId, profiles)
        }
        val open = choice as? StartChoice.Open ?: return null
        return profiles.firstOrNull { it.userId == open.userId && it.isSignedIn }
    }
}

/**
 * Nixie (tv-home-start): opens the start profile of [serverId] (or [userId], a launcher row's) from what is saved on
 * the device, with no request: its saved Home read into memory meanwhile, the server checked afterwards ([StartCheck]).
 * The app's first screen is then Home itself, without the profiles screen on the way.
 */
@Singleton
class StartFromSaved @Inject constructor(
    private val accounts: AccountRepository,
    private val sessions: SessionManager,
    private val memory: StartMemory,
    private val saved: SavedHome,
    private val check: StartCheck,
    private val startLine: StartLine,
) {
    /** True when the profile opened (Home may come next); false when the start must ask the server first. */
    suspend fun open(serverId: String, userId: String?): Boolean {
        val began = startLine.now()
        val state = accounts.current()
        val server = state.servers.firstOrNull { it.id == serverId } ?: return false
        val profile = SavedStart.choice(state, serverId, userId, memory.single(serverId)) ?: return false
        // Home's saved page into memory meanwhile, so Home draws from it at once.
        state.users.firstOrNull { it.serverId == serverId && it.userId == profile.userId }
            ?.let { saved.prefetch(SavedHome.key(it)) }
        if (!sessions.open(serverId, profile.userId)) return false
        check.verify(server, profile)
        startLine.step("Start: profile opened from what is saved", startLine.now() - began)
        return true
    }
}

/**
 * Nixie (tv-home-start): after a start that opened a profile from what is saved ([SavedStart]), what the start used to
 * ask before Home, asked in the background: another server answering at the address (its profiles move over), a
 * token the server no longer takes (sign-in), and for "the single profile" whether the server still lists one. A
 * server that does not answer changes nothing: Home says so, and keeps what it saved.
 */
@Singleton
class StartCheck @Inject constructor(
    private val finder: ServerFinder,
    private val connector: ServerConnector,
    private val authenticator: Authenticator,
    private val accounts: AccountRepository,
    private val sessions: SessionManager,
    private val memory: StartMemory,
    private val startLine: StartLine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _redirects = Channel<Any>(Channel.CONFLATED)

    /** Screens the app opens from scratch after a check: sign-in, or the profiles of the server that answers now. */
    val redirects: Flow<Any> = _redirects.receiveAsFlow()

    fun verify(server: StoredServer, profile: Profile) {
        scope.launch {
            val began = startLine.now()
            val answering = runSuspendCatching { finder.identify(server.address) }.getOrNull()
            if (answering != null && answering.id != server.id) {
                val moved = connector.adopt(answering)
                sessions.leave()
                _redirects.send(ProfilesRoute(answering.id, appStart = true, userId = moved[profile.userId]))
                return@launch
            }
            val stored = accounts.current().users.firstOrNull {
                it.serverId == server.id && it.userId == profile.userId
            }
            val token = stored?.accessToken ?: return@launch
            val accepted = runSuspendCatching { authenticator.isTokenValid(server.address, token, stored.deviceId) }
            startLine.step("Start: the server and the token checked after Home opened", startLine.now() - began)
            if (accepted.getOrNull() == false) {
                Log.i(TAG, "The server no longer takes the profile's token: sign-in")
                accounts.forgetToken(server.id, profile.userId)
                if (sessions.session.value?.user?.userId == profile.userId) sessions.leave()
                _redirects.send(SignInRoute(server.id, profile.name))
                return@launch
            }
            if (accounts.current().startProfile.mode == StartMode.Single) checkSingle(server)
        }
    }

    /** "The single profile": the next start opens one only while the server still lists one. */
    private suspend fun checkSingle(server: StoredServer) {
        val users = runSuspendCatching { authenticator.publicUsers(server.address) }.getOrNull() ?: return
        val state = accounts.current()
        val open = state.startChoice(server.id, state.profilesFor(server.id, users)) as? StartChoice.Open
        memory.rememberSingle(server.id, open?.userId)
    }

    private companion object {
        const val TAG = "StartCheck"
    }
}
