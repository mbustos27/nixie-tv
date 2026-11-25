package io.github.glacier_jellyfin.androidtv.core.data

import androidx.datastore.core.DataStore
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.core.jellyfin.SignedInUser
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.mbustos27.nixie.tv.data.NamesSeen
import io.github.mbustos27.nixie.tv.data.nixieWithNamesSeen
import io.github.mbustos27.nixie.tv.data.withDeviceIdPinned
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
class AccountRepository @Inject constructor(private val store: DataStore<AccountState>) {
    val state: Flow<AccountState> = store.data

    suspend fun current(): AccountState = store.data.first()

    suspend fun rememberServer(server: ServerInfo) {
        update { it.withServer(server, now()) }
    }

    suspend fun rememberSignIn(user: SignedInUser) {
        update { it.withSignIn(user, now()) }
    }

    suspend fun markUsed(serverId: String, userId: String) {
        update { it.updateUser(serverId, userId) { user -> user.copy(lastUsedAt = now()) } }
    }

    // Nixie: a sign-in from before device ids per sign-in keeps the install's id, written down once.
    suspend fun pinDeviceId(
        serverId: String,
        userId: String,
        installId: String,
    ) {
        update { it.withDeviceIdPinned(serverId, userId, installId) }
    }

    /** Keeps the profile listed but drops its credential. */
    suspend fun forgetToken(serverId: String, userId: String) {
        update { it.updateUser(serverId, userId) { user -> user.copy(accessToken = null) } }
    }

    suspend fun removeUser(serverId: String, userId: String) {
        update { it.withoutUser(serverId, userId) }
    }

    suspend fun setPin(
        serverId: String,
        userId: String,
        pin: String,
    ) {
        val hash = withContext(Dispatchers.Default) { Pins.hash(pin) }
        update { it.updateUser(serverId, userId) { user -> user.copy(pin = hash, pinChangedAt = now()) } }
    }

    /** Drops the PIN and every lock that needs it; the age limit stays. */
    suspend fun removePin(serverId: String, userId: String) {
        update {
            it.updateUser(serverId, userId) { user ->
                user.copy(
                    pin = null,
                    pinChangedAt = null,
                    protection = user.protection.copy(
                        pinOnProfileSwitch = false,
                        pinForLocked = false,
                        pinForSettings = false,
                    ),
                )
            }
        }
    }

    suspend fun updateProtection(
        serverId: String,
        userId: String,
        transform: (Protection) -> Protection,
    ) {
        update {
            it.updateUser(serverId, userId) { user -> user.copy(protection = transform(user.protection)) }
        }
    }

    suspend fun verifyPin(
        serverId: String,
        userId: String,
        pin: String,
    ): Boolean {
        val stored = current().users.firstOrNull { it.serverId == serverId && it.userId == userId }?.pin ?: return true
        // Hashing takes a moment on slow TVs; off the main thread the keys keep coming.
        val ok = withContext(Dispatchers.Default) { Pins.verify(pin, stored) }
        if (ok && Pins.outdated(stored)) {
            val hash = withContext(Dispatchers.Default) { Pins.hash(pin) }
            update { it.updateUser(serverId, userId) { user -> user.copy(pin = hash) } }
            Log.i(TAG, "PIN hash redone with ${hash.iterations} instead of ${stored.iterations} iterations")
        }
        return ok
    }

    suspend fun setStartProfile(startProfile: StartProfile) {
        update { it.copy(startProfile = startProfile) }
    }

    /** [server] was set up again at the address of [oldId]; see [withServerReplaced]. */
    suspend fun replaceServer(
        oldId: String,
        server: ServerInfo,
        kept: Map<String, String>,
    ) {
        update { it.withServerReplaced(oldId, server, kept, now()) }
    }

    suspend fun removeServer(serverId: String) {
        update { it.withoutServer(serverId) }
    }

    /**
     * Nixie (tv-1.0-privacy): the names of servers and profiles kept for the log's anonymiser, forgotten: the log they
     * were kept for was cleared. (Clearing the app's data takes them with the accounts.)
     */
    suspend fun nixieForgetNamesSeen() {
        store.updateData { it.copy(nixieSeen = NamesSeen()) }
    }

    /**
     * Nixie (tv-1.0-privacy): every change keeps the server and profile names it had before and after it
     * ([nixieWithNamesSeen]): the shared log's anonymiser still finds a removed one in older lines.
     */
    private suspend fun update(transform: (AccountState) -> AccountState) {
        store.updateData { before -> transform(before).nixieWithNamesSeen(before) }
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val TAG = "Accounts"
    }
}
