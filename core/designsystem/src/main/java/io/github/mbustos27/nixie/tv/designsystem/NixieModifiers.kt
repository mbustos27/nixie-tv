// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.dp

/** A [NavEntryLook]'s underline under a chip or an entry; nothing when [color] is Unspecified. */
fun Modifier.underline(color: Color): Modifier = if (!color.isSpecified) {
    this
} else {
    drawBehind {
        val h = 3.dp.toPx()
        val inset = 14.dp.toPx()
        drawRect(color, Offset(inset, size.height - h - 4.dp.toPx()), Size(size.width - 2 * inset, h))
    }
}

/** The theme's frame for an item page's Play area ([ItemSlots.playArea]); unchanged when the theme has none. */
fun Modifier.nixiePlayArea(): Modifier = NixieTheme.slots.item.playArea?.let { frame -> this.frame() } ?: this

/** The theme's chrome for the player's cards and panels ([PlayerSlots.panelChrome]), else [glacier]. */
fun Modifier.nixiePanelChrome(glacier: Modifier.() -> Modifier): Modifier =
    NixieTheme.slots.player.panelChrome?.let { chrome -> this.chrome() } ?: this.glacier()

/** [shadow] (a soft drop shadow, an offscreen blur) only where the box affords it ([VisualCapabilities.shadows]). */
fun Modifier.nixieShadow(shadow: Modifier.() -> Modifier): Modifier =
    if (NixieVisuals.current.shadows) this.shadow() else this
