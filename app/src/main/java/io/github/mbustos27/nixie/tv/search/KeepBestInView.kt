// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.search

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * TV-S4 (review): search results arrive in steps, and a better match that comes a moment late goes in front. A lazy grid
 * keeps the card that was on top where it was, so a row of better matches would land above the screen. While the
 * focus is not in the grid, a new first result scrolls the grid back to the top; with the focus in the grid, the
 * focused card stays put. A page composed again (Back from a title) keeps its place.
 */
@Composable
fun KeepBestInView(
    grid: LazyGridState,
    firstKey: Any?,
    focusInGrid: () -> Boolean,
) {
    var shown by remember { mutableStateOf(firstKey) }
    LaunchedEffect(firstKey) {
        if (firstKey == shown) return@LaunchedEffect
        shown = firstKey
        if (firstKey != null && !focusInGrid()) grid.scrollToItem(0)
    }
}
