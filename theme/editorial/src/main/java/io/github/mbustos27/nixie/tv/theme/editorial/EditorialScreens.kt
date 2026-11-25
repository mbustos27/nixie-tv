// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.editorial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.mbustos27.nixie.tv.designsystem.Artwork
import io.github.mbustos27.nixie.tv.designsystem.BrowseSlots
import io.github.mbustos27.nixie.tv.designsystem.ItemSlots
import io.github.mbustos27.nixie.tv.designsystem.NavEntryLook
import io.github.mbustos27.nixie.tv.designsystem.PlayerSlots

/*
 * Phase 2: the library pages, the item pages and the player's chrome, in the magazine's language: serif titles (the
 * displayFont token and these slots), a standfirst under a heading, hairline rules, episodes numbered "01", facts
 * set like a colophon, and little chrome in the player.
 */

/**
 * Editorial's ink wash over an item page's picture: the floor every pixel gets, so the page's text (secondary text
 * included) reads at 4.5:1 or better wherever it stands, even over a white picture printed in [PrintTone].
 */
const val ITEM_WASH = 0.62f

/**
 * The tone an item page's picture is printed in (multiplied over it): a dusty rose, so the picture reads as a
 * one-ink print on the page and its highlights stop at the rose's lightness.
 */
val PrintTone = Color(0xFFD07A8E)

// --- Browse -------------------------------------------------------------------------------------------------------

/**
 * A library's heading: the title in the serif, the count or the folder path under it as the standfirst (italic, the
 * nearest folder kept), and a hairline under both, as wide as the wider of them.
 */
@Composable
fun EditorialLibraryHeader(
    title: String,
    line: String,
    modifier: Modifier,
) {
    Column(modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = EditorialType.heading(56),
            color = EditorialPalette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            line,
            style = EditorialType.standfirst(23),
            color = EditorialPalette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
        )
        Box(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .widthIn(min = 160.dp)
                .height(2.dp)
                .background(EditorialPalette.Rule),
        )
    }
}

/**
 * The filter and season chips, as the top bar's tabs: at rest a ruled box with secondary text, the chosen one
 * underlined in pink, the focused one ink on paper in a pink frame (its underline crimson, to show on paper).
 */
internal val EditorialChip: (Boolean, Boolean) -> NavEntryLook = { focused, active ->
    when {
        focused -> NavEntryLook(
            background = EditorialPalette.Paper,
            foreground = EditorialPalette.Ink,
            border = EditorialPalette.Pink,
            underline = if (active) EditorialPalette.Crimson else Color.Unspecified,
        )

        active -> NavEntryLook(Color.Transparent, EditorialPalette.Text, EditorialPalette.Rule, EditorialPalette.Pink)

        else -> NavEntryLook(Color.Transparent, EditorialPalette.TextSecondary, EditorialPalette.Rule)
    }
}

/**
 * The A–Z rail: letters with titles in secondary text, the letter in view in the figures' colour on a panel tab,
 * the focused one ink on paper. Letters without titles are faint (they cannot be chosen). No underline: the rail's
 * letters are too short for one under the glyph.
 */
internal val EditorialLetterRail: (Boolean, Boolean, Boolean) -> NavEntryLook = { focused, current, available ->
    when {
        focused -> NavEntryLook(EditorialPalette.Paper, EditorialPalette.Ink, Color.Transparent)
        current -> NavEntryLook(EditorialPalette.Panel, EditorialPalette.Numeral, Color.Transparent)
        available -> NavEntryLook(Color.Transparent, EditorialPalette.TextSecondary, Color.Transparent)
        else -> NavEntryLook(Color.Transparent, EditorialPalette.TextSecondary.copy(alpha = 0.32f), Color.Transparent)
    }
}

/** A poster as a plate: the art on the panel inside a hairline frame, as Home's picture. */
private val EditorialPosterArt: @Composable BoxScope.(String?, Boolean, Artwork) -> Unit = { imageUrl, _, artwork ->
    Box(Modifier.matchParentSize().background(EditorialPalette.Panel))
    artwork(imageUrl, Modifier.fillMaxSize())
    Box(Modifier.matchParentSize().border(1.dp, EditorialPalette.PlateFrame, EditorialShape))
}

