// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.trailer

import android.app.Application
import android.os.Looper
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.Trailers
import io.github.glacier_jellyfin.androidtv.core.data.media.YouTubeTrailer
import io.github.glacier_jellyfin.androidtv.trailer.TrailerSubtitle
import io.github.glacier_jellyfin.androidtv.trailer.YouTubeListener
import io.github.glacier_jellyfin.androidtv.trailer.makeYouTubePlayerAhead
import io.github.glacier_jellyfin.androidtv.trailer.trailerPage
import java.time.Duration
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * TV-S9: the movie page makes the YouTube player's shell ahead, one web view at a time, only while started, and lets it
 * go when the page is left, unless the trailer screen is taking it. The shell names no title: YouTube learns which video
 * only when Trailer is pressed (Nixie's rule for outside services). Real web views (Robolectric's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrailerWarmupTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()

    /** Every web view the warm-up made, in order. */
    private val made = mutableListOf<WebView>()

    @Before
    fun setUp() {
        TrailerWarmup.make = { c, l -> makeYouTubePlayerAhead(c, l).also { made += it } }
    }

    @After
    fun tearDown() {
        TrailerWarmup.releaseClaimed()
        TrailerWarmup.release()
        settle()
        TrailerWarmup.make = ::makeYouTubePlayerAhead
    }

    /** The release's grace runs out. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TrailerWarmup.RELEASE_GRACE_MS + 100))
    }

    /** Everything the web view was asked to load or run: the page, and any script. */
    private fun requests(webView: WebView): String = shadowOf(webView).let {
        it.lastLoadDataWithBaseURL?.data.orEmpty() + "\n" + it.lastEvaluatedJavascript.orEmpty()
    }

    @Test
    fun `the movie page makes a shell that names no title, the video only on the press`() {
        // Through the page's own composable, as the movie page calls it once it is drawn.
        compose.setContent { NixieTrailerWarmup(details(localCount = 0)) }
        compose.waitForIdle()

        val shell = made.single()
        assertFalse("the video's id went out before the press", VIDEO in requests(shell))
        assertTrue(requests(shell).contains("var videoId = null;"))

        // Trailer pressed: the trailer screen takes the shell and names the video, only now.
        val warm = TrailerWarmup.take()!!
        assertSame(shell, warm.webView)
        warm.webView.evaluateJavascript("startVideo(\"$VIDEO\");", null)
        assertTrue(requests(shell).contains(VIDEO))
    }

    @Test
    fun `every title's shell is the same page`() {
        TrailerWarmup.prepare(context, started = true)
        val first = requests(made.single())
        TrailerWarmup.release()
        settle()
        TrailerWarmup.prepare(context, started = true)
        assertEquals(2, made.size)
        assertEquals(first, requests(made[1]))
        // The page made without a shell, for a trailer opened directly, still names its video and plays at once.
        assertTrue(trailerPage(VIDEO, "").let { it.contains(VIDEO) && it.contains("autoplay: 1") })
    }

    @Test
    fun `one shell at a time, and nothing while the page is not started`() {
        TrailerWarmup.prepare(context, started = false)
        assertFalse(TrailerWarmup.ready)
        TrailerWarmup.prepare(context, started = true)
        TrailerWarmup.prepare(context, started = true)
        assertEquals(1, made.size)
        val page = shadowOf(made.single()).lastLoadDataWithBaseURL.data
        assertTrue(page.contains("autoplay: 0") && page.contains("var startOnReady = false;"))
    }

    @Test
    fun `the page leaving lets it go, unless the trailer screen is taking it`() {
        TrailerWarmup.prepare(context, started = true)
        TrailerWarmup.release()
        settle()
        assertTrue(shadowOf(made[0]).wasDestroyCalled())
        assertFalse(TrailerWarmup.ready)

        // Claimed by the trailer screen: the page leaving keeps it; taken, it is the screen's.
        TrailerWarmup.prepare(context, started = true)
        TrailerWarmup.claim()
        TrailerWarmup.release()
        settle()
        assertFalse(shadowOf(made[1]).wasDestroyCalled())
        assertSame(made[1], TrailerWarmup.take()!!.webView)
        TrailerWarmup.releaseClaimed()
        TrailerWarmup.release()
        settle()
        assertFalse(shadowOf(made[1]).wasDestroyCalled())

        // Claimed, then the trailer screen closes without taking it.
        TrailerWarmup.prepare(context, started = true)
        TrailerWarmup.claim()
        TrailerWarmup.releaseClaimed()
        assertTrue(shadowOf(made[2]).wasDestroyCalled())
    }

    @Test
    fun `the page stopping just before the trailer screen claims it keeps it for that screen`() {
        // As on the emulator: pressing Trailer stops the movie page 60 ms before the trailer screen is composed.
        TrailerWarmup.prepare(context, started = true)
        TrailerWarmup.release()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        TrailerWarmup.claim()
        settle()
        assertFalse(shadowOf(made.single()).wasDestroyCalled())
        assertSame(made.single(), TrailerWarmup.take()!!.webView)
    }

    @Test
    fun `the shell's reports before the press are kept, then handed on as the named video's`() {
        TrailerWarmup.prepare(context, started = true)
        val warm = TrailerWarmup.take()!!
        val bridge = shadowOf(warm.webView).getJavascriptInterface("Glacier")
        fun call(name: String, vararg args: Any) =
            bridge.javaClass.methods.first { it.name == name }.apply { isAccessible = true }.invoke(bridge, *args)
        call("onStep", "YouTube API")
        call("onStep", "player ready")
        call("onStep", "not a step")
        call("onProgress", 1000.0, 60000.0)
        call("onError", 2)
        shadowOf(Looper.getMainLooper()).idle()

        val seen = Recorder()
        warm.relay.attach(seen, VIDEO)
        assertEquals(listOf("YouTube API", "player ready"), seen.steps)
        assertEquals(0, seen.progress)
        assertEquals(emptyList<Int>(), seen.errors)
        call("onState", 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(VIDEO to 1), seen.states)
    }

    @Test
    fun `a title with a trailer file on the server makes nothing ahead`() {
        assertTrue(makesTrailerAhead(details(localCount = 0)))
        assertFalse(makesTrailerAhead(details(localCount = 1)))
    }

    private fun details(localCount: Int) = ItemDetails(
        item = MediaItem(
            id = UUID(0, 1), kind = ItemKind.Movie, title = "Film", sortName = null, year = 2020,
            communityRating = null, officialRating = null, runtimeMinutes = 100, runtimeMs = 6_000_000,
            genres = emptyList(), overview = null, parentTitle = null, seriesId = null, seasonNumber = null,
            episodeNumber = null, progress = null, remainingMinutes = null, unwatchedCount = null,
            isFavorite = false, played = false, childCount = null, quality = null, posterUrl = null,
            thumbUrl = null, backdropUrl = null,
        ),
        cast = emptyList(),
        tracks = null,
        trailers = Trailers(localCount = localCount, youTube = listOf(YouTubeTrailer(VIDEO, null))),
        seriesId = null,
        seasonId = null,
        premiereDate = null,
        seasonCount = null,
        episodeCount = null,
    )

    private class Recorder : YouTubeListener {
        val steps = mutableListOf<String>()
        val states = mutableListOf<Pair<String, Int>>()
        val errors = mutableListOf<Int>()
        var progress = 0

        override fun onYouTubeState(videoId: String, state: Int) {
            states += videoId to state
        }

        override fun onYouTubeProgress(
            videoId: String,
            positionMs: Long,
            durationMs: Long,
        ) {
            progress++
        }

        override fun onYouTubeSubtitles(
            videoId: String,
            tracks: List<TrailerSubtitle>,
            shown: String?,
        ) = Unit

        override fun onYouTubeError(videoId: String, code: Int) {
            errors += code
        }

        override fun onYouTubeStep(videoId: String, step: String) {
            steps += step
        }
    }

    private companion object {
        const val VIDEO = "vid00000001"
    }
}
