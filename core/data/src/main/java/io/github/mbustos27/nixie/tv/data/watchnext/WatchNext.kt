// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.watchnext

import android.content.Context
import androidx.datastore.core.DataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsState
import io.github.glacier_jellyfin.androidtv.core.data.settings.profileKey
import io.github.mbustos27.nixie.tv.data.ContinueWatchingRules
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * Nixie (tv-watchnext): Continue watching on the TV's home screen, which Android calls Watch Next.
 * The settings key of its own, apart from Glacier's and the other streams'.
 */
@Serializable
data class NixieWatchNextSettings(
    /** Continue watching and Up next go to the home screen's Watch Next row (Settings › Home). */
    val showOnHomeScreen: Boolean = true,
)

/** A video position the server took for [itemId], for that title's row on the home screen. */
data class WatchNextProgress(
    val itemId: UUID,
    val positionMs: Long,
    /** Not playing (paused, or buffering for a moment). */
    val paused: Boolean = false,
    /** The stop: the player ended or was left. */
    val final: Boolean = false,
)

/**
 * Whose titles the home screen shows: the profile that opened in Nixie last, while it is still
 * signed in on this device. Never another profile's: after a sign-out, or once that profile or its
 * server is removed from the device, nothing shows until a profile opens again.
 */
object WatchNextOwner {
    fun key(serverId: String, userId: String): String = "$serverId/$userId"

    fun key(user: StoredUser): String = key(user.serverId, user.userId)

    /** The [recorded] profile, if it is still on the device, signed in and on a known server. */
    fun of(state: AccountState, recorded: String?): StoredUser? {
        if (recorded == null) return null
        val user = state.users.firstOrNull { key(it) == recorded } ?: return null
        if (user.accessToken == null) return null
        if (state.servers.none { it.id == user.serverId }) return null
        return user
    }
}

/**
 * What the home screen's row lists: Nixie's own Continue watching, then Up next, as Home shows
 * them ([ContinueWatchingRules]: each title once, stale ones hidden as Settings says); nothing
 * when the profile switched it off.
 */
object WatchNextRules {
    fun titles(
        resume: List<BaseItemDto>,
        nextUp: List<BaseItemDto>,
        settings: ProfileSettings,
        now: LocalDateTime,
    ): List<BaseItemDto> {
        if (!settings.nixieWatchNext.showOnHomeScreen) return emptyList()
        val rows = ContinueWatchingRules.rows(resume, nextUp, settings.nixieHome, now)
        return rows.continueWatching + rows.upNext
    }
}

/** The owner of the home screen's rows and that profile's settings ([WatchNextOwner]). */
interface WatchNextProfiles {
    /** The owner's key ([WatchNextOwner.key]) each time it may have changed; null while there is none. */
    val ownerKeys: Flow<String?>

    suspend fun owner(): StoredUser?

    suspend fun settingsOf(user: StoredUser): ProfileSettings

    /** A profile opened in Nixie: it owns the rows from now on. */
    fun record(serverId: String, userId: String)

    /**
     * tv-home-start: the recorded owner's key once that profile is known to be gone (signed out, or removed from the
     * device with its server); null when nothing is recorded yet, or the recorded profile is still signed in.
     */
    suspend fun gone(): String? = null
}

/** [WatchNextProfiles] on this device: the profile opened last is kept in a small file of its own. */
@Singleton
class DeviceWatchNextProfiles @Inject constructor(
    @ApplicationContext context: Context,
    private val accounts: AccountRepository,
    private val settings: DataStore<SettingsState>,
) : WatchNextProfiles {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val recorded = MutableStateFlow(prefs.getString(KEY_OWNER, null))

    override val ownerKeys: Flow<String?> =
        combine(accounts.state, recorded) { state, key -> WatchNextOwner.of(state, key)?.let(WatchNextOwner::key) }

    override suspend fun owner(): StoredUser? = WatchNextOwner.of(accounts.current(), recorded.value)

    override suspend fun settingsOf(user: StoredUser): ProfileSettings =
        settings.data.first().profiles[profileKey(user.serverId, user.userId)] ?: ProfileSettings()

    override fun record(serverId: String, userId: String) {
        val key = WatchNextOwner.key(serverId, userId)
        if (recorded.value == key) return
        prefs.edit().putString(KEY_OWNER, key).commit()
        recorded.value = key
    }

    override suspend fun gone(): String? = recorded.value?.takeIf { WatchNextOwner.of(accounts.current(), it) == null }

    private companion object {
        const val PREFS = "nixie_watchnext"
        const val KEY_OWNER = "owner"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WatchNextModule {
    @Binds
    abstract fun profiles(device: DeviceWatchNextProfiles): WatchNextProfiles
}
