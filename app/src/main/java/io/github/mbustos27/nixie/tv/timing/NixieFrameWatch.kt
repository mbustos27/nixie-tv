// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.timing

import android.os.SystemClock
import android.view.Choreographer

/**
 * TV-S13: whether the main thread kept a path waiting (composing, laying out, decoding on it): for [WATCH_MS] after
 * each OK it notes every frame that came [LONG_MS] or more after the one before, so a timing line can say how much of
 * a wait was the main thread's ([between]). Off unless [enabled] (debug builds and detailed logging; the navigation
 * turns it on), and then only for those seconds after a key: a frame callback per frame, nothing drawn.
 * Main thread only.
 */
object NixieFrameWatch {
    /** The main thread's long frames in a span: how many, the longest and all of them together, in ms. */
    data class Stalls(
        val count: Int,
        val longestMs: Long,
        val totalMs: Long,
    ) {
        override fun toString(): String = when (count) {
            0 -> "no frame over $LONG_MS ms"
            1 -> "1 frame over $LONG_MS ms, $longestMs ms"
            else -> "$count frames over $LONG_MS ms, longest $longestMs ms, $totalMs ms in all"
        }
    }

    @Volatile
    var enabled = false

    private var until = 0L
    private var lastAt = 0L
    private var running = false

    /** Long frames as (end, length) in uptime ms, the newest last. */
    private val frames = ArrayDeque<Pair<Long, Long>>()

    private val tick = Choreographer.FrameCallback { frame(SystemClock.uptimeMillis()) }

    /** An OK at [now]: the watch runs (on) until [WATCH_MS] after it. */
    fun start(now: Long = SystemClock.uptimeMillis()) {
        if (!enabled) return
        until = now + WATCH_MS
        if (running) return
        running = true
        lastAt = now
        Choreographer.getInstance().postFrameCallback(tick)
    }

    /** A frame came at [now]: noted if long; the next one is asked for while the watch runs. */
    internal fun frame(now: Long) {
        val gap = now - lastAt
        if (gap >= LONG_MS) {
            synchronized(frames) {
                frames.addLast(now to gap)
                while (frames.size > KEPT) frames.removeFirst()
            }
        }
        lastAt = now
        if (now < until) {
            Choreographer.getInstance().postFrameCallback(tick)
        } else {
            running = false
        }
    }

    /** The long frames that ended between [from] and [to] (uptime ms). */
    fun between(from: Long, to: Long): Stalls {
        val inSpan = synchronized(frames) { frames.filter { (end, _) -> end in from..to } }
        return Stalls(inSpan.size, inSpan.maxOfOrNull { it.second } ?: 0, inSpan.sumOf { it.second })
    }

    /** Forgets what it noted (the tests). */
    internal fun reset() {
        synchronized(frames) { frames.clear() }
        lastAt = 0L
    }

    /** How long the watch runs after an OK. */
    const val WATCH_MS = 12_000L

    /** A frame this long or longer is a stall (about three frames at 60 Hz). */
    const val LONG_MS = 50L

    private const val KEPT = 400
}
