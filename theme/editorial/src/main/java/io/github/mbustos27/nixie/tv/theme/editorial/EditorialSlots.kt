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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Text
import io.github.mbustos27.nixie.tv.designsystem.NavEntryLook
import io.github.mbustos27.nixie.tv.designsystem.NixieSlots

/** Editorial's one radius, for the plate, the bar and every rounded shape. */
internal val EditorialShape = RoundedCornerShape(8.dp)

/**
 * Home's hero as a magazine spread: the title, facts and buttons (Glacier's, at the bottom left) stand on plain ink,
 * and the picture is a framed plate on the other side with the sunset rule along its top. The plate ends before the
 * position marks, so they sit under it on ink. Sizes are design pixels on Glacier's 1920 x 1080 grid.
 */
object EditorialHero {
    /** Below the top bar (34 from the top, 68 high). */
    const val PLATE_TOP = 120

    /** The page edge, as the position marks below it. */
    const val PLATE_END = 80

    /** 16:9, and clear of the info column (80 + 900 wide) by 92. */
    const val PLATE_WIDTH = 768
    const val PLATE_HEIGHT = 432

    /** The sunset rule across the plate's top edge. */
    const val RULE_HEIGHT = 6
}

/** The ground: ink darkening towards the bottom, under the sunset rule along the top edge. */
private val EditorialPageGround: DrawScope.() -> Unit = {
    drawRect(Brush.verticalGradient(0f to EditorialPalette.Ink, 1f to EditorialPalette.InkDeep))
    drawSunsetRule(Offset.Zero, Size(size.width, 6.dp.toPx()))
}

/** The top bar: a dense strip of panel under the sunset rule, square to the page, radius 8. */
private val EditorialTopBarChrome: Modifier.() -> Modifier = {
    clip(EditorialShape)
        .background(EditorialPalette.Bar)
        .drawBehind {
            val rule = 3.dp.toPx()
            drawSunsetRule(Offset(0f, size.height - rule), Size(size.width, rule), alpha = 1f)
        }
}

/**
 * A tab: the focused one is set in ink on paper, framed in pink; the page you are on is underlined in pink (in
 * crimson while it also has focus, so the mark shows on paper). Focus changes the fill, not only a colour.
 */
private val EditorialTopBarEntry: (Boolean, Boolean) -> NavEntryLook = { focused, active ->
    when {
        focused -> NavEntryLook(
            background = EditorialPalette.Paper,
            foreground = EditorialPalette.Ink,
            border = EditorialPalette.Pink,
            underline = if (active) EditorialPalette.Crimson else Color.Unspecified,
        )

        active -> NavEntryLook(
            background = Color.Transparent,
            foreground = EditorialPalette.Text,
            border = Color.Transparent,
            underline = EditorialPalette.Pink,
        )

        else -> NavEntryLook(Color.Transparent, EditorialPalette.TextSecondary, Color.Transparent)
    }
}

/**
 * A row's title as a section head: the title in the serif, the subtitle in small capitals beside it, and a hairline
 * running from them to the page edge on their baseline. Where the page gives the row's position, the head opens
 * with its section number ("01") in the figures' colour, as a magazine's contents; the number is not read aloud.
 */
@Composable
fun EditorialRowHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier,
    number: Int? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (number != null) {
            Text(
                sectionNumber(number),
                style = EditorialType.figures(34),
                color = EditorialPalette.Numeral,
                maxLines = 1,
                modifier = Modifier.alignByBaseline().clearAndSetSemantics { },
            )
        }
        Text(
            title,
            style = EditorialType.heading(34),
            color = EditorialPalette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alignByBaseline().semantics { heading() },
        )
        if (subtitle != null) {
            Text(
                subtitle.uppercase(),
                style = EditorialType.kicker(16),
                color = EditorialPalette.Kicker,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
        // The rule's foot on the text's baseline.
        Box(
            Modifier
                .weight(1f)
                .height(2.dp)
                .alignBy { it.measuredHeight }
                .background(EditorialPalette.Rule),
        )
    }
}

/** A section's number as a magazine prints it: two figures at least ("01", "12", "104"). */
fun sectionNumber(number: Int): String = number.toString().padStart(2, '0')

/** The small capitals above the hero's title while it resumes, in place of Glacier's chip. */
@Composable
fun EditorialKicker(text: String) {
    Text(
        text.uppercase(),
        style = EditorialType.kicker(18),
        color = EditorialPalette.Kicker,
        maxLines = 1,
    )
}

