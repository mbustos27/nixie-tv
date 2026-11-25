// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.drivein

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.mbustos27.nixie.tv.designsystem.FocusLook
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeSpec
import io.github.mbustos27.nixie.tv.designsystem.NixieTokens

/**
 * Drive-in's tokens: the desert-sunset palette, one 8 dp radius and no oblong pills, warm glass on the chrome only,
 * hot pink for what you can act on, and a two-tone neon focus ring (a near-white core in a pink glow).
 */
val DriveInTokens = NixieTokens(
    void = DriveInPalette.Night,
    deep = DriveInPalette.Tile,
    // Glacier's alphas, in the sunset's peach white instead of ice blue.
    glassFill = Color(0x17FFDCD2),
    glassBorder = Color(0x2EFFDCD2),
    glassFill2 = Color(0x21FFDCD2),
    glassBorder2 = Color(0x47FFDCD2),
    ice = DriveInPalette.Text,
    mist = DriveInPalette.TextSecondary,
    accentMain = DriveInPalette.Pink,
    // Glacier writes Night on the deep accent (primary buttons): Rose lifted to 5.1:1, where Crimson gave 3.0:1.
    accentDeep = DriveInPalette.RoseLifted,
    radius = 8.dp,
    pill = RoundedCornerShape(8.dp),
    focus = FocusLook(
        inner = DriveInPalette.NeonCore,
        outer = DriveInPalette.Pink,
        innerWidth = 2.dp,
        glowWidth = 12.dp,
        glowStrokes = 3,
    ),
    ground = DriveInPalette.Night2,
)

/** Drive-in, Nixie's default theme. */
val DriveInTheme = NixieThemeSpec(
    id = NixieThemeId.DriveIn,
    tokens = DriveInTokens,
    slots = DriveInSlots,
)
