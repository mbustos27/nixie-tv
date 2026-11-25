// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/*
 * tv-gate-b (the basics gate, rule 12; a real box too): Down right after an A–Z jump did nothing. The jump's
 * page holds ten rows; Down along them reached its last row while the next page was still on its way (it is asked for
 * three rows before the end), found no row below and was dropped: the viewer had to press again. A library grid's Down
 * on the last row of what is loaded, more titles to come, is now held until they come, then goes down. A load that
 * ends with nothing new is the list's end: the Down is let go there, as any other key lets it go.
 */

/** A Down held on the last row of what a library grid has loaded. */
@Stable
class HeldDown internal constructor() {
    /** How many titles were loaded when the Down was held; null when none is held. */
    internal var heldAt by mutableStateOf<Int?>(null)

    /** A load ran while the Down was held (one that ends with nothing new is the list's end). */
    internal var loadSeen = false
}

@Composable
fun rememberHeldDown(): HeldDown = remember { HeldDown() }

/** The card [focusedKey] is on the screen, on the last row the grid has. */
private fun LazyGridState.onLoadedEnd(focusedKey: String?): Boolean {
    val info = layoutInfo
    val focused = info.visibleItemsInfo.firstOrNull { it.key.toString() == focusedKey } ?: return false
    val last = info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 } ?: return false
    return last.row == focused.row
}

/**
 * On a library grid: Down on the card [focusedKey] on the grid's last row while [more] titles are to come is held
 * (and [ask]s for them); any other key lets a held Down go. [count] is how many titles are loaded.
 */
fun Modifier.nixieHoldDown(
    held: HeldDown,
    grid: LazyGridState,
    focusedKey: () -> String?,
    count: () -> Int,
    more: () -> Boolean,
    ask: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    if (event.key != Key.DirectionDown) {
        held.heldAt = null
        return@onPreviewKeyEvent false
    }
    if (!more() || !grid.onLoadedEnd(focusedKey())) return@onPreviewKeyEvent false
    held.heldAt = count()
    held.loadSeen = false
    ask()
    true
}

/**
 * A held Down goes down once the titles below the focused card are in ([count] grew); while titles come in only
 * above it (a jump's rows on the way up), it asks again. A load that ended with nothing new, or one that [failed], lets
 * it go.
 */
@Composable
fun NixieHeldDown(
    held: HeldDown,
    grid: LazyGridState,
    focusedKey: () -> String?,
    count: Int,
    loading: Boolean,
    failed: Boolean,
    ask: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val heldAt = held.heldAt
    LaunchedEffect(heldAt, count, loading, failed) {
        if (heldAt == null) return@LaunchedEffect
        if (loading) held.loadSeen = true
        when {
            failed -> held.heldAt = null

            count == heldAt -> if (!loading && held.loadSeen) held.heldAt = null

            else -> {
                withFrameNanos { }
                if (grid.onLoadedEnd(focusedKey())) {
                    held.heldAt = count
                    held.loadSeen = false
                    ask()
                } else {
                    held.heldAt = null
                    focusManager.moveFocus(FocusDirection.Down)
                }
            }
        }
    }
}
