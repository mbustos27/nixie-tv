package io.github.glacier_jellyfin.androidtv.trailer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.mbustos27.nixie.tv.NixieTrailers
import io.github.mbustos27.nixie.tv.trailer.TrailerWarmup
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/** What the view model asks of the embedded player. */
sealed interface YouTubeCommand {
    data object Play : YouTubeCommand
    data object Pause : YouTubeCommand
    data class SeekTo(val positionMs: Long) : YouTubeCommand

    /** A language code, or "" for off. */
    data class Subtitles(val language: String) : YouTubeCommand
}

/**
 * Reports of the embedded player, on the main thread. Each names its video:
 * reports still queued when the screen moves on to another trailer are stale.
 */
interface YouTubeListener {
    /** The IFrame API's player state: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued. */
    fun onYouTubeState(videoId: String, state: Int)
    fun onYouTubeProgress(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
    )

    /** The video's subtitle languages and the one shown (null: off). */
    fun onYouTubeSubtitles(
        videoId: String,
        tracks: List<TrailerSubtitle>,
        shown: String?,
    )

    /** The IFrame API's error code (101/150: embedding not allowed), or [YOUTUBE_NO_WEBVIEW]. */
    fun onYouTubeError(videoId: String, code: Int)

    /** Nixie (tv-player-4k, TV-S9): a step of the start, one of [YOUTUBE_STEPS], for its timing. */
    fun onYouTubeStep(videoId: String, step: String) = Unit

    /** Nixie (TV-S9): the player was made ahead on the movie page (`TrailerWarmup`); only the video loads now. */
    fun onYouTubeMadeAhead(videoId: String) = Unit
}

/** Nixie (TV-S9): the steps of a YouTube trailer's start, in order; the page may name only these. */
val YOUTUBE_STEPS = listOf("web view", "page", "YouTube API", "player ready")

/** The device has no usable WebView. */
const val YOUTUBE_NO_WEBVIEW = -1

/**
 * A YouTube video in YouTube's official embedded player (IFrame API), with its
 * own controls hidden: the trailer screen drives it through [commands]. The
 * WebView never takes focus, so the remote keeps talking to Compose.
 *
 * YouTube's embed rules forbid drawing over the player; the screen keeps its
 * OSD out of this view's bounds.
 */
@Composable
fun YouTubePlayer(
    videoId: String,
    /** Subtitles at the start, as in [PlaybackSettings.trailerSubtitles]; later changes arrive as [YouTubeCommand.Subtitles]. */
    subtitles: String,
    commands: Flow<YouTubeCommand>,
    listener: YouTubeListener,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember(videoId) {
        // Nixie (tv-player-4k, TV-S9): the player the movie page made ahead, when it is this video's.
        // Only now does the video's id go to YouTube (the shell made ahead names no title).
        TrailerWarmup.take()?.let { warm ->
            warm.relay.attach(listener, videoId)
            listener.onYouTubeMadeAhead(videoId)
            warm.webView.evaluateJavascript(
                "setSubtitles(${JSONObject.quote(subtitles)}); startVideo(${JSONObject.quote(videoId)});",
                null,
            )
            warm.webView
        } ?: runCatching { createWebView(context, videoId, subtitles, listener) }
            .onFailure { Log.w(TAG, "No WebView for the YouTube player", it) }
            .getOrNull()
    }
    if (webView == null) {
        LaunchedEffect(videoId) { listener.onYouTubeError(videoId, YOUTUBE_NO_WEBVIEW) }
        return
    }
    LaunchedEffect(webView) { listener.onYouTubeStep(videoId, "web view") } // Nixie (TV-S9)
    LaunchedEffect(webView) {
        commands.collect { command ->
            val script = when (command) {
                YouTubeCommand.Play -> "player && player.playVideo()"
                YouTubeCommand.Pause -> "player && player.pauseVideo()"
                is YouTubeCommand.SeekTo -> "player && player.seekTo(${command.positionMs / 1000.0}, true)"
                is YouTubeCommand.Subtitles -> "setSubtitles(${JSONObject.quote(command.language)})"
            }
            webView.evaluateJavascript(script, null)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    // Nixie (tv-player-4k, design rule 5): in the background the page's work and its timers (the progress every 500 ms)
    // pause; the app's return resumes them. The only web view of the app, so the timers are its own.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        webView.onPause()
        webView.pauseTimers()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        webView.onResume()
        webView.resumeTimers()
    }
    // Nixie (TV-S9): a player made ahead has no parent; the guard keeps a second screen from failing on one.
    AndroidView(factory = { webView.also { (it.parent as? ViewGroup)?.removeView(it) } }, modifier = modifier)
}

/**
 * Nixie (tv-player-4k, TV-S9): the player's shell made ahead: the web view, the page and YouTube's player with no video,
 * the same requests for every title. Every report goes to [listener] (a [YouTubeRelay] until the trailer screen takes
 * it); `startVideo(id)` in the page names the video, only when Trailer is pressed.
 */
internal fun makeYouTubePlayerAhead(context: Context, listener: YouTubeListener): WebView =
    createWebView(context, videoId = null, subtitles = "", listener, autoplay = false)

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: Context,
    videoId: String?, // Nixie (TV-S9): null for the shell made ahead
    subtitles: String,
    listener: YouTubeListener,
    autoplay: Boolean = true, // Nixie (TV-S9): false for a player made ahead
): WebView {
    val webView = WebView(context)
    // The page only needs the network: no files or content providers of the device.
    // Set on the variable, not inside apply: CodeQL does not follow the WebView into the lambda.
    webView.settings.allowFileAccess = false
    webView.settings.allowContentAccess = false
    with(webView) {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webChromeClient = object : WebChromeClient() {
            // Without a poster, WebView shows a grey play symbol until the video starts.
            override fun getDefaultVideoPoster(): Bitmap = createBitmap(1, 1)
        }
        webViewClient = object : WebViewClient() {
            // Links inside the player (title, logo) would leave the page; the remote cannot reach them anyway.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            // Nixie (TV-S9): the page and the IFrame API's script are in, for the start's timing.
            override fun onPageFinished(view: WebView, url: String?) = listener.onYouTubeStep(videoId.orEmpty(), "page")
        }
        addJavascriptInterface(Bridge(videoId.orEmpty(), listener), "Glacier")
        // YouTube refuses embeds without a referrer (error 153).
        // Nixie: the page lives at Nixie's own app-id origin (NixieTrailers), never another project's site.
        loadDataWithBaseURL(NixieTrailers.BASE_URL, page(videoId, subtitles, autoplay), "text/html", "utf-8", null)
    }
    return webView
}

