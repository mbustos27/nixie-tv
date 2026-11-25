// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import android.app.Application
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.size.Scale
import com.sun.net.httpserver.HttpServer
import io.github.glacier_jellyfin.androidtv.GlacierApplication
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TV-S13's finding: at a cold Movies open the grid's cards (composed about two rows ahead) and the rows fetched ahead
 * (NixiePrefetchArt) asked for the same posters at the same moment, and a slow server was asked for each twice. Here
 * the app's own image loader (GlacierApplication's) against a slow local server: a row of posters, each asked by its
 * card (at the size it is drawn) and by the fetch ahead (the grid's poster size) at once, is asked of the server once
 * per poster.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
// The pictures are decoded as on the box.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PosterOnceTest {

    private lateinit var server: HttpServer
    private lateinit var serving: ExecutorService
    private lateinit var main: ExecutorCoroutineDispatcher

    /** Requests the server answered, by the poster's item id. */
    private val asked = ConcurrentHashMap<String, AtomicInteger>()

    @Before
    fun setUp() {
        // Coil starts its requests on the main dispatcher; Robolectric's main thread is the test's own, blocked below.
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        serving = Executors.newCachedThreadPool()
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = serving
            createContext("/Items/") { exchange ->
                val id = exchange.requestURI.path.split('/')[2]
                asked.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                // A slow server: the card asks while the fetch ahead is still coming.
                Thread.sleep(SLOW_MS)
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, PNG.size.toLong())
                exchange.responseBody.use { it.write(PNG) }
            }
            start()
        }
    }

    @After
    fun tearDown() {
        server.stop(0)
        serving.shutdownNow()
        Dispatchers.resetMain()
        main.close()
    }

    @Test
    fun `a poster the card and the fetch ahead ask for at once is asked of the server once`() =
        runBlocking(Dispatchers.IO) { ask() }

    private suspend fun ask() = coroutineScope {
        val context = RuntimeEnvironment.getApplication()
        val loader = GlacierApplication().newImageLoader(context)
        val ids = List(ROW) { UUID.randomUUID().toString() }
        val asks = ids.flatMap { id ->
            // NixieGridArt.poster's address, as the card and the rows ahead both ask for it.
            val url = "http://127.0.0.1:${server.address.port}/Items/$id/Images/Primary" +
                "?fillHeight=320&fillWidth=213&quality=96&format=Webp"
            listOf(
                ImageRequest.Builder(context).data(url).size(CARD_W, CARD_H).build(),
                ImageRequest.Builder(context).data(url).size(GRID_W, GRID_H).scale(Scale.FILL).build(),
            )
        }
        val results = asks.map { async { loader.execute(it) } }.awaitAll()

        val failed = results.filterIsInstance<ErrorResult>().map { it.throwable }
        assertTrue("every poster came: $failed", failed.isEmpty())
        assertEquals(ids.associateWith { 1 }, ids.associateWith { asked[it]?.get() ?: 0 })
    }

    private companion object {
        /** A row of the grid at 1080p. */
        const val ROW = 7
        const val SLOW_MS = 400L
        const val CARD_W = 216
        const val CARD_H = 324
        const val GRID_W = 240
        const val GRID_H = 360

        /** A 1 x 1 PNG. */
        val PNG: ByteArray = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=")
    }
}
