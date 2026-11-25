// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.editorial

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Editorial's ink palette: the dark variant of Nixie for Windows' Editorial theme (Editorial.xaml), a page of ink
 * rather than paper, because a living room at night is no place for a white screen. The colour rule is the shared
 * one: hot pink means "you can act on this", the warm numeral colour is "a number you read", coral is decoration,
 * and text on pink is ink.
 */
object EditorialPalette {
    /** The page: deep plum ink (EditorialGround). */
    val Ink = Color(0xFF160610)

    /** Below the page, where the ground darkens towards the bottom. */
    val InkDeep = Color(0xFF0E030A)

    /** A raised panel (EditorialPanel): the top bar's strip, the picture plate's mat. */
    val Panel = Color(0xFF2A0C18)

    /** The top bar's strip: the panel, nearly opaque, so its type reads over any picture. */
    val Bar = Color(0xF22A0C18)

    /** Paper white (EditorialFocusOuter, the arc's spark): the focus rule and a focused tab's fill. */
    val Paper = Color(0xFFFFF4EE)

    /** Body text (EditorialLitText) and the secondary line. */
    val Text = Color(0xFFFFEFF2)
    val TextSecondary = Color(0xFFC9B3B8)

    /** Numbers you read (EditorialNumber): Quick Connect's figures. */
    val Numeral = Color(0xFFFFC2AE)

    /** Kickers, the small capitals over and beside a heading (EditorialKicker). */
    val Kicker = Color(0xFFF7AA9C)

    /** Hairline rules: peach white at a quarter. */
    val Rule = Color(0x42FFDCD2)

    /** The picture plate's frame. */
    val PlateFrame = Color(0x38FFDCD2)

    /** The sunset, in its rule's order (EditorialSunsetRule) and as the accent pair. */
    val Maroon = Color(0xFF500723)
    val Crimson = Color(0xFFBC1E47)
    val Rose = Color(0xFFE6385F)
    val Coral = Color(0xFFEC6A79)
    val Peach = Color(0xFFF7AA9C)
    val Pink = Color(0xFFF96985)

    /**
     * The accent's deep shade: a primary button at rest, under ink text. Crimson would give ink only 3.2:1, so this
     * is a rose light enough for 5:1.
     */
    val AccentDeep = Color(0xFFE44A6B)
}

/**
 * The sunset rule, Editorial's signature: five flat bands from maroon to peach (EditorialSunsetRule's stops), drawn
 * as five rectangles, no gradient and no blur.
 */
fun DrawScope.drawSunsetRule(
    topLeft: Offset,
    size: Size,
    alpha: Float = 0.9f,
) {
    var from = 0f
    SunsetBands.forEach { (color, to) ->
        drawRect(
            color,
            topLeft = Offset(topLeft.x + size.width * from, topLeft.y),
            size = Size(size.width * (to - from), size.height),
            alpha = alpha,
        )
        from = to
    }
}

/** Each band's colour and where it ends, as a fraction of the rule's width. */
private val SunsetBands = listOf(
    EditorialPalette.Maroon to 0.14f,
    EditorialPalette.Crimson to 0.30f,
    EditorialPalette.Rose to 0.46f,
    EditorialPalette.Coral to 0.70f,
    EditorialPalette.Peach to 1f,
)