val EditorialBrowse = BrowseSlots(
    libraryHeader = { title, line, modifier -> EditorialLibraryHeader(title, line, modifier) },
    chip = EditorialChip,
    letterRail = EditorialLetterRail,
    posterArt = EditorialPosterArt,
)

// --- Item pages ---------------------------------------------------------------------------------------------------

/**
 * An item page's picture printed in one ink: the art multiplied by [PrintTone], then washed with ink ([ITEM_WASH]
 * everywhere, deeper on the start side where the title stands) and fading into the page at its foot. The text stands
 * on it anywhere the page puts it, at 4.5:1 or better whatever the picture.
 */
private val EditorialItemBackdrop: @Composable BoxScope.(String?, Int, Artwork) -> Unit = { imageUrl, _, artwork ->
    Box(Modifier.matchParentSize().background(EditorialPalette.Ink))
    artwork(imageUrl, Modifier.fillMaxSize())
    Canvas(Modifier.matchParentSize()) { drawItemWash() }
}

/** The print and the wash over the picture; see [EditorialItemBackdrop]. */
fun DrawScope.drawItemWash() {
    drawRect(PrintTone, blendMode = BlendMode.Multiply)
    drawRect(EditorialPalette.Ink.copy(alpha = ITEM_WASH))
    val deep = EditorialPalette.Ink.copy(alpha = 0.55f)
    val clear = EditorialPalette.Ink.copy(alpha = 0f)
    val start = if (layoutDirection == LayoutDirection.Rtl) listOf(clear, deep) else listOf(deep, clear)
    drawRect(Brush.horizontalGradient(start))
    drawRect(Brush.verticalGradient(ITEM_FADE_FROM to clear, 1f to EditorialPalette.Ink))
}

/** Where an item page's picture starts fading into the page, as a share of its height. */
private const val ITEM_FADE_FROM = 0.55f

/** The line above an item's title: a short pink tab, then the section in the kickers' small capitals. */
@Composable
fun EditorialCrumb(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(36.dp, 3.dp).background(EditorialPalette.Pink))
        Text(
            text,
            style = EditorialType.kicker(17),
            color = EditorialPalette.Kicker,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** An item's title in the serif, at most two lines, on the ink (no shadow: the wash gives the contrast). */
@Composable
fun EditorialTitle(
    text: String,
    size: Int,
    maxWidth: Int,
) {
    Text(
        text,
        style = EditorialType.heading(size).copy(lineHeight = (size * 1.04f).sp),
        color = EditorialPalette.Text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = maxWidth.dp).semantics { heading() },
    )
}

/**
 * The facts set like a colophon: one line of small capitals between hairline column rules, the rating first in the
 * figures' colour with its star, the age rating in the text colour, the rest secondary.
 */
@Composable
fun EditorialFacts(
    rating: String?,
    ageRating: String?,
    facts: List<String>,
    tech: String?,
) {
    val parts = buildList {
        rating?.let { add(it to EditorialPalette.Numeral) }
        ageRating?.let { add(it to EditorialPalette.Text) }
        facts.forEach { add(it to EditorialPalette.TextSecondary) }
        tech?.let { add(it to EditorialPalette.TextSecondary) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        parts.forEachIndexed { i, (text, color) ->
            if (i > 0) Box(Modifier.size(2.dp, 18.dp).background(EditorialPalette.Rule))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (i == 0 && rating != null) {
                    Icon(GlacierIcons.Star, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                }
                Text(text.uppercase(), style = EditorialType.colophon(17), color = color, maxLines = 1)
            }
        }
    }
}

/** The Play area under a hairline, as a byline under its rule. */
private val EditorialPlayArea: Modifier.() -> Modifier = {
    drawBehind { drawRect(EditorialPalette.Rule, Offset.Zero, Size(size.width, 2.dp.toPx())) }
        .padding(top = 22.dp)
}

/**
 * An episode's still: the art, ink rising at its foot, the episode's number in the serif's figures ("03") at the
 * start and its length ("42′") at the end, the progress along the foot, and a paper check when it was watched. The
 * number and the length are not read aloud: the card's title and line under it say them.
 */
@Composable
fun BoxScope.EditorialEpisodeStill(
    imageUrl: String?,
    number: Int?,
    runtimeMinutes: Int?,
    progress: Float?,
    played: Boolean,
    artwork: Artwork,
) {
    Box(Modifier.matchParentSize().background(EditorialPalette.Panel))
    artwork(imageUrl, Modifier.fillMaxSize())
    Box(
        Modifier.matchParentSize().background(
            Brush.verticalGradient(
                0.35f to EditorialPalette.Ink.copy(alpha = 0f),
                0.62f to EditorialPalette.Ink.copy(alpha = 0.8f),
                1f to EditorialPalette.Ink.copy(alpha = 0.95f),
            ),
        ),
    )
    number?.let {
        Text(
            sectionNumber(it),
            style = EditorialType.figures(44),
            color = EditorialPalette.Numeral,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 10.dp)
                .clearAndSetSemantics { },
        )
    }
    runtimeMinutes?.let {
        Text(
            "$it′",
            style = EditorialType.figures(22),
            color = EditorialPalette.TextSecondary,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 18.dp)
                .clearAndSetSemantics { },
        )
    }
    if (played) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(EditorialPalette.Paper),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                GlacierIcons.Check,
                contentDescription = null,
                tint = EditorialPalette.Ink,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    progress?.let {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(EditorialPalette.Rule))
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(it.coerceIn(0f, 1f))
                .height(5.dp)
                .background(EditorialPalette.Pink),
        )
    }
}

