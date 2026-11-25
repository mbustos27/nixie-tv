// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.trailer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.trailer.TrailerSubtitle
import io.github.glacier_jellyfin.androidtv.trailer.YouTubeListener
import io.github.glacier_jellyfin.androidtv.trailer.makeYouTubePlayerAhead

/**
 * TV-S9: a YouTube trailer's first frame took tens of seconds on a slow 4K Google TV box, most of it the cold path
 * (the web view's first creation, YouTube's page, API and player). Once a movie page is drawn (design rule 1), and
 * again when its Trailer button takes the focus, the page makes the player's shell ahead: one web view, the page,
 * YouTube's API and player with no video. That is the same for every title: YouTube learns nothing about what is
 * browsed (Nixie's rule for outside services). Pressing Trailer hands the shell to the trailer screen, which names the
 * video then. The page lets it go when it is left or the app stops (design rule 5); nothing is made while the app is
 * in the background. A title with a trailer file on the server plays that first (Glacier's order), so nothing is made
 * for it.
 */
object TrailerWarmup {
    /** A player shell made ahead: its page, and the relay its reports go through. */
    class Warm(val webView: WebView, val relay: YouTubeRelay)

    // Main thread only, as the web view. It holds the activity's web view only while a movie page is started: the page's
    // stop (always before the activity is destroyed) and its leaving let it go.
    @SuppressLint("StaticFieldLeak")
    private var warm: Warm? = null

    /** The trailer screen is opening and will take the player: the movie page leaving does not let it go. */
    private var claimed = false

    /**
     * The movie page stops as the trailer screen opens, before that screen is composed and can claim the player: the
     * release waits [RELEASE_GRACE_MS] for the claim. Seen on the emulator: the page's stop came 60 ms before the
     * trailer screen.
     */
    private val main = Handler(Looper.getMainLooper())
    private val pendingRelease = Runnable { if (!claimed) destroy() }

    /** Seam for the tests: makes the web view. */
    internal var make: (Context, YouTubeListener) -> WebView = ::makeYouTubePlayerAhead

    /** A shell is made ahead (for the tests). */
    val ready: Boolean get() = warm != null

    /**
     * Makes the player's shell ahead, unless one is made already. [started]: the calling page is started (the app in the
     * foreground); nothing is made otherwise.
     */
    fun prepare(context: Context, started: Boolean) {
        main.removeCallbacks(pendingRelease)
        if (warm != null) {
            claimed = false // back on the page: it owns the shell again
            return
        }
        if (!started) return
        val relay = YouTubeRelay()
        val webView = runCatching { make(context, relay) }
            .onFailure { Log.w(TAG, "Making the trailer's player ahead failed", it) }
            .getOrNull() ?: return
        warm = Warm(webView, relay)
        claimed = false
        Log.d(TAG, "The trailer's player is being made ahead")
    }

    /** The trailer screen opens: what was made ahead waits for it. */
    fun claim() {
        claimed = warm != null
        if (claimed) main.removeCallbacks(pendingRelease)
    }

    /** The shell made ahead, handed over (no longer this object's); null when there is none. */
    fun take(): Warm? {
        val taken = warm ?: return null
        main.removeCallbacks(pendingRelease)
        warm = null
        claimed = false
        return taken
    }

    /**
     * The movie page is left or the app stops: the player made ahead goes [RELEASE_GRACE_MS] later, unless the trailer
     * screen claims it meanwhile.
     */
    fun release() {
        if (claimed || warm == null) return
        main.removeCallbacks(pendingRelease)
        main.postDelayed(pendingRelease, RELEASE_GRACE_MS)
    }

    /** The trailer screen closes or stops without taking it: it goes (not when a page owns it again). */
    fun releaseClaimed() {
        if (!claimed) return
        claimed = false
        destroy()
    }

    private fun destroy() {
        val gone = warm ?: return
        warm = null
        gone.relay.detach()
        runCatching {
            gone.webView.stopLoading()
            gone.webView.destroy()
        }
        Log.d(TAG, "The trailer's player made ahead is let go")
    }

    private const val TAG = "TrailerWarmup"

    /** How long a release waits for the trailer screen's claim. */
    const val RELEASE_GRACE_MS = 1_000L
}

/**
 * The reports of a player shell made ahead: kept until the trailer screen [attach]es its listener with the video it
 * names, then handed on as they come, as that video's. Steps that came before are given to it at once; progress,
 * states and errors before then are dropped (no video was asked for yet).
 */
class YouTubeRelay : YouTubeListener {
    private var target: YouTubeListener? = null
    private var videoId = ""
    private val steps = LinkedHashSet<String>()

    fun attach(listener: YouTubeListener, videoId: String) {
        this.videoId = videoId
        target = listener
        steps.forEach { listener.onYouTubeStep(videoId, it) }
    }

    fun detach() {
        target = null
    }

    override fun onYouTubeState(videoId: String, state: Int) {
        target?.onYouTubeState(this.videoId, state)
    }

    override fun onYouTubeProgress(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        target?.onYouTubeProgress(this.videoId, positionMs, durationMs)
    }

    override fun onYouTubeSubtitles(
        videoId: String,
        tracks: List<TrailerSubtitle>,
        shown: String?,
    ) {
        target?.onYouTubeSubtitles(this.videoId, tracks, shown)
    }

    override fun onYouTubeError(videoId: String, code: Int) {
        target?.onYouTubeError(this.videoId, code)
    }

    override fun onYouTubeStep(videoId: String, step: String) {
        steps += step
        target?.onYouTubeStep(this.videoId, step)
    }
}

/** Whether [details]' page makes a YouTube player ahead: a YouTube trailer, and no trailer file on the server. */
fun makesTrailerAhead(details: ItemDetails): Boolean =
    details.trailers.localCount == 0 && details.trailers.youTube.isNotEmpty()

/**
 * Nixie (TV-S9) on a movie or show page: makes the YouTube player's shell ahead once the page is drawn and started,
 * and lets it go when the page stops (left, or the app in the background) or leaves the composition. TV-S13: and hands
 * the page's details over to the trailer screen ([TrailerHandOver]), whatever its trailers.
 */
@Composable
fun NixieTrailerWarmup(details: ItemDetails) {
    if (details.trailers.any) NixieTrailerHandOver(details)
    if (!makesTrailerAhead(details)) return
    val context = LocalContext.current
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (state.isAtLeast(Lifecycle.State.STARTED)) {
        LaunchedEffect(Unit) {
            // Design rule 1: the page is drawn first.
            withFrameNanos { }
            withFrameNanos { }
            TrailerWarmup.prepare(context, started = true)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { TrailerWarmup.release() }
    DisposableEffect(Unit) { onDispose { TrailerWarmup.release() } }
}

/** Nixie (TV-S9): the Trailer button taking the focus makes the player's shell ahead again, if it was let go. */
@Composable
fun Modifier.nixieWarmTrailerOnFocus(details: ItemDetails): Modifier {
    if (!makesTrailerAhead(details)) return this
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return onFocusChanged {
        if (it.isFocused) TrailerWarmup.prepare(context, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
}
