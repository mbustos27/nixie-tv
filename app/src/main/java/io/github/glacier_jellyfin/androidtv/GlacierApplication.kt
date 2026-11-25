package io.github.glacier_jellyfin.androidtv

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import dagger.hilt.android.HiltAndroidApp
import io.github.glacier_jellyfin.androidtv.channels.HomeChannelsSync
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.log.DebugOptions
import io.github.glacier_jellyfin.androidtv.core.log.DebugThrottle
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.player.Media3Logs
import io.github.glacier_jellyfin.androidtv.diagnostics.PlaybackCheckActivity
import io.github.mbustos27.nixie.tv.NixieThemes
import io.github.mbustos27.nixie.tv.data.StartLine
import io.github.mbustos27.nixie.tv.library.NixieImageRequests
import io.github.mbustos27.nixie.tv.library.NixieImageWatch
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltAndroidApp
class GlacierApplication :
    Application(),
    SingletonImageLoader.Factory {

    // Nixie (tv-home-start): built off the main thread, after onCreate (its graph is not needed for the first frame).
    @Inject
    lateinit var homeChannels: dagger.Lazy<HomeChannelsSync>

    // Nixie (tv-home-start): the accounts' file is read (and decrypted) beside the activity's start, not after it.
    @Inject
    lateinit var nixieAccounts: AccountRepository

    /** Coil's defaults plus SVG, for the language flags. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(SvgDecoder.Factory())
            // Nixie (TV-S13's finding): a picture already coming is not asked of the server again.
            add(NixieImageRequests.network())
        }
        // Nixie (TV-S1): the library's first picture, for the detailed log (NixieImageWatch).
        .eventListener(NixieImageWatch)
        .build()

    override fun onCreate() {
        // First, so crashes while the app starts are recorded too.
        Log.install(this)
        StartLine.mark("Start: Application.onCreate begins") // Nixie (tv-home-start): the cold start's split
        Media3Logs.install()
        // Nixie: the themes, before anything is drawn (also in the playback check's process).
        NixieThemes.install(this)
        StartLine.mark("Start: logs and themes ready") // Nixie (tv-home-start)
        super.onCreate()
        StartLine.mark("Start: Application injected") // Nixie (tv-home-start)
        Log.i(
            "App", // Nixie: the log's start line names no other app
            "Started ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}) on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
        )
        // The playback check's own process only plays its samples.
        if (!PlaybackCheckActivity.isCheckProcess(getProcessName())) {
            // Nixie (tv-home-start): read once now; the first screen and the profile then find it in memory.
            CoroutineScope(Dispatchers.IO).launch { runCatching { nixieAccounts.current() } }
            CoroutineScope(Dispatchers.Default).launch { homeChannels.get().start() }
        }
        if (BuildConfig.DEBUG) registerThrottle()
        StartLine.mark("Start: Application.onCreate done") // Nixie (tv-home-start)
    }

    /**
     * Debug builds take a speed cap for streams from adb, to try the app on a slow link:
     * `adb shell am broadcast -a io.github.glacier_jellyfin.androidtv.THROTTLE --el kbps 20000 -p <package>`;
     * 0 lifts it. `--ez fit false` lets the display scale a transcode down instead of the server; `--ez fixed true`
     * keeps a ladder on its first tier; `--ez pause true` pauses at the first picture; `--ez seglog true` logs every segment load;
     * `--ez probe true` runs the segment probe once;
     * `--ez probeold true` makes it ask for segments played long ago.
     */
    private fun registerThrottle() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.hasExtra("kbps")) DebugThrottle.bitsPerSecond = intent.getLongExtra("kbps", 0) * 1000
                if (intent.hasExtra("fixed")) {
                    DebugOptions.fixedTier = intent.getBooleanExtra("fixed", false)
                    Log.w("Debug", "Ladder stays on its first tier: ${DebugOptions.fixedTier}")
                }
                if (intent.hasExtra("probe")) DebugOptions.probe = intent.getBooleanExtra("probe", false)
                if (intent.hasExtra("probeold")) DebugOptions.probeOld = intent.getBooleanExtra("probeold", false)
                if (intent.hasExtra("seglog")) DebugOptions.segmentLog = intent.getBooleanExtra("seglog", false)
                if (intent.hasExtra("pause")) DebugOptions.pauseOnStart = intent.getBooleanExtra("pause", false)
                if (intent.hasExtra("fit")) {
                    DebugOptions.fitDisplay = intent.getBooleanExtra("fit", true)
                    Log.w("Debug", "Transcodes fit the display: ${DebugOptions.fitDisplay}")
                }
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(THROTTLE_ACTION), ContextCompat.RECEIVER_EXPORTED)
    }

    private companion object {
        const val THROTTLE_ACTION = "io.github.glacier_jellyfin.androidtv.THROTTLE"
    }

    init {
        // The Jellyfin SDK logs through kotlin-logging, which otherwise expects
        // an SLF4J binding on the classpath and crashes without one. Route it
        // to Logcat instead. Must run before the SDK is first touched.
        System.setProperty("kotlin-logging-to-android-native", "true")
    }
}
