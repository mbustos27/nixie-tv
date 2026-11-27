// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import java.time.LocalTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.0.1 queue: "Ends at" was always 24-hour; it follows the TV's 12- or 24-hour setting. */
class EndsAtClockTest {
    private val evening = LocalTime.of(19, 5)

    @Test
    fun `a 24-hour TV keeps 19-05`() {
        assertEquals("19:05", evening.format(endsAtClock(twentyFour = true, Locale.US)))
        assertEquals("07:05", LocalTime.of(7, 5).format(endsAtClock(twentyFour = true, Locale.GERMANY)))
    }

    @Test
    fun `a 12-hour TV reads 7-05 PM`() {
        assertEquals("7:05 PM", evening.format(endsAtClock(twentyFour = false, Locale.US)))
        assertEquals("12:30 AM", LocalTime.of(0, 30).format(endsAtClock(twentyFour = false, Locale.US)))
    }
}