/** Home's hero: ink where the text stands, the picture in a framed plate (see [EditorialHero]). */
private val EditorialHeroBackdrop: @Composable BoxScope.(
    String?,
    @Composable (String?, Modifier) -> Unit,
) -> Unit = { imageUrl, artwork ->
    Box(Modifier.matchParentSize().background(EditorialPalette.Ink))
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = EditorialHero.PLATE_TOP.dp, end = EditorialHero.PLATE_END.dp)
            .size(EditorialHero.PLATE_WIDTH.dp, EditorialHero.PLATE_HEIGHT.dp)
            .clip(EditorialShape)
            .background(EditorialPalette.Panel),
    ) {
        // The plate clips Glacier's slow zoom, so the picture moves inside a still frame.
        artwork(imageUrl, Modifier.fillMaxSize())
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawSunsetRule(Offset.Zero, Size(size.width, EditorialHero.RULE_HEIGHT.dp.toPx()), alpha = 1f)
                }
                .border(2.dp, EditorialPalette.PlateFrame, EditorialShape),
        )
    }
}

/**
 * Quick Connect's code set as figures in the serif, each on its own rule like a form's blanks, in two groups of three
 * for reading across a room. Dashes on bare rules until the server sends a code. Read aloud digit by digit.
 */
@Composable
fun EditorialCode(code: String?, length: Int) {
    val shown = code?.take(length)?.padEnd(length)
    Row(
        Modifier.clearAndSetSemantics { if (code != null) contentDescription = code.toList().joinToString(" ") },
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(length) { i ->
            if (i > 0) Spacer(Modifier.width(if (length % 2 == 0 && i == length / 2) 44.dp else 14.dp))
            Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val digit = shown?.get(i)?.takeIf { it != ' ' }
                Text(
                    digit?.toString() ?: "–",
                    style = EditorialType.figures(108),
                    color = if (digit != null) EditorialPalette.Numeral else EditorialPalette.TextSecondary,
                    maxLines = 1,
                )
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(if (digit != null) EditorialPalette.Numeral else EditorialPalette.Rule),
                )
            }
        }
    }
}

/**
 * The setup screens as a magazine's opening page: a masthead with its rule at the top, a column rule between the
 * intro and the form, and the Googie arc (coral, decoration) with its spark under the intro. Positions follow
 * Glacier's SetupLayout: 130 from the edges, an intro column of 560, 110 between the columns.
 */
private val EditorialSetupBackdrop: @Composable BoxScope.() -> Unit = {
    Text(
        "NIXIE",
        style = EditorialType.heading(30).copy(letterSpacing = 0.32.em),
        color = EditorialPalette.Text,
        modifier = Modifier.align(Alignment.TopStart).padding(start = 130.dp, top = 46.dp),
    )
    Box(
        Modifier
            .align(Alignment.TopStart)
            .padding(start = 130.dp, end = 130.dp, top = 108.dp)
            .fillMaxWidth()
            .height(2.dp)
            .background(EditorialPalette.Rule),
    )
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .padding(start = (130 + 560 + 55 - 1).dp)
            .size(2.dp, 680.dp)
            .background(EditorialPalette.Rule),
    )
    Canvas(
        Modifier
            .align(Alignment.BottomStart)
            .padding(start = 130.dp, bottom = 92.dp)
            .size(540.dp, 64.dp),
    ) { drawGoogieArc() }
}

/** A long, rising arc in coral that ends in a four-point spark of paper white. */
private fun DrawScope.drawGoogieArc() {
    val w = size.width
    val h = size.height
    val end = Offset(w - 14.dp.toPx(), h * 0.3f)
    val arc = Path().apply {
        moveTo(0f, h * 0.9f)
        quadraticTo(w * 0.5f, h * 0.05f, end.x, end.y)
    }
    drawPath(arc, EditorialPalette.Coral, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    val r = 13.dp.toPx()
    val pinch = 2.5f.dp.toPx()
    val spark = Path().apply {
        moveTo(end.x, end.y - r)
        lineTo(end.x + pinch, end.y - pinch)
        lineTo(end.x + r, end.y)
        lineTo(end.x + pinch, end.y + pinch)
        lineTo(end.x, end.y + r)
        lineTo(end.x - pinch, end.y + pinch)
        lineTo(end.x - r, end.y)
        lineTo(end.x - pinch, end.y - pinch)
        close()
    }
    drawPath(spark, EditorialPalette.Paper)
}

/** Editorial's layout slots: sign-in, the top bar and Home. */
val EditorialSlots = NixieSlots(
    pageGround = EditorialPageGround,
    topBarChrome = EditorialTopBarChrome,
    topBarEntry = EditorialTopBarEntry,
    rowHeader = { title, subtitle, modifier -> EditorialRowHeader(title, subtitle, modifier) },
    rowHeaderAt = { index, title, subtitle, modifier ->
        EditorialRowHeader(title, subtitle, modifier, number = index + 1)
    },
    heroBackdrop = EditorialHeroBackdrop,
    // The text never stands on the picture, so the plate keeps its full frame: no fade at the hero's foot.
    heroFade = {},
    heroKicker = { text -> EditorialKicker(text) },
    quickConnectCode = { code, length -> EditorialCode(code, length) },
    setupBackdrop = EditorialSetupBackdrop,
    browse = EditorialBrowse,
    item = EditorialItem,
    player = EditorialPlayer,
)
