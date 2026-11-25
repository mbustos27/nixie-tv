// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.editorial

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.glacier_jellyfin.androidtv.core.designsystem.GoogleSans

/**
 * Editorial's type: serif headings and figures over Glacier's Google Sans for everything else. The serif is the
 * system's own (Noto Serif on Android TV), so the theme adds no font file; a bundled display serif is a later step.
 * Every text reads in its own direction, as Glacier's do.
 */
object EditorialType {
    /** The serif: the theme's own headings and figures, and Glacier's display text through the displayFont token. */
    val Serif: FontFamily = FontFamily.Serif

    /** A section heading: a row's title, the masthead. */
    fun heading(size: Int, weight: FontWeight = FontWeight.Bold) = TextStyle(
        fontFamily = Serif,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (size * 1.12f).sp,
        letterSpacing = (-0.005).em,
        textDirection = TextDirection.Content,
    )

    /** The standfirst: the italic line under a heading that says what the page holds ("320 titles"). */
    fun standfirst(size: Int) = TextStyle(
        fontFamily = Serif,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Normal,
        fontSize = size.sp,
        lineHeight = (size * 1.3f).sp,
        textDirection = TextDirection.Content,
    )

    /** A colophon's line: small capitals, widely spaced, for facts ("2026 | 1 H 58 MIN | DRAMA"). */
    fun colophon(size: Int) = TextStyle(
        fontFamily = GoogleSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = size.sp,
        letterSpacing = 0.12.em,
        textDirection = TextDirection.Content,
    )

    /** Small capitals beside or over a heading ("9 TITLES"). */
    fun kicker(size: Int) = TextStyle(
        fontFamily = GoogleSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = size.sp,
        letterSpacing = 0.16.em,
        textDirection = TextDirection.Content,
    )

    /** Big figures in the serif, lining and of equal width so a code does not shift as it changes. */
    fun figures(size: Int) = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = size.sp,
        lineHeight = (size * 1.15f).sp,
        fontFeatureSettings = "lnum, tnum",
        textDirection = TextDirection.Ltr,
    )
}
