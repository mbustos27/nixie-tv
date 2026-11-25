package io.github.glacier_jellyfin.androidtv.core.data

import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.mbustos27.nixie.tv.jellyfin.deviceFor
import io.github.mbustos27.nixie.tv.jellyfin.installDeviceId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder

/** The profile currently using the app. */
data class Session(
    val server: StoredServer,
    val user: StoredUser,
    val api: ApiClient,
    // Nixie: the install's device id; the profile's own is `api.deviceInfo.id` (DeviceIds).
    val installDeviceId: String = api.deviceInfo.id,
) {
    /** Jellyfin's `Authorization` header for requests the SDK client does not make (the player, raw HTTP). */
    fun authorization(): String = AuthorizationHeaderBuilder.buildHeader(
        clientName = api.clientInfo.name,
        clientVersion = api.clientInfo.version,
        deviceId = api.deviceInfo.id,
        deviceName = api.deviceInfo.name,
        accessToken = api.accessToken,
    )
}

@Singleton
class SessionManager @Inject constructor(
    private val jellyfin: Jellyfin,
    private val accounts: AccountRepository,
    private val authenticator: Authenticator,
) {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** Opens a stored, signed-in profile. Returns false when it has no token. */
    suspend fun open(serverId: String, userId: String): Boolean {
        val state = accounts.current()
        val server = state.servers.firstOrNull { it.id == serverId } ?: return false
        val user = state.users.firstOrNull { it.serverId == serverId && it.userId == userId } ?: return false
        val token = user.accessToken ?: return false
        // Nixie: requests go out as the profile's own device id; a sign-in from before keeps the install's.
        if (user.deviceId == null) accounts.pinDeviceId(serverId, userId, jellyfin.installDeviceId)
        val api = jellyfin.createApi(
            baseUrl = server.address,
            accessToken = token,
            deviceInfo = jellyfin.deviceFor(user.deviceId),
        )
        _session.value = Session(server, user, api, installDeviceId = jellyfin.installDeviceId)
        // Nixie (tv-home-start): "used last" is saved after the profile opened, not before: an encrypted write
        // that took about 0.6 s of the start on the emulator, and nothing on the way to Home reads it.
        nixieScope.launch { accounts.markUsed(serverId, userId) }
        return true
    }

    private val nixieScope = CoroutineScope(SupervisorJob() + Dispatchers.IO) // Nixie (tv-home-start)

    /** Back to profile selection; the profile stays signed in. */
    fun leave() {
        _session.value = null
    }

    /**
     * Ends the session on the server as well. The local token is dropped even
     * when the server cannot be reached, so the profile asks for its password
     * next time either way.
     */
    suspend fun signOut() {
        val current = endOnServer() ?: return
        accounts.forgetToken(current.server.id, current.user.userId)
        _session.value = null
    }

    /**
     * Signs out like [signOut] and forgets the profile on this device: PIN and
     * parental control go too. The Jellyfin account itself stays.
     */
    suspend fun removeFromDevice() {
        val current = endOnServer() ?: return
        accounts.removeUser(current.server.id, current.user.userId)
        _session.value = null
    }

    private suspend fun endOnServer(): Session? {
        val current = _session.value ?: return null
        val token = current.user.accessToken
        if (token != null) {
            // Nixie: with the device id the token belongs to.
            runCatching { authenticator.signOut(current.server.address, token, current.api.deviceInfo.id) }
                .onFailure { Log.w(TAG, "Server sign-out failed; token dropped locally only", it) }
        }
        return current
    }

    private companion object {
        const val TAG = "SessionManager"
    }
}
