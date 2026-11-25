// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.cinematic

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/** WCAG 2 relative luminance of an sRGB channel value 0..1. */
private fun linear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

/** WCAG 2 relative luminance of an opaque colour (alpha ignored). */
fun luminance(color: Color): Double = 0.2126 * linear(color.red.toDouble()) + 0.7152 * linear(color.green.toDouble()) +
    0.0722 * linear(color.blue.toDouble())

/** Relative luminance of a packed ARGB pixel. */
fun luminance(argb: Int): Double = luminance(Color(argb))

fun contrast(l1: Double, l2: Double): Double = (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)

fun contrast(a: Color, b: Color): Double = contrast(luminance(a), luminance(b))

/** [this] drawn over the opaque [background] (source over, in sRGB, as the canvas blends). */
infix fun Color.over(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
    )
}

/** The text rule of every Nixie theme. */
const val TEXT_CONTRAST = 4.5
