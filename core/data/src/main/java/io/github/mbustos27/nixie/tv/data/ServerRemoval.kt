// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsSync
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Nixie (tv-gate-a, tv-fix-a): a saved server taken off this TV, through its card's menu in the server list.
 *
 * The TV's part comes first and in one go: the session is left when it is that server's, then the server and its
 * profiles (their tokens with them) leave the device and the profiles' settings here are forgotten. Only then are the
 * profiles signed out on the server, best effort, all at once, each given [signOutTimeoutMs]: a server that is down
 * (the usual reason to remove one) costs that much once, and never keeps the server on the TV. Everything runs in this
 * object's own scope, so leaving the screen (Back) cannot stop a removal half-way. The accounts on the server stay,
 * and the server can be added again.
 */
@Singleton
class ServerRemoval @Inject constructor(
    private val accounts: AccountRepository,
    private val authenticator: Authenticator,
    private val sessions: SessionManager,
    private val settings: SettingsSync,
) {
    /** Outlives any screen; tests put their own. */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How long each sign-out may take before it is left. */
    internal var signOutTimeoutMs: Long = SIGN_OUT_TIMEOUT_MS

    private val _removing = MutableStateFlow<Set<String>>(emptySet())

    /** The servers being removed right now (their TV part). */
    val removing: StateFlow<Set<String>> = _removing.asStateFlow()

    /**
     * Starts removing [serverId] in this object's scope and answers when the TV's part is done (the sign-outs go on
     * after it): true when it was removed, false when there was no such server or it is being removed already.
     */
    fun start(serverId: String): Deferred<Boolean> = scope.async { remove(serverId) }

    /** As [start], in the caller's coroutine; the sign-outs are still sent from this object's scope. */
    suspend fun remove(serverId: String): Boolean {
        if (!claim(serverId)) return false
        try {
            val state = accounts.current()
            val server = state.servers.firstOrNull { it.id == serverId } ?: return false
            val users = state.users.filter { it.serverId == serverId }
            if (sessions.session.value?.server?.id == serverId) sessions.leave()
            accounts.removeServer(serverId)
            users.forEach { settings.forget(serverId, it.userId) }
            Log.i(TAG, "Server removed from this device, with its profiles; signing them out on the server")
            signOut(server.address, users.filter { it.accessToken != null })
            return true
        } finally {
            _removing.update { it - serverId }
        }
    }

    private fun claim(serverId: String): Boolean {
        var claimed = false
        _removing.update { now ->
            claimed = serverId !in now
            if (claimed) now + serverId else now
        }
        return claimed
    }

    /** Each token ended on the server as its own device (DeviceIds), all at once, none waited for beyond the timeout. */
    private fun signOut(address: String, users: List<StoredUser>) {
        users.forEach { user ->
            scope.launch {
                val ended = withTimeoutOrNull(signOutTimeoutMs) {
                    runSuspendCatching { authenticator.signOut(address, user.accessToken!!, user.deviceId) }.isSuccess
                }
                if (ended != true) Log.w(TAG, NOT_SIGNED_OUT)
            }
        }
    }

    private companion object {
        const val TAG = "ServerRemoval"
        const val SIGN_OUT_TIMEOUT_MS = 5_000L
        const val NOT_SIGNED_OUT = "A sign-out after the server's removal did not go through; dropped here only"
    }
}
