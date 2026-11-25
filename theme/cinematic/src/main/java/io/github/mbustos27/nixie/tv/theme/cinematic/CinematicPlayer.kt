// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.cinematic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.mbustos27.nixie.tv.designsystem.PlayerSlots
import java.util.Locale

/**
 * Cinematic in the player: deep shades at the top and the foot of the picture, the seek bar as a strip of film (a
 * dark band the progress runs through in pink, chapters as frame lines, intro and credits tinted peach, perforations
 * along both edges while it has focus, a white playhead with a pink glow), the times with the chapter in peach
 * capitals, Skip on a dark plate with its time draining along the foot, and the cards and panels on a dense fill
 * (no blur over video).
 */
val CinematicPlayer = PlayerSlots(
    osdScrim = { top -> drawOsdScrim(top) },
    seekBar = { position, buffered, focused, segments, chapters ->
        FilmSeekBar(position, buffered, focused, segments, chapters)
    },
    times = { position, chapter, remaining -> Times(position, chapter, remaining) },
    skipButton = { label, focused, remainingSeconds, played -> SkipFace(label, focused, remainingSeconds, played) },
    upNextCountdown = { text, _ -> Kicker(text) },
    panelChrome = {
        clip(CinematicShape)
            .background(CinematicPalette.PanelFill)
            .border(1.dp, Brush.verticalGradient(*PanelRimStops), CinematicShape)
    },
)

/** The OSD's shades; the title, the clock, the times and the buttons keep 4.5:1 over a white picture. */
internal fun DrawScope.drawOsdScrim(top: Boolean) {
    drawRect(Brush.verticalGradient(*(if (top) OsdTopStops else OsdFootStops)))
}

/** The seek bar as a strip of film, drawn in one canvas inside Glacier's focusable 38 dp bar. */
@Composable
private fun BoxScope.FilmSeekBar(
    position: Float,
    buffered: Float,
    focused: Boolean,
    segments: List<ClosedFloatingPointRange<Float>>,
    chapters: List<Float>,
) {
    Canvas(Modifier.fillMaxSize()) {
        val band = (if (focused) BAND_FOCUSED else BAND).dp.toPx()
        val top = (size.height - band) / 2
        val w = size.width
        val corner = CornerRadius(2.dp.toPx())
        drawRoundRect(CinematicPalette.FilmBand, Offset(0f, top), Size(w, band), corner)
        drawRect(CinematicPalette.ProgressTrack, Offset(0f, top), Size(w * buffered.coerceIn(0f, 1f), band))
        drawRect(CinematicPalette.Pink, Offset(0f, top), Size(w * position.coerceIn(0f, 1f), band))
        segments.forEach { segment ->
            val from = w * segment.start.coerceIn(0f, 1f)
            drawRect(
                CinematicPalette.SegmentTint,
                Offset(from, top),
                Size(w * segment.endInclusive.coerceIn(0f, 1f) - from, band),
            )
        }
        // Chapters: frame lines through the band.
        val line = 2.dp.toPx()
        chapters.forEach { drawRect(CinematicPalette.FilmBase, Offset(w * it - line / 2, top), Size(line, band)) }
        // The strip's lit edges, so the dark band reads over a dark shade too.
        drawRoundRect(
            CinematicPalette.FilmEdgeLight,
            Offset(0f, top),
            Size(w, band),
            corner,
            style = Stroke(1.dp.toPx()),
        )
        if (focused) drawPerforations(top, band)
        drawPlayhead(w * position.coerceIn(0f, 1f), band, focused)
    }
}

/** Film perforations above and below the band, only while the bar has focus. */
private fun DrawScope.drawPerforations(top: Float, band: Float) {
    val hole = Size(4.dp.toPx(), 3.dp.toPx())
    val pitch = PERFORATION_PITCH.dp.toPx()
    val gap = 3.dp.toPx()
    var x = pitch / 2
    while (x < size.width) {
        drawRect(CinematicPalette.Perforation, Offset(x, top - gap - hole.height), hole)
        drawRect(CinematicPalette.Perforation, Offset(x, top + band + gap), hole)
        x += pitch
    }
}

