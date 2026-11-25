// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import org.junit.Assert.assertEquals
import org.junit.Test

/** Design rule 10: a start's steps and its first frame, as durations, in one line. */
class StartTimingTest {

    private var now = 1_000L
    private val lines = mutableListOf<String>()
    private fun timing() = StartTiming("Trailer start", clock = { now }, log = { lines += it })

    @Test
    fun `each step once, in order, then one line at the first frame`() {
        val timing = timing()
        now += 410
        timing.mark("details")
        now += 1_690
        timing.mark("page")
        timing.mark("details")
        now += 7_700
        timing.kind = "YouTube"
        timing.firstFrame()
        now += 5_000
        timing.mark("late")
        timing.firstFrame()

        assertEquals(listOf("Trailer start (YouTube): details 0.41 s, page 2.10 s, first frame 9.80 s"), lines)
    }

    @Test
    fun `a start without steps still says how long it took`() {
        val timing = timing()
        now += 250
        timing.firstFrame()
        assertEquals(listOf("Trailer start: first frame 0.25 s"), lines)
    }
}
