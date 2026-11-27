// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Nixie (tv-1.0.1): the clock of "Ends at" (the player's controls and the item page) in the TV's own 12- or 24-hour
 * setting; it was always 24-hour.
 */
@Composable
fun rememberEndsAtClock(): DateTimeFormatter {
    val twentyFour = DateFormat.is24HourFormat(LocalContext.current)
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return remember(twentyFour, locale) { endsAtClock(twentyFour, locale) }
}

/** 19:05 as "19:05" with [twentyFour], else "7:05 PM" in [locale]'s words. */
internal fun endsAtClock(twentyFour: Boolean, locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (twentyFour) "HH:mm" else "h:mm a", locale)
