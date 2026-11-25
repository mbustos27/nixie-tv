package io.github.glacier_jellyfin.androidtv.channels

import android.content.Context
import android.content.Intent
import io.github.glacier_jellyfin.androidtv.MainActivity
import javax.inject.Inject
import javax.inject.Singleton
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

/** A title picked on the Android TV home screen: opens its page in the profile it came from. */
data class HomeLaunch(
    val serverId: String,
    val userId: String,
    val itemId: String,
    // Nixie (tv-watchnext): an entry of the home screen's Continue watching plays from where it was left.
    val play: Boolean = false,
) {

    /** Explicit, so test and release builds installed side by side never open each other. */
    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION)
        .putExtra(EXTRA_SERVER, serverId)
        .putExtra(EXTRA_USER, userId)
        .putExtra(EXTRA_ITEM, itemId)
        .apply { if (play) putExtra(EXTRA_PLAY, true) } // Nixie (tv-watchnext)

    companion object {
        private const val ACTION = "io.github.glacier_jellyfin.androidtv.OPEN_TITLE"
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_USER = "user"
        private const val EXTRA_ITEM = "item"
        private const val EXTRA_PLAY = "play" // Nixie (tv-watchnext)

        fun from(intent: Intent?): HomeLaunch? {
            if (intent?.action != ACTION) return null
            return HomeLaunch(
                serverId = intent.getStringExtra(EXTRA_SERVER) ?: return null,
                userId = intent.getStringExtra(EXTRA_USER)?.let(::uuid) ?: return null,
                itemId = intent.getStringExtra(EXTRA_ITEM)?.let(::uuid) ?: return null,
                play = intent.getBooleanExtra(EXTRA_PLAY, false), // Nixie (tv-watchnext)
            )
        }

        /** Any app can send this intent: ids come with or without dashes, and a broken one is dropped. */
        private fun uuid(id: String): String? = id.toUUIDOrNull()?.toString()
    }
}

/**
 * The home screen title waiting for its profile: Home opens it once that profile
 * is signed in (after its PIN, if it has one), and drops it for any other.
 */
@Singleton
class HomeLaunches @Inject constructor() {
    @Volatile
    var pending: HomeLaunch? = null
        private set

    fun set(launch: HomeLaunch) {
        pending = launch
    }

    /** The waiting title if it belongs to this profile; it is gone afterwards either way. */
    fun take(serverId: String, userId: String): HomeLaunch? =
        pending.also { pending = null }?.takeIf { it.serverId == serverId && it.userId == userId }
}
