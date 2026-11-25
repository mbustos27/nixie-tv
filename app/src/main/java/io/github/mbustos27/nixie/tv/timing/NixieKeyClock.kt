// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.timing

import android.os.SystemClock
import android.view.KeyEvent

/**
 * TV-S13 (a check of 10101 on a box: Movies' first row took nearly three times as long by the stopwatch as from the
 * page's opening by the log): where the time goes from the remote's OK to what it opens, so the detailed log covers
 * the whole wait (design rule 10). The last OK (Enter, the D-pad's centre, A) is noted as the input system dated it,
 * with how long it waited for the main thread, when it came up (a click happens then) and when it opened a screen.
 * Times are [SystemClock.uptimeMillis], the clock key events carry. Main thread only.
 */
object NixieKeyClock {
    /**
     * One OK: [downAt] when it went down, [queuedMs] how long that waited before the app saw it (the main thread was
     * busy), [upAt] when it came up, [navigatedAt] when it opened a screen; null until then.
     */
    data class Press(
        val seq: Int,
        val downAt: Long,
        val queuedMs: Long,
        val upAt: Long? = null,
        val navigatedAt: Long? = null,
    )

    private var seq = 0

    @Volatile
    private var last: Press? = null

    /** A key the app saw ([now]: when it saw it); only an OK's first down and its up count. */
    fun seen(event: KeyEvent, now: Long = SystemClock.uptimeMillis()) =
        seen(event.keyCode, event.action, event.repeatCount, event.eventTime, now)

    /** As [seen], from the event's parts (the tests make no KeyEvent). */
    fun seen(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        eventTime: Long,
        now: Long,
    ) {
        if (keyCode !in Confirm) return
        when {
            action == KeyEvent.ACTION_DOWN && repeatCount == 0 -> {
                last = Press(++seq, eventTime, (now - eventTime).coerceAtLeast(0))
                NixieFrameWatch.start(now)
            }

            action == KeyEvent.ACTION_UP -> last = last?.let { if (it.upAt == null) it.copy(upAt = eventTime) else it }
        }
    }

    /** A screen was opened (the navigation's own call). */
    fun navigated(now: Long = SystemClock.uptimeMillis()) {
        last = last?.let { if (it.navigatedAt == null) it.copy(navigatedAt = now) else it }
    }

    /** The last OK, if it came up (or went down) at most [withinMs] before [now]. */
    fun recent(now: Long = SystemClock.uptimeMillis(), withinMs: Long = WINDOW_MS): Press? =
        last?.takeIf { now - (it.upAt ?: it.downAt) in 0..withinMs }

    /** Forgets the last OK (the tests). */
    fun reset() {
        last = null
    }

    /** An OK that opened something more than this long before it is not what is being timed. */
    const val WINDOW_MS = 15_000L

    private val Confirm = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
    )
}