/** The playhead: a white bar taller than the band; focused, taller still in a pink glow of stacked strokes. */
private fun DrawScope.drawPlayhead(
    x: Float,
    band: Float,
    focused: Boolean,
) {
    val width = 4.dp.toPx()
    val height = band + (if (focused) HEAD_REACH_FOCUSED else HEAD_REACH).dp.toPx()
    val top = (size.height - height) / 2
    val corner = CornerRadius(2.dp.toPx())
    if (focused) {
        for (i in GLOW_STROKES downTo 1) {
            val reach = (GLOW_STEP * i).dp.toPx()
            drawRoundRect(
                CinematicPalette.Pink.copy(alpha = GLOW_ALPHA / i),
                Offset(x - width / 2 - reach, top - reach),
                Size(width + reach * 2, height + reach * 2),
                CornerRadius(corner.x + reach),
            )
        }
    }
    drawRoundRect(CinematicPalette.Rim, Offset(x - width / 2, top), Size(width, height), corner)
}

/** The times: where you are in white, the chapter in peach capitals in the middle, the time left in grey. */
@Composable
private fun Times(
    position: String,
    chapter: String,
    remaining: String?,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(position, style = GlacierText.mono(20), color = CinematicPalette.Text)
        Text(
            chapter.uppercase(),
            style = GlacierText.body(17, FontWeight.SemiBold).copy(letterSpacing = 0.12.em),
            color = CinematicPalette.Peach,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 24.dp),
        )
        if (remaining != null) Text(remaining, style = GlacierText.mono(20), color = CinematicPalette.TextSecondary)
    }
}

/**
 * Skip's face: a dark plate with a white edge (a pink plate with a white rim and Night text when focused), the label,
 * the seconds left on a small plate of their own, and the segment's time draining along the foot.
 */
@Composable
private fun SkipFace(
    label: String,
    focused: Boolean,
    remainingSeconds: Long,
    played: () -> Float,
) {
    val ink = if (focused) CinematicPalette.Night else CinematicPalette.Text
    Row(
        Modifier
            .height(SKIP_HEIGHT)
            .clip(CinematicShape)
            .background(if (focused) CinematicPalette.Pink else CinematicPalette.SkipPlate)
            .border(2.dp, if (focused) CinematicPalette.Rim else CinematicPalette.BadgeStroke, CinematicShape)
            .drawBehind {
                val left = 1f - played().coerceIn(0f, 1f)
                val h = 3.dp.toPx()
                drawRect(
                    if (focused) CinematicPalette.Night else CinematicPalette.Pink,
                    Offset(0f, size.height - h),
                    Size(size.width * left, h),
                )
            }
            .padding(start = 28.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(GlacierIcons.SkipForward, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
        Text(label, style = GlacierText.body(22, FontWeight.SemiBold), color = ink)
        Box(
            Modifier
                .size(48.dp)
                .clip(CinematicShape)
                .background(ink.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(secondsText(remainingSeconds), style = GlacierText.body(18, FontWeight.Bold), color = ink)
        }
    }
}

/** 42 as "42", 75 as "1:15". */
internal fun secondsText(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    if (s < SECONDS_PER_MINUTE) return s.toString()
    return String.format(Locale.ROOT, "%d:%02d", s / SECONDS_PER_MINUTE, s % SECONDS_PER_MINUTE)
}

private const val BAND = 10
private const val BAND_FOCUSED = 16
private const val HEAD_REACH = 12
private const val HEAD_REACH_FOCUSED = 18
private const val PERFORATION_PITCH = 14
private const val GLOW_STROKES = 3
private const val GLOW_STEP = 3
private const val GLOW_ALPHA = 0.45f
private const val SECONDS_PER_MINUTE = 60
private val SKIP_HEIGHT: Dp = 68.dp

/** Top: under the title and the clock (60 to about 200 of its 280). */
internal val OsdTopStops = arrayOf(
    0f to CinematicPalette.Ground.copy(alpha = 0.92f),
    0.6f to CinematicPalette.Ground.copy(alpha = 0.82f),
    0.8f to CinematicPalette.Ground.copy(alpha = 0.66f),
    1f to Color.Transparent,
)

/** Foot: under the seek bar, the times and the buttons (from about 300 of its 560). */
internal val OsdFootStops = arrayOf(
    0f to Color.Transparent,
    0.3f to CinematicPalette.Ground.copy(alpha = 0.55f),
    0.5f to CinematicPalette.Ground.copy(alpha = 0.84f),
    1f to CinematicPalette.Ground.copy(alpha = 0.96f),
)

private val PanelRimStops = arrayOf(
    0f to CinematicPalette.IslandRimTop,
    0.3f to CinematicPalette.IslandRimMid,
    1f to CinematicPalette.IslandRimFoot,
)
