// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.timing

import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TV-S13: the detailed log's waits start at the remote's OK, as the input system dated it, with how long it waited for
 * the main thread; a screen opened by it says so; another key in between does not move it.
 */
class NixieKeyClockTest {

    @After
    fun tearDown() {
        NixieKeyClock.reset()
        NixieFrameWatch.reset()
    }

    private fun key(
        code: Int,
        action: Int,
        at: Long,
        seenAt: Long,
        repeat: Int = 0,
    ) = NixieKeyClock.seen(code, action, repeat, at, seenAt)

    @Test
    fun `an OK is dated as the input system dated it, with its wait for the main thread`() {
        key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, at = 1_000, seenAt = 1_350)
        key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, at = 1_050, seenAt = 1_360, repeat = 1)
        key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, at = 1_090, seenAt = 1_370)
        NixieKeyClock.navigated(now = 1_400)
        NixieKeyClock.navigated(now = 1_900) // a second screen: the first one counts
        // Another key in between leaves the OK as it was.
        key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, at = 1_500, seenAt = 1_500)
        val press = NixieKeyClock.recent(now = 2_000)!!
        assertEquals(1_000, press.downAt)
        assertEquals(350, press.queuedMs)
        assertEquals(1_090L, press.upAt)
        assertEquals(1_400L, press.navigatedAt)
        // Long after, it is not what is being timed.
        assertNull(NixieKeyClock.recent(now = 1_090 + NixieKeyClock.WINDOW_MS + 1))
    }

    @Test
    fun `a screen's wait from the OK, split, with the main thread's long frames`() {
        val lines = mutableListOf<String>()
        var up = 2_150L
        val wait = NixieKeyWait(
            "Trailer",
            { lines += it },
            uptime = { up },
            stalls = { _, _ -> NixieFrameWatch.Stalls(1, 640, 640) },
            press = NixieKeyClock.Press(seq = 1, downAt = 2_000, queuedMs = 3, upAt = 2_080, navigatedAt = 2_100),
        )
        up = 5_400
        wait.done()
        wait.done()
        assertEquals(
            listOf(
                "Trailer from the key: 3400 ms (the key waited 3 ms for the main thread, came up at 80 ms, the " +
                    "screen opened at 100 ms, made at 150 ms); main thread: 1 frame over 50 ms, 640 ms",
            ),
            lines,
        )
    }

    @Test
    fun `the main thread's long frames in a span`() {
        NixieFrameWatch.reset()
        // Frames every 16 ms, then one 300 ms late, then one 80 ms late.
        listOf(16L, 32, 48, 348, 364, 444, 460).forEach(NixieFrameWatch::frame)
        assertEquals(NixieFrameWatch.Stalls(2, 300, 380), NixieFrameWatch.between(0, 500))
        assertEquals(NixieFrameWatch.Stalls(1, 80, 80), NixieFrameWatch.between(400, 500))
        assertEquals("no frame over 50 ms", NixieFrameWatch.between(0, 40).toString())
    }
}
