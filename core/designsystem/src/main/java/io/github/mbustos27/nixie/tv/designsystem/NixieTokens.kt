// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp

/**
 * What a theme changes in Glacier's design system: `GlacierColors`, `Accent`, `GlacierShapes`, `PillShape`,
 * `GlacierBackground` and the focus frame read these through their `// Nixie:` hooks. [Color.Unspecified],
 * [Dp.Unspecified] or null keeps Glacier's value, so a theme sets only what it changes.
 */
@Immutable
data class NixieTokens(
    /** The page ground (Glacier's Void); also the text on the accent. */
    val void: Color = Color.Unspecified,
    /** A raised sheet (Glacier's Deep). */
    val deep: Color = Color.Unspecified,
    /**
     * Glacier's glass fills and borders (buttons, fields, code boxes, the navigation bar). A theme with glass on chrome
     * only gives these opaque or near-opaque values and draws its chrome's glass in its own slots.
     */
    val glassFill: Color = Color.Unspecified,
    val glassBorder: Color = Color.Unspecified,
    val glassFill2: Color = Color.Unspecified,
    val glassBorder2: Color = Color.Unspecified,
    /** Text (Glacier's Ice) and secondary text (Mist). */
    val ice: Color = Color.Unspecified,
    val mist: Color = Color.Unspecified,
    /**
     * "You can act on this", in place of the accent the profile picked (Glacier's nine accents); [accentDeep] is its
     * deep shade for gradients. With a theme accent the settings' accent picker has nothing to change.
     */
    val accentMain: Color = Color.Unspecified,
    val accentDeep: Color = Color.Unspecified,
    /** One corner radius for every rounded shape (Glacier's 8, 14 and 22). */
    val radius: Dp = Dp.Unspecified,
    /** What Glacier's pill shape becomes (buttons, chips, the navigation bar, the hero's position marks). */
    val pill: Shape? = null,
    /** The focus frame; Glacier draws an accent border and a soft accent ring. */
    val focus: FocusLook? = null,
    /** The darkest ground under pages without a picture (Glacier's near black). */
    val ground: Color = Color.Unspecified,
    /**
     * The face of Glacier's display text (`GlacierText.display`: the hero's title, setup headings, page titles);
     * null keeps Google Sans. Added 2026-11-21 for Editorial's serif.
     */
    val displayFont: FontFamily? = null,
    /** How much a focused element grows (Glacier's 1.07); NaN keeps Glacier's. Added 2026-11-21 for Cinematic's 1.1. */
    val focusZoom: Float = Float.NaN,
) {
    /** Whether the theme brings its own accent, so the profile's accent choice does not show. */
    val ownAccent: Boolean get() = accentMain != Color.Unspecified
}

/**
 * A theme's focus frame: an [inner] border hugging the element and an [outer] glow drawn as [glowStrokes] stacked
 * strokes of falling alpha out to [glowWidth], never a blur (the plan's neon rule: a blur costs a low-RAM box its
 * whole graphics budget). Focus must never rely on colour alone, so the frame changes shape, not only hue.
 */
@Immutable
data class FocusLook(
    val inner: Color,
    val outer: Color,
    val innerWidth: Dp,
    val glowWidth: Dp,
    val glowStrokes: Int = 3,
)
