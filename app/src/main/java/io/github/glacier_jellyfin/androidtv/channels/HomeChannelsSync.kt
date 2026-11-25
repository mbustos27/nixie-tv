package io.github.glacier_jellyfin.androidtv.channels

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeScreenRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.mbustos27.nixie.tv.channels.NixieWatchNext
import io.github.mbustos27.nixie.tv.data.StartLine
import io.github.mbustos27.nixie.tv.data.watchnext.WatchNextProfiles
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the Android TV home screen ([HomeChannels]) current: when a profile
 * opens, after the profile used last or its age limit changed, after
 * playback or marking a title watched, and every few hours in the background
 * for titles added meanwhile.
 */
@Singleton
class HomeChannelsSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accounts: AccountRepository,
    private val sessions: SessionManager,
    private val playback: PlaybackRepository,
    private val details: DetailRepository,
    // Nixie (tv-watchnext): Continue watching on the home screen, changed on the spot (NixieWatchNext).
    private val nixie: NixieWatchNext,
    private val nixieProfiles: WatchNextProfiles,
    private val settings: SettingsRepository,
    // Nixie (tv-home-start): the start's work waits for Home (StartLine).
    private val nixieStartLine: StartLine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        // Nixie (tv-home-start): all of it off the main thread and after Home is drawn and settled (or a while): the
        // provider check, WorkManager's start and the rows' load never stand in the way of Home's first rows.
        scope.launch {
            nixieStartLine.awaitAnyHomeSettled(StartLine.BACKGROUND_CAP_MS)
            if (!HomeChannels(context).available) return@launch
            nixieStartWork()
        }
    }

    private fun nixieStartWork() {
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HomeChannelsWorker>(PERIOD_HOURS, TimeUnit.HOURS).setConstraints(Online).build(),
        )
        // A profile opened in the app (also at every app start); not on process starts for the background work.
        scope.launch {
            sessions.session
                .map { session -> session?.let { it.server.id to it.user.userId } }
                .distinctUntilChanged()
                .filterNotNull()
                .collectLatest { (serverId, userId) ->
                    // Nixie (tv-home-start): once that profile's Home is in, or a while.
                    nixieStartLine.awaitHomeSettled("$serverId/$userId", StartLine.BACKGROUND_CAP_MS)
                    refresh(context)
                }
        }
        // Signed out, or the age limit of the profile changed.
        scope.launch {
            accounts.state
                .map { state ->
                    state.users.filter { it.accessToken != null }.maxByOrNull { it.lastUsedAt }?.let {
                        Triple(it.serverId, it.userId, it.protection)
                    }
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh(context) }
        }
        scope.launch { playback.stopped.collect { refresh(context) } }
        scope.launch { details.playedChanged.collect { refresh(context) } }
        nixieStart() // Nixie (tv-watchnext)
    }

    // ---- Nixie (tv-watchnext): the home screen's Continue watching between two loads (NixieWatchNext) ----
    private fun nixieStart() {
        if (io.github.glacier_jellyfin.androidtv.BuildConfig.DEBUG) nixie.registerDebugDump()
        nixie.follow(
            sessions = sessions.session.filterNotNull().map { it.server.id to it.user.userId },
            owners = nixieProfiles.ownerKeys,
            positions = playback.nixieProgress,
            shows = settings.active.map { it?.nixieWatchNext?.showOnHomeScreen }.filterNotNull(),
        )
    }
    // Nixie (tv-watchnext) end.

    companion object {
        private const val PERIODIC = "home-channels"
        private const val ONCE = "home-channels-refresh"
        private const val PERIOD_HOURS = 6L

        /** Short delay: a burst of changes (profile, then playback) is written once. */
        private const val DELAY_SECONDS = 3L

        private val Online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun refresh(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<HomeChannelsWorker>()
                    .setInitialDelay(DELAY_SECONDS, TimeUnit.SECONDS)
                    .setConstraints(Online)
                    .build(),
            )
        }
    }
}

class HomeChannelsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun homeScreen(): HomeScreenRepository

        fun nixieWatchNext(): NixieWatchNext // Nixie (tv-watchnext)

        fun nixieStartLine(): StartLine // Nixie (tv-home-start)
    }

    override suspend fun doWork(): Result {
        val channels = HomeChannels(applicationContext)
        if (!channels.available) return Result.success()
        // Nixie (tv-home-start): in a process just started, Home's rows first (or a while); the times in the log.
        val startLine = EntryPointAccessors.fromApplication<Dependencies>(applicationContext).nixieStartLine()
        val young = StartLine.BACKGROUND_CAP_MS - startLine.sinceStart()
        if (young > 0) startLine.awaitAnyHomeSettled(young)
        val began = startLine.now()
        val content = try {
            EntryPointAccessors.fromApplication<Dependencies>(applicationContext).homeScreen().load()
                .also { Log.d(TAG, "Loaded the home screen titles in ${startLine.now() - began} ms") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The rows stay as they are until the server answers again.
            Log.w(TAG, "Loading the home screen titles failed", e)
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
        return try {
            // The periodic and the one-time work may overlap; one writes at a time.
            // Nixie (tv-watchnext): under the lock of the changes on the spot, only for the profile that owns the rows.
            val nixie = EntryPointAccessors.fromApplication<Dependencies>(applicationContext).nixieWatchNext()
            val writing = startLine.now()
            withContext(Dispatchers.IO) { nixie.publish(content) { channels.write(it) } }
            Log.d(TAG, "Wrote the home screen rows in ${startLine.now() - writing} ms") // Nixie (tv-home-start)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Writing the home screen rows failed", e)
            Result.success()
        }
    }

    private companion object {
        const val TAG = "HomeChannels"
        const val MAX_ATTEMPTS = 3
    }
}

/** The launcher asks for the rows after Glacier was installed, or its data was cleared. */
class HomeChannelsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TvContractCompat.ACTION_INITIALIZE_PROGRAMS) HomeChannelsSync.refresh(context)
    }
}
