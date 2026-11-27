// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Nixie (tv-1.0.1): the server's bandwidth test (GET /Playback/BitrateTest?Size=n) on 127.0.0.1, over a link of
 * [bitsPerSecond] (0: as fast as loopback goes) from a server that waits [firstByteMs] before it answers. [notFound]
 * answers 404 instead (a test that fails at once). Every other path answers 404 too. Plain sockets: nothing is fetched.
 */
internal class FakeLink(
    private val bitsPerSecond: Long = 0,
    private val firstByteMs: Long = 0,
    private val notFound: Boolean = false,
    /** The link's speed from the second test on (a link that slows down); null: [bitsPerSecond]. */
    private val laterBitsPerSecond: Long? = null,
) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    /** The server's address, for [FakePlaybackServer]. */
    val address = "http://127.0.0.1:${socket.localPort}"

    /** Bandwidth tests asked for. */
    val tests = AtomicInteger()

    init {
        thread(isDaemon = true, name = "fake-link") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(client) } }
            }
        }
    }

    private fun serve(client: Socket) = client.use {
        it.soTimeout = READ_TIMEOUT_MS
        val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = input.readLine() ?: return
        while (input.readLine()?.isNotEmpty() == true) Unit
        val out = it.getOutputStream()
        val path = request.split(' ').getOrNull(1).orEmpty()
        val size = SIZE.find(path)?.groupValues?.get(1)?.toIntOrNull()
        if (notFound || size == null || !path.startsWith("/Playback/BitrateTest")) {
            out.head("404 Not Found", 0)
            return
        }
        val rate = if (tests.incrementAndGet() > 1) laterBitsPerSecond ?: bitsPerSecond else bitsPerSecond
        Thread.sleep(firstByteMs)
        out.head("200 OK", size)
        val chunk = ByteArray(CHUNK)
        val started = System.nanoTime()
        var sent = 0L
        while (sent < size) {
            val n = minOf(CHUNK.toLong(), size - sent).toInt()
            out.write(chunk, 0, n)
            out.flush()
            sent += n
            if (rate > 0) {
                // Paced against the start, so a sleep that runs long is made up by the next ones.
                val due = started + sent * 8 * 1_000_000_000L / rate
                val wait = due - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
        }
    }

    private fun OutputStream.head(status: String, length: Int) {
        write(
            (
                "HTTP/1.1 $status\r\nContent-Type: application/octet-stream\r\nContent-Length: $length\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.ISO_8859_1),
        )
        flush()
    }

    override fun close() = socket.close()

    private companion object {
        const val CHUNK = 4_096
        const val READ_TIMEOUT_MS = 10_000
        val SIZE = Regex("[?&]Size=(\\d+)", RegexOption.IGNORE_CASE)
    }
}