/** Relays the page's calls, which arrive on a WebView thread, to the main thread. */
private class Bridge(private val videoId: String, private val listener: YouTubeListener) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onState(state: Int) {
        main.post { listener.onYouTubeState(videoId, state) }
    }

    @JavascriptInterface
    fun onProgress(positionMs: Double, durationMs: Double) {
        main.post { listener.onYouTubeProgress(videoId, positionMs.toLong(), durationMs.toLong()) }
    }

    @JavascriptInterface
    fun onSubtitles(tracks: String, shown: String) {
        val list = runCatching {
            val array = JSONArray(tracks)
            (0 until array.length()).map { i ->
                val track = array.getJSONObject(i)
                TrailerSubtitle(track.getString("language"), track.optString("name").ifEmpty { null })
            }.distinctBy { it.language }
        }.getOrDefault(emptyList())
        main.post { listener.onYouTubeSubtitles(videoId, list, shown.ifEmpty { null }) }
    }

    @JavascriptInterface
    fun onError(code: Int) {
        main.post { listener.onYouTubeError(videoId, code) }
    }

    // Nixie (TV-S9): the page's steps; anything but a known step name is dropped (frames share this bridge).
    @JavascriptInterface
    fun onStep(step: String) {
        if (step in YOUTUBE_STEPS) main.post { listener.onYouTubeStep(videoId, step) }
    }
}

/**
 * Subtitles go through the captions module, which the IFrame API documents
 * only loosely: cc_load_policy loads it, its "tracklist" and "track" options
 * name the languages, "track" also picks one, and unloading the module turns
 * subtitles off. YouTube loads the module again when playback starts, so
 * "off" is applied again then. A video without the chosen language plays
 * without subtitles. Player texts (such as "auto-generated") follow the
 * device language.
 */
// Nixie: the page as the web view gets it, for the test of its origin and host (NixieTrailersTest).
internal fun trailerPage(
    videoId: String?,
    subtitles: String,
    autoplay: Boolean = true,
): String = page(videoId, subtitles, autoplay)