val EditorialItem = ItemSlots(
    backdrop = EditorialItemBackdrop,
    crumb = { text -> EditorialCrumb(text) },
    title = { text, size, maxWidth -> EditorialTitle(text, size, maxWidth) },
    facts = { rating, ageRating, facts, tech -> EditorialFacts(rating, ageRating, facts, tech) },
    playArea = EditorialPlayArea,
    episodeStill = { imageUrl, number, runtime, progress, played, _, _, artwork ->
        EditorialEpisodeStill(imageUrl, number, runtime, progress, played, artwork)
    },
)

// --- Player -------------------------------------------------------------------------------------------------------

/**
 * The OSD's shade in ink: deep where its text stands (the top's title and clock, the foot's times and buttons), clear
 * towards the picture's middle; the top one carries the sunset rule along the screen's edge, the player's only colour.
 */
fun DrawScope.drawOsdScrim(top: Boolean) {
    val stops = (if (top) OsdTopShade else OsdFootShade)
        .map { (at, alpha) -> at to EditorialPalette.Ink.copy(alpha = alpha) }
        .toTypedArray()
    drawRect(Brush.verticalGradient(*stops))
    if (top) drawSunsetRule(Offset.Zero, Size(size.width, 3.dp.toPx()))
}

/** The top shade's stops (where, ink's alpha): deep over the title and the clock. */
private val OsdTopShade = listOf(0f to 0.96f, 0.4f to 0.9f, 0.7f to 0.5f, 1f to 0f)

/** The foot's stops: deep from 55% down, over the times and the buttons. */
private val OsdFootShade = listOf(0f to 0f, 0.3f to 0.7f, 0.55f to 0.9f, 1f to 0.97f)

/**
 * The seek bar as a ruler: a hairline track (thicker with focus), the buffered part a shade lighter, intro and
 * credits in the kickers' peach, the part played in pink, chapters as ticks across it, and the playhead a paper
 * column rule, taller with focus.
 */
