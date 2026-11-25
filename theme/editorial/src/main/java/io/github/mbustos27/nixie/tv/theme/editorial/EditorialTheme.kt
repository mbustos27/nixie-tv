// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.editorial

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.mbustos27.nixie.tv.designsystem.FocusLook
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeSpec
import io.github.mbustos27.nixie.tv.designsystem.NixieTokens

/**
 * Editorial's tokens: an ink page, one 8 dp radius and no oblong pills, flat tints instead of glass (little chrome),
 * hot pink for what you can act on, and a focus frame like a printer's double rule: a paper-white rule on the element
 * and a band of pink outside it, with no glow. Headings are set in the serif.
 */
val EditorialTokens = NixieTokens(
    void = EditorialPalette.Ink,
    deep = EditorialPalette.Panel,
    // Flat tints of white over the ink, as the desktop theme's chips: a field or a button is a ruled box.
    glassFill = Color(0x12FFFFFF),
    glassBorder = Color(0x29FFFFFF),
    glassFill2 = Color(0x1AFFFFFF),
    glassBorder2 = Color(0x3DFFDCD2),
    ice = EditorialPalette.Text,
    mist = EditorialPalette.TextSecondary,
    accentMain = EditorialPalette.Pink,
    accentDeep = EditorialPalette.AccentDeep,
    radius = 8.dp,
    pill = EditorialShape,
    focus = FocusLook(
        inner = EditorialPalette.Paper,
        outer = EditorialPalette.Pink,
        innerWidth = 3.dp,
        glowWidth = 7.dp,
        glowStrokes = 1,
    ),
    ground = EditorialPalette.InkDeep,
    // Glacier's display text (the hero's title, the setup headings, page titles) in the serif, as the row heads.
    displayFont = EditorialType.Serif,
)

/** Editorial: a magazine on ink, type-led, serif headings, hairline rules and the sunset rule. */
val EditorialTheme = NixieThemeSpec(
    id = NixieThemeId.Editorial,
    tokens = EditorialTokens,
    slots = EditorialSlots,
)
