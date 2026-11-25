// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.drivein

import androidx.compose.ui.graphics.Color

/**
 * Drive-in's desert-sunset palette, the same values as Nixie for Windows (its docs\design\DESIGN.md and the
 * DriveIn.xaml theme). The colour rule: hot pink means "you can act on this", nixie orange means "a number you
 * read", and text on pink is Night.
 */
object DriveInPalette {
    val Coral = Color(0xFFEC6A79)
    val Sun = Color(0xFFF5A8A5)
    val Peach = Color(0xFFF7AA9C)
    val Pink = Color(0xFFF96985)
    val Rose = Color(0xFFE6385F)

    /** Rose, lifted so Night text on it reads at 5.1:1 (the primary buttons' fill). */
    val RoseLifted = Color(0xFFE8476A)
    val Crimson = Color(0xFFBC1E47)
    val Maroon = Color(0xFF500723)
    val Night = Color(0xFF1A0710)
    val Night2 = Color(0xFF12040A)
    val NeonCore = Color(0xFFFFF4EE)
    val NixieOrange = Color(0xFFFF7A50)

    /** A raised tile, the page text and its secondary line (DriveInTileBrush, DriveInPageText...). */
    val Tile = Color(0xFF2A0814)
    val Text = Color(0xFFF8EFF0)
    val TextSecondary = Color(0xFFD9C6C8)

    /** The sky bar (the top bar's glass): Night at 85% over the page, a faint lit edge. */
    val SkyBarTop = Color(0xD91C0512)
    val SkyBarBottom = Color(0xC21A050E)
    val SkyBarStroke = Color(0x1FFFFFFF)

    /** The marquee board behind row titles: near black with a maroon rim and a peach hairline. */
    val BoardTop = Color(0xFF130409)
    val BoardBottom = Color(0xFF0C0206)
    val BoardRim = Color(0xFF4A1830)
    val BoardHairline = Color(0x38F7AA9C)
    val Kicker = Color(0xFFF7AA9C)
}
