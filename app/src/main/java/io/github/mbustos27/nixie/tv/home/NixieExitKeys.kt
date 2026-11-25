// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** The keys that press Quit. */
private val QuitKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * TV-S10: "Quit Nixie?" owns the remote from the moment Back asked for it, before it has drawn or taken the focus (its
 * Quit takes the focus a frame after it appears). Until the focus is in the dialog, OK quits, as Quit would, and no
 * other key reaches the page behind it (the top bar's entry or the card that had the focus); Back and Escape go on, so
 * Back still closes the dialog. Once the focus is in the dialog, its buttons have the keys. Read on the key's arrival,
 * so a Back and an OK in the same frame already see the dialog asked for.
 *
 * On the page's outermost layout, so it sees a key before anything on the page does.
 */
fun Modifier.nixieExitKeys(
    asked: () -> Boolean,
    inDialog: () -> Boolean,
    quit: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    when {
        !asked() || inDialog() -> false

        event.key == Key.Back || event.key == Key.Escape -> false

        event.key in QuitKeys -> {
            if (event.type == KeyEventType.KeyDown) quit()
            true
        }

        else -> true
    }
}