private fun page(
    videoId: String?,
    subtitles: String,
    autoplay: Boolean,
) = """
    <!doctype html>
    <html><head>
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="preconnect" href="${NixieTrailers.EMBED_HOST}">
    <link rel="preconnect" href="https://i.ytimg.com">
    <style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}#player{position:absolute;inset:0;width:100%;height:100%}</style>
    </head><body>
    <div id="player"></div>
    <script>
    var player;
    // Nixie (TV-S9): the shell made ahead names no video (the same for every title) until startVideo(id), on the press.
    var videoId = ${videoId?.let { JSONObject.quote(it) } ?: "null"};
    var shell = ${videoId == null};
    var startOnReady = $autoplay;
    var playerReady = false;
    function startVideo(id) {
      videoId = id;
      startOnReady = true;
      if (playerReady) player.loadVideoById(id);
    }
    // A language code, or '' for off.
    var subtitles = ${JSONObject.quote(subtitles)};
    function captionsLoaded() {
      return player.getOptions().indexOf('captions') >= 0;
    }
    // Subtitle tracks by language code. The tracklist option leaves out auto-generated ones,
    // so the track YouTube loads on its own is added as well.
    var tracks = {};
    function collectTracks() {
      (player.getOption('captions', 'tracklist') || []).forEach(function (t) {
        if (t.languageCode && !tracks[t.languageCode]) tracks[t.languageCode] = t;
      });
      var current = player.getOption('captions', 'track') || {};
      if (current.languageCode && !tracks[current.languageCode]) {
        // The original, never a translation YouTube remembered from an earlier video.
        var original = {};
        for (var key in current) if (key !== 'translationLanguage') original[key] = current[key];
        tracks[current.languageCode] = original;
      }
      return Object.keys(tracks).length > 0;
    }
    function reportSubtitles(shown) {
      Glacier.onSubtitles(JSON.stringify(Object.keys(tracks).map(function (code) {
        return { language: code, name: tracks[code].languageName || tracks[code].displayName || '' };
      })), shown);
    }
    // The tracks can arrive after the captions module, without another event: checked until they are there.
    var listKnown = false;
    // YouTube remembers "off" and then skips cc_load_policy: the module is loaded once by hand.
    var loadAsked = false;
    function hideSubtitles() {
      player.unloadModule('captions');
      player.unloadModule('cc');
    }
    function setSubtitles(language) {
      subtitles = language;
      if (player && listKnown) applySubtitles();
    }
    // Only the chosen language shows, never one YouTube picks.
    function syncSubtitles() {
      if (listKnown) return;
      if (!captionsLoaded()) {
        if (!loadAsked && player.getPlayerState() === 1) {
          loadAsked = true;
          player.loadModule('captions');
        }
        return;
      }
      if (!collectTracks()) return;
      listKnown = true;
      reportSubtitles(applySubtitles());
    }
    // Shows the chosen language or nothing; returns what shows ('' for nothing).
    function applySubtitles() {
      if (subtitles === '' || !tracks[subtitles]) {
        hideSubtitles();
        return '';
      }
      // Without the module, its next onApiChange picks the track.
      if (captionsLoaded()) player.setOption('captions', 'track', tracks[subtitles]);
      else player.loadModule('captions');
      return subtitles;
    }
    // The captions module came or went.
    function onApiChange() {
      if (!listKnown) syncSubtitles();
      else if (captionsLoaded()) applySubtitles();
    }
    function onYouTubeIframeAPIReady() {
      Glacier.onStep('YouTube API');
      var options = {
        host: '${NixieTrailers.EMBED_HOST}',
        width: '100%', height: '100%',
        playerVars: {
          autoplay: ${if (autoplay) 1 else 0}, controls: 0, disablekb: 1, fs: 0, rel: 0, playsinline: 1, iv_load_policy: 3, cc_load_policy: 1,
          hl: ${JSONObject.quote(Locale.getDefault().language)}, origin: '$ORIGIN'
        },
        events: {
          onReady: function (e) {
            playerReady = true;
            Glacier.onStep('player ready');
            if (startOnReady) {
              if (shell) e.target.loadVideoById(videoId);
              else e.target.playVideo();
            }
            setInterval(function () {
              syncSubtitles();
              Glacier.onProgress(player.getCurrentTime() * 1000, (player.getDuration() || 0) * 1000);
            }, 500);
          },
          onStateChange: function (e) {
            // YouTube reloads the module (with what it remembers) when playback starts again.
            if (e.data === 1 && listKnown) applySubtitles();
            Glacier.onState(e.data);
          },
          onApiChange: onApiChange,
          onError: function (e) { Glacier.onError(e.data); }
        }
      };
      if (videoId) options.videoId = videoId;
      player = new YT.Player('player', options);
    }
    </script>
    <script src="https://www.youtube.com/iframe_api"></script>
    </body></html>
""".trimIndent()

// Nixie: the app's own origin, where Glacier used its website's.
private const val ORIGIN = NixieTrailers.ORIGIN
private const val TAG = "YouTubePlayer"
