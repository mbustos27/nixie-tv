// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// Drawing from coordinates and shares: those numbers are the art, not magic.
@file:Suppress("MagicNumber")

package io.github.mbustos27.nixie.tv.theme.drivein

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.mbustos27.nixie.tv.designsystem.Artwork
import io.github.mbustos27.nixie.tv.designsystem.BrowseSlots
import io.github.mbustos27.nixie.tv.designsystem.ItemSlots
import io.github.mbustos27.nixie.tv.designsystem.NavEntryLook
import io.github.mbustos27.nixie.tv.designsystem.NixieGlyphs
import io.github.mbustos27.nixie.tv.designsystem.NixieReadout
import io.github.mbustos27.nixie.tv.designsystem.PlayerSlots

private val Radius = RoundedCornerShape(8.dp)

// ---- the library ---------------------------------------------------------------------------------------------------

/** The library's heading on the marquee: the title on the board, the count on nixie tubes, the rest in peach. */
@Composable
private fun LibraryHeader(
    title: String,
    line: String,
    modifier: Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Box(Modifier.marqueeBoard().padding(start = 24.dp, end = 22.dp, top = 10.dp, bottom = 14.dp)) {
            Text(
                title.uppercase(),
                style = GlacierText.body(40, FontWeight.SemiBold).copy(letterSpacing = 0.12.em),
                color = DriveInPalette.NeonCore,
            )
        }
        val count = line.trim().takeWhile { it.isDigit() || it == ',' || it == '.' }.filter(Char::isDigit)
        if (count.isNotEmpty() && count.all(NixieGlyphs::canShow)) {
            NixieReadout(count, height = 46.dp, spokenAs = line)
            Text(
                line.trim().drop(line.trim().indexOfFirst { it.isLetter() }.coerceAtLeast(0)),
                style = GlacierText.body(20),
                color = DriveInPalette.Kicker,
            )
        } else if (line.isNotBlank()) {
            Text(line, style = GlacierText.body(20), color = DriveInPalette.Kicker)
        }
    }
}

/** Chips: pink with Night text when focused; the chosen one underlined in pink; the rest on a dark tile. */
internal fun driveInChip(focused: Boolean, active: Boolean): NavEntryLook = when {
    focused -> NavEntryLook(DriveInPalette.Pink, DriveInPalette.Night, DriveInPalette.Pink)
    active -> NavEntryLook(DriveInPalette.Tile, DriveInPalette.NeonCore, DriveInPalette.BoardRim, DriveInPalette.Pink)
    else -> NavEntryLook(DriveInPalette.Tile, DriveInPalette.TextSecondary, Color.Transparent)
}

/** The A–Z rail: focused pink, the letter in view underlined, letters without titles dimmed (they take no focus). */
internal fun driveInLetter(
    focused: Boolean,
    current: Boolean,
    available: Boolean,
): NavEntryLook = when {
    focused -> NavEntryLook(DriveInPalette.Pink, DriveInPalette.Night, Color.Transparent)
    current -> NavEntryLook(Color.Transparent, DriveInPalette.NeonCore, Color.Transparent, DriveInPalette.Pink)
    available -> NavEntryLook(Color.Transparent, DriveInPalette.TextSecondary, Color.Transparent)
    else -> NavEntryLook(Color.Transparent, DriveInPalette.TextSecondary.copy(alpha = 0.3f), Color.Transparent)
}

/** A poster: the art, opaque (glass is for chrome), with a little night at its foot. */
@Composable
private fun BoxScope.PosterArt(imageUrl: String?, artwork: Artwork) {
    Box(
        Modifier.matchParentSize().background(
            Brush.verticalGradient(listOf(DriveInPalette.Tile, DriveInPalette.BoardBottom)),
        ),
    )
    artwork(imageUrl, Modifier.fillMaxSize())
    Box(
        Modifier.matchParentSize().background(
            Brush.verticalGradient(0.7f to Color.Transparent, 1f to DriveInPalette.Night.copy(alpha = 0.45f)),
        ),
    )
}

internal val DriveInBrowse = BrowseSlots(
    libraryHeader = { title, line, modifier -> LibraryHeader(title, line, modifier) },
    chip = ::driveInChip,
    letterRail = ::driveInLetter,
    posterArt = { imageUrl, _, artwork -> PosterArt(imageUrl, artwork) },
)

// ---- the item pages ------------------------------------------------------------------------------------------------

/** The crumb: tracked peach capitals after a small neon dot. */
@Composable
private fun Crumb(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(DriveInPalette.Pink))
        Text(
            text,
            style = GlacierText.body(17, FontWeight.Bold).copy(letterSpacing = 0.18.em),
            color = DriveInPalette.Kicker,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Title(
    text: String,
    size: Int,
    maxWidth: Int,
) {
    Text(
        text,
        style = GlacierText.display(size).copy(lineHeight = (size * 1.03).sp),
        color = DriveInPalette.NeonCore,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = maxWidth.dp),
    )
}