fun DrawScope.drawSeekBar(
    position: Float,
    buffered: Float,
    focused: Boolean,
    segments: List<ClosedFloatingPointRange<Float>>,
    chapters: List<Float>,
) {
    val w = size.width
    val cy = size.height / 2
    val rtl = layoutDirection == LayoutDirection.Rtl
    fun x(f: Float) = (if (rtl) 1f - f.coerceIn(0f, 1f) else f.coerceIn(0f, 1f)) * w
    fun span(
        from: Float,
        to: Float,
        color: Color,
        h: Float,
    ) {
        val a = x(from)
        val b = x(to)
        drawRect(color, Offset(minOf(a, b), cy - h / 2), Size(kotlin.math.abs(b - a), h))
    }
    val track = (if (focused) 6.dp else 3.dp).toPx()
    span(0f, 1f, EditorialPalette.Text.copy(alpha = 0.22f), track)
    span(0f, buffered, EditorialPalette.Text.copy(alpha = 0.4f), track)
    segments.forEach { span(it.start, it.endInclusive, EditorialPalette.Kicker.copy(alpha = 0.6f), track) }
    span(0f, position, EditorialPalette.Pink, track)
    val tick = (if (focused) 20.dp else 14.dp).toPx()
    val tickColor = EditorialPalette.Paper.copy(alpha = 0.7f)
    chapters.forEach { c -> drawRect(tickColor, Offset(x(c) - 1.dp.toPx(), cy - tick / 2), Size(2.dp.toPx(), tick)) }
    val head = Size((if (focused) 4.dp else 3.dp).toPx(), (if (focused) 30.dp else 18.dp).toPx())
    drawRect(EditorialPalette.Paper, Offset(x(position) - head.width / 2, cy - head.height / 2), head)
}

/** The times under the bar in the serif's figures, the chapter between them as an italic standfirst. */
@Composable
fun EditorialTimes(
    position: String,
    chapter: String,
    remaining: String?,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(position, style = EditorialType.figures(24), color = EditorialPalette.Text, maxLines = 1)
        Text(
            chapter,
            style = EditorialType.standfirst(21).copy(textAlign = TextAlign.Center),
            color = EditorialPalette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 24.dp),
        )
        remaining?.let {
            Text(it, style = EditorialType.figures(24), color = EditorialPalette.TextSecondary, maxLines = 1)
        }
    }
}

/**
 * Skip as a plate on the picture: ink with a paper edge at rest, paper with ink text when focused; the seconds left
 * in figures after a column rule, and the segment's progress as a pink rule along its foot.
 */
@Composable
fun EditorialSkipButton(
    label: String,
    focused: Boolean,
    remainingSeconds: Long,
    played: () -> Float,
) {
    val face = if (focused) EditorialPalette.Ink else EditorialPalette.Text
    Row(
        Modifier
            .height(68.dp)
            .clip(EditorialShape)
            .background(if (focused) EditorialPalette.Paper else EditorialPalette.Ink.copy(alpha = 0.9f))
            .border(2.dp, EditorialPalette.Paper.copy(alpha = if (focused) 1f else 0.4f), EditorialShape)
            .drawBehind {
                val h = 4.dp.toPx()
                val share = played().coerceIn(0f, 1f)
                val left = if (layoutDirection == LayoutDirection.Rtl) size.width * (1 - share) else 0f
                drawRect(EditorialPalette.Pink, Offset(left, size.height - h), Size(size.width * share, h))
            }
            .padding(start = 28.dp, end = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(GlacierIcons.SkipForward, contentDescription = null, tint = face, modifier = Modifier.size(22.dp))
        Text(label, style = EditorialType.colophon(20), color = face, maxLines = 1)
        Box(Modifier.size(2.dp, 28.dp).background(face.copy(alpha = 0.3f)))
        Text(
            remainingSeconds.toString(),
            style = EditorialType.figures(26),
            color = if (focused) EditorialPalette.Ink else EditorialPalette.Numeral,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** The player's cards and panels: the solid panel, radius 8, under the sunset rule. */
private val EditorialPanelChrome: Modifier.() -> Modifier = {
    clip(EditorialShape)
        .background(EditorialPalette.Panel)
        .drawBehind { drawSunsetRule(Offset.Zero, Size(size.width, 4.dp.toPx()), alpha = 1f) }
}

val EditorialPlayer = PlayerSlots(
    osdScrim = { top -> drawOsdScrim(top) },
    seekBar = { position, buffered, focused, segments, chapters ->
        Canvas(Modifier.matchParentSize()) { drawSeekBar(position, buffered, focused, segments, chapters) }
    },
    times = { position, chapter, remaining -> EditorialTimes(position, chapter, remaining) },
    skipButton = { label, focused, remainingSeconds, played ->
        EditorialSkipButton(label, focused, remainingSeconds, played)
    },
    upNextCountdown = { text, _ ->
        Text(text.uppercase(), style = EditorialType.kicker(16), color = EditorialPalette.Kicker, maxLines = 1)
    },
    panelChrome = EditorialPanelChrome,
)
