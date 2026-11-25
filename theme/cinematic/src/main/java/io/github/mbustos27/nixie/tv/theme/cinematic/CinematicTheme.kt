// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.cinematic

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.mbustos27.nixie.tv.designsystem.FocusLook
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeSpec
import io.github.mbustos27.nixie.tv.designsystem.NixieTokens

/** Cinematic's one radius: every rounded shape, and the pill that is never oblong. */
internal val CinematicRadius = 8.dp
internal val CinematicShape = RoundedCornerShape(CinematicRadius)

/**
 * Cinematic's tokens: Night under everything, warm white text, hot pink for what you can act on, one 8 dp radius, glass
 * that is near white at Glacier's alphas (buttons, fields) and a dense plum island for the bar, and a focus frame you
 * see from the couch: a 3 dp white rim inside a wide pink glow, on top of Glacier's focus zoom.
 */
val CinematicTokens = NixieTokens(
    void = CinematicPalette.Night,
    deep = CinematicPalette.ArtGround,
    // Glacier's alphas in a warm white: controls read as lit glass on the dark stage.
    glassFill = Color(0x17FFEDEF),
    glassBorder = Color(0x2EFFEDEF),
    glassFill2 = Color(0x21FFEDEF),
    glassBorder2 = Color(0x47FFEDEF),
    ice = CinematicPalette.Text,
    mist = CinematicPalette.TextSecondary,
    accentMain = CinematicPalette.Pink,
    accentDeep = CinematicPalette.DeepRose,
    radius = CinematicRadius,
    pill = CinematicShape,
    focus = FocusLook(
        inner = CinematicPalette.Rim,
        outer = CinematicPalette.Pink,
        innerWidth = 3.dp,
        glowWidth = 16.dp,
        glowStrokes = 4,
    ),
    ground = CinematicPalette.Ground,
    // Windows' Cinematic grows a focused still by a tenth (Glacier: 1.07).
    focusZoom = 1.1f,
)

/** Cinematic: the backdrop stage. Full-bleed art under a scrim, the title over it, the rows beneath. */
val CinematicTheme = NixieThemeSpec(
    id = NixieThemeId.Cinematic,
    tokens = CinematicTokens,
    slots = CinematicSlots,
)