/** The facts: the rating in nixie orange (a number you read), the age badge on the board's rim, the rest in light. */
@Composable
private fun Facts(
    rating: String?,
    ageRating: String?,
    facts: List<String>,
    tech: String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        rating?.let { Text("★ $it", style = GlacierText.body(20, FontWeight.Bold), color = DriveInPalette.NixieOrange) }
        ageRating?.let { Badge(it, DriveInPalette.BoardRim, DriveInPalette.NeonCore) }
        if (facts.isNotEmpty()) {
            Text(facts.joinToString("  ·  "), style = GlacierText.body(20), color = DriveInPalette.TextSecondary)
        }
        tech?.let { Badge(it, DriveInPalette.Kicker.copy(alpha = 0.6f), DriveInPalette.Kicker) }
    }
}

@Composable
private fun Badge(
    text: String,
    edge: Color,
    color: Color,
) {
    Box(Modifier.border(1.dp, edge, Radius).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = GlacierText.body(16, FontWeight.SemiBold), color = color)
    }
}

/** The Play area on a marquee board with bulbs along its foot. */
internal fun Modifier.playBoard(): Modifier = this
    .marqueeBoard()
    .padding(horizontal = 20.dp, vertical = 18.dp)

/** An episode's still: number on nixie tubes, runtime in peach, played dimmed and checked, progress in pink. */
@Composable
private fun BoxScope.EpisodeStill(
    imageUrl: String?,
    number: Int?,
    runtimeMinutes: Int?,
    progress: Float?,
    played: Boolean,
    artwork: Artwork,
) {
    PosterArt(imageUrl, artwork)
    Box(
        Modifier.matchParentSize().background(
            Brush.verticalGradient(0.45f to Color.Transparent, 1f to DriveInPalette.Night.copy(alpha = 0.8f)),
        ),
    )
    if (played) Box(Modifier.matchParentSize().background(DriveInPalette.Night.copy(alpha = 0.5f)))
    number?.let {
        NixieReadout(
            it.toString(),
            height = 34.dp,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            lit = !played,
        )
    }
    runtimeMinutes?.let {
        Text(
            "$it min",
            style = GlacierText.body(15, FontWeight.SemiBold),
            color = DriveInPalette.Kicker,
            modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
        )
    }
    if (played) {
        Canvas(Modifier.align(Alignment.BottomEnd).padding(14.dp).size(30.dp)) {
            drawRoundRect(DriveInPalette.Pink, cornerRadius = CornerRadius(6.dp.toPx()))
            val s = size.width
            val path = Path().apply {
                moveTo(s * 0.26f, s * 0.52f)
                lineTo(s * 0.43f, s * 0.68f)
                lineTo(s * 0.74f, s * 0.34f)
            }
            drawPath(path, DriveInPalette.Night, style = Stroke(2.6.dp.toPx()))
        }
    } else if (progress != null) {
        Bar(progress, Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 14.dp))
    }
}

/** A thin progress bar, square-ended (no pills): pink over the dark tile. */
@Composable
private fun Bar(
    progress: Float,
    modifier: Modifier,
    height: Dp = 5.dp,
) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRoundRect(DriveInPalette.Tile, cornerRadius = CornerRadius(1.dp.toPx()))
        drawRoundRect(
            DriveInPalette.Pink,
            size = Size(size.width * progress.coerceIn(0f, 1f), size.height),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
    }
}

internal val DriveInItem = ItemSlots(
    backdrop = { imageUrl, height, artwork -> DriveInHero(imageUrl, artwork, height, fadeFoot = true) },
    crumb = { Crumb(it) },
    title = { text, size, maxWidth -> Title(text, size, maxWidth) },
    facts = { rating, ageRating, facts, tech -> Facts(rating, ageRating, facts, tech) },
    playArea = { playBoard() },
    episodeStill = { imageUrl, number, runtime, progress, played, _, _, artwork ->
        EpisodeStill(imageUrl, number, runtime, progress, played, artwork)
    },
)

// ---- the player ----------------------------------------------------------------------------------------------------

/** The OSD's shade: night falling from the top and rising from the bottom, warm, never blurred. */
private fun DrawScope.osdShade(top: Boolean) {
    drawRect(
        if (top) {
            Brush.verticalGradient(
                0f to DriveInPalette.Night2.copy(alpha = 0.94f),
                0.55f to DriveInPalette.Night.copy(alpha = 0.55f),
                1f to Color.Transparent,
            )
        } else {
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.4f to DriveInPalette.Night.copy(alpha = 0.7f),
                1f to DriveInPalette.Night2.copy(alpha = 0.96f),
            )
        },
    )
}

