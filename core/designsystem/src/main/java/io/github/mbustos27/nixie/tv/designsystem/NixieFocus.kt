// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.designsystem

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

/**
 * A theme's focus frame ([FocusLook]), in place of Glacier's accent border and ring: the glow's strokes, widest and
 * faintest first, around the element, then the [FocusLook.inner] border on it. Unfocused, only [unfocusedBorder].
 */
fun Modifier.themedFocusFrame(
    focused: Boolean,
    shape: Shape,
    look: FocusLook,
    unfocusedBorder: Color = Color.Transparent,
    borderWidth: Int = 2,
): Modifier {
    // TV-S2: no invisible border drawn on every card at rest.
    if (!focused) return if (unfocusedBorder.alpha == 0f) this else border(borderWidth.dp, unfocusedBorder, shape)
    return this
        .drawWithContent {
            val glow = look.glowWidth.toPx()
            val strokes = look.glowStrokes.coerceAtLeast(1)
            for (i in strokes downTo 1) {
                // Outer strokes wide and faint, inner ones narrow and brighter: a neon falloff with no blur.
                val reach = glow * i / strokes
                val outline = shape.createOutline(Size(size.width + reach, size.height + reach), layoutDirection, this)
                translate(-reach / 2, -reach / 2) {
                    drawOutline(outline, look.outer.copy(alpha = look.outer.alpha * (0.55f / i)), style = Stroke(reach))
                }
            }
            drawContent()
        }
        .border(look.innerWidth.coerceAtLeast(borderWidth.dp), look.inner, shape)
}
