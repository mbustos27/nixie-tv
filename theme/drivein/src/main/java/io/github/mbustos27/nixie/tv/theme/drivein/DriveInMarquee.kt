// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.drivein

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.mbustos27.nixie.tv.designsystem.NavEntryLook
import io.github.mbustos27.nixie.tv.designsystem.NixieReadout

private val Radius = RoundedCornerShape(8.dp)

/**
 * A row of marquee bulbs along the bottom edge: a lit core in a faint halo, two strokes of falling alpha, no blur.
 * [spacing] is the distance between bulbs.
 */
private fun DrawScope.bulbs(
    spacing: Float,
    inset: Float,
    y: Float,
) {
    var x = inset
    while (x <= size.width - inset) {
        drawCircle(DriveInPalette.Peach.copy(alpha = 0.16f), radius = 4.5.dp.toPx(), center = Offset(x, y))
        drawCircle(DriveInPalette.NeonCore.copy(alpha = 0.8f), radius = 1.8.dp.toPx(), center = Offset(x, y))
        x += spacing
    }
}

/**
 * The top bar as Drive-in's sky bar: Night glass (the plan's fake glass, a 85% tint, nothing captured or blurred), a
 * lit hairline, and a string of marquee bulbs along its foot. Radius 8, never a pill.
 */
internal fun Modifier.driveInBar(): Modifier = this
    .clip(Radius)
    .background(Brush.verticalGradient(listOf(DriveInPalette.SkyBarTop, DriveInPalette.SkyBarBottom)))
    .border(1.dp, DriveInPalette.SkyBarStroke, Radius)
    .drawBehind { bulbs(spacing = 22.dp.toPx(), inset = 14.dp.toPx(), y = size.height - 4.dp.toPx()) }

/**
 * A top-bar entry: pink with Night text when focused (pink is "you can act on this"), the page you are on underlined
 * in pink, the rest in the secondary text colour on the glass.
 */
internal fun driveInEntry(focused: Boolean, active: Boolean): NavEntryLook = when {
    focused -> NavEntryLook(DriveInPalette.Pink, DriveInPalette.Night, DriveInPalette.Pink)
    active -> NavEntryLook(Color.Transparent, DriveInPalette.NeonCore, Color.Transparent, DriveInPalette.Pink)
    else -> NavEntryLook(Color.Transparent, DriveInPalette.TextSecondary, Color.Transparent)
}

/**
 * A row's title as a marquee board: neon letters spaced like a letter board, on a dark board with a maroon rim. A
 * count reads on nixie tubes (orange is "a number you read"); any other subtitle in peach beside it.
 */
@Composable
internal fun DriveInRowHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier
                .clip(Radius)
                .background(Brush.verticalGradient(listOf(DriveInPalette.BoardTop, DriveInPalette.BoardBottom)))
                .border(1.dp, DriveInPalette.BoardRim, Radius)
                .drawBehind {
                    val y = size.height - 3.dp.toPx()
                    drawLine(
                        DriveInPalette.BoardHairline,
                        Offset(10.dp.toPx(), y),
                        Offset(size.width - 10.dp.toPx(), y),
                        1.dp.toPx(),
                    )
                }
                .padding(start = 18.dp, end = 16.dp, top = 6.dp, bottom = 9.dp),
        ) {
            Text(
                title.uppercase(),
                style = GlacierText.body(24, FontWeight.SemiBold).copy(letterSpacing = 0.14.em),
                color = DriveInPalette.NeonCore,
            )
        }
        if (subtitle != null) {
            if (subtitle.isNotEmpty() && subtitle.all(Char::isDigit)) {
                NixieReadout(subtitle, height = 34.dp)
            } else {
                Text(subtitle, style = GlacierText.body(18), color = DriveInPalette.Kicker)
            }
        }
    }
}

/** Quick Connect's code on six nixie tubes; dashes at rest until the server sends a code. */
@Composable
internal fun DriveInQuickConnectCode(code: String?, length: Int) {
    val shown = code?.takeIf { it.isNotBlank() } ?: "-".repeat(length)
    NixieReadout(
        text = shown,
        height = 132.dp,
        lit = code != null,
        spokenAs = code?.toList()?.joinToString(" ") ?: "",
    )
}