/** The seek bar: a square-ended track, the part played in pink, segments hatched in peach, a lit knob. */
@Composable
private fun BoxScope.SeekBar(
    position: Float,
    buffered: Float,
    focused: Boolean,
    segments: List<ClosedFloatingPointRange<Float>>,
    chapters: List<Float>,
) {
    Canvas(Modifier.matchParentSize()) {
        val track = (if (focused) 12.dp else 8.dp).toPx()
        val top = (size.height - track) / 2
        val corner = CornerRadius(2.dp.toPx())
        drawRoundRect(DriveInPalette.Tile, Offset(0f, top), Size(size.width, track), corner)
        drawRoundRect(
            DriveInPalette.TextSecondary.copy(alpha = 0.28f),
            Offset(0f, top),
            Size(size.width * buffered, track),
            corner,
        )
        drawRoundRect(DriveInPalette.Pink, Offset(0f, top), Size(size.width * position, track), corner)
        for (segment in segments) {
            val x0 = size.width * segment.start
            val x1 = size.width * segment.endInclusive
            var x = x0
            while (x < x1) {
                drawLine(
                    DriveInPalette.Kicker.copy(alpha = 0.7f),
                    Offset(x, top + track),
                    Offset((x + track).coerceAtMost(x1), top),
                    1.5.dp.toPx(),
                )
                x += 6.dp.toPx()
            }
        }
        for (c in chapters) {
            drawRect(
                DriveInPalette.Night,
                Offset(size.width * c - 1.dp.toPx(), top - 2.dp.toPx()),
                Size(2.dp.toPx(), track + 4.dp.toPx()),
            )
        }
        val knob = (if (focused) 13.dp else 9.dp).toPx()
        val center = Offset(size.width * position, size.height / 2)
        if (focused) {
            for (i in 3 downTo 1) {
                drawCircle(DriveInPalette.Pink.copy(alpha = 0.16f * (4 - i)), knob + i * 4.dp.toPx(), center)
            }
        }
        drawCircle(DriveInPalette.Pink, knob, center)
        drawCircle(DriveInPalette.NeonCore, knob * 0.5f, center)
    }
}

/** The times: where you are on nixie tubes, the chapter in peach, what is left in light. */
@Composable
private fun Times(
    position: String,
    chapter: String,
    remaining: String?,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        NixieReadout(position, height = 40.dp)
        Text(
            chapter,
            style = GlacierText.body(20),
            color = DriveInPalette.Kicker,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 24.dp),
        )
        remaining?.let { Text(it, style = GlacierText.mono(22), color = DriveInPalette.TextSecondary) }
    }
}

/** The Skip button: a marquee board, pink when focused, the seconds left on small tubes. */
@Composable
private fun SkipFace(
    label: String,
    focused: Boolean,
    remainingSeconds: Long,
) {
    Row(
        Modifier
            .height(68.dp)
            .clip(Radius)
            .background(if (focused) DriveInPalette.Pink else DriveInPalette.BoardTop.copy(alpha = 0.92f))
            .border(2.dp, if (focused) DriveInPalette.NeonCore else DriveInPalette.BoardRim, Radius)
            .padding(start = 28.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            label,
            style = GlacierText.body(22, FontWeight.SemiBold),
            color = if (focused) DriveInPalette.Night else DriveInPalette.NeonCore,
        )
        NixieReadout(remainingSeconds.toString(), height = 44.dp)
    }
}

/** Up next's countdown line in tracked peach capitals. */
@Composable
private fun Countdown(text: String) {
    Text(
        text.uppercase(),
        style = GlacierText.body(16, FontWeight.Bold).copy(letterSpacing = 0.12.em),
        color = DriveInPalette.Kicker,
    )
}

/** The player's cards and panels on the marquee board's night, opaque enough to read over any picture. */
internal fun Modifier.panelBoard(): Modifier = this
    .clip(Radius)
    .background(
        Brush.verticalGradient(
            listOf(DriveInPalette.BoardTop.copy(alpha = 0.96f), DriveInPalette.BoardBottom.copy(alpha = 0.98f)),
        ),
    )
    .border(1.dp, DriveInPalette.BoardRim, Radius)

internal val DriveInPlayer = PlayerSlots(
    osdScrim = { top -> osdShade(top) },
    seekBar = { position, buffered, focused, segments, chapters ->
        SeekBar(
            position,
            buffered,
            focused,
            segments,
            chapters,
        )
    },
    times = { position, chapter, remaining -> Times(position, chapter, remaining) },
    skipButton = { label, focused, remainingSeconds, _ -> SkipFace(label, focused, remainingSeconds) },
    upNextCountdown = { text, _ -> Countdown(text) },
    panelChrome = { panelBoard() },
)

// ---- shared ----------------------------------------------------------------------------------------------------------

/** The marquee board: near-black with a maroon rim, a peach hairline and a string of bulbs along its foot. */
internal fun Modifier.marqueeBoard(): Modifier = this
    .clip(Radius)
    .background(Brush.verticalGradient(listOf(DriveInPalette.BoardTop, DriveInPalette.BoardBottom)))
    .border(1.dp, DriveInPalette.BoardRim, Radius)
    .drawBehind {
        val y = size.height - 4.dp.toPx()
        var x = 14.dp.toPx()
        while (x <= size.width - 14.dp.toPx()) {
            drawCircle(DriveInPalette.Peach.copy(alpha = 0.14f), 4.dp.toPx(), Offset(x, y))
            drawCircle(DriveInPalette.NeonCore.copy(alpha = 0.7f), 1.6.dp.toPx(), Offset(x, y))
            x += 22.dp.toPx()
        }
    }
