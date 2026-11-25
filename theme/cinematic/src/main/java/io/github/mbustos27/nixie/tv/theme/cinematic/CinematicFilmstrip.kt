// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.cinematic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText

private val FrameWidth = 100.dp
private val FrameHeight = 128.dp
private val FrameGap = 12.dp
private val SprocketBand = 30.dp

/**
 * Quick Connect's code on a filmstrip: one frame per character, lit from the top like a projected frame, on a dark
 * film base with its sprocket holes along both edges. Until the server sends a code each frame shows a dim dash.
 * TalkBack reads the characters one by one; the strip itself is decoration.
 */
@Composable
internal fun FilmstripCode(code: String?, length: Int) {
    val shown = (code ?: "").padEnd(length).take(length)
    val spoken = code?.toList()?.joinToString(" ")
    Row(
        Modifier
            .clearAndSetSemantics { if (spoken != null) contentDescription = spoken }
            .clip(CinematicShape)
            .background(CinematicPalette.FilmBase)
            .border(1.dp, CinematicPalette.FilmEdge, CinematicShape)
            .drawBehind { drawSprockets(SprocketBand.toPx()) }
            .padding(horizontal = 18.dp, vertical = SprocketBand),
        horizontalArrangement = Arrangement.spacedBy(FrameGap),
    ) {
        shown.forEach { char -> FilmFrame(char.takeUnless { code == null || it == ' ' }) }
    }
}

@Composable
private fun FilmFrame(char: Char?) {
    Box(
        Modifier
            .size(FrameWidth, FrameHeight)
            .clip(CinematicShape)
            .background(Brush.verticalGradient(listOf(CinematicPalette.FrameTop, CinematicPalette.FrameFoot)))
            .border(1.dp, CinematicPalette.FilmEdge, CinematicShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            char?.toString() ?: "–",
            style = GlacierText.display(74).copy(fontWeight = FontWeight.SemiBold),
            color = if (char == null) CinematicPalette.TextSecondary.copy(alpha = 0.55f) else CinematicPalette.Text,
        )
    }
}

/** The film's perforations: small rounded holes, evenly spaced and centred along each edge band. */
private fun DrawScope.drawSprockets(band: Float) {
    val hole = Size(14.dp.toPx(), 9.dp.toPx())
    val pitch = 26.dp.toPx()
    val count = ((size.width - pitch) / pitch).toInt().coerceAtLeast(1)
    val start = (size.width - (count - 1) * pitch - hole.width) / 2
    val corner = CornerRadius(2.dp.toPx())
    for (i in 0 until count) {
        val x = start + i * pitch
        drawRoundRect(CinematicPalette.Sprocket, Offset(x, (band - hole.height) / 2), hole, corner)
        drawRoundRect(
            CinematicPalette.Sprocket,
            Offset(x, size.height - band + (band - hole.height) / 2),
            hole,
            corner,
        )
    }
}
