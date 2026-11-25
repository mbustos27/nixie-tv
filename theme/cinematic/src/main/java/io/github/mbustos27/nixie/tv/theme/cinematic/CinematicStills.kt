// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.cinematic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText

/** Draws an image (Glacier's artwork, with its fill while it loads) into a modifier's bounds. */
internal typealias ArtworkDrawer = @Composable (imageUrl: String?, modifier: Modifier) -> Unit

/**
 * A still on a shelf: the art fills the card, a shade rises from its foot, and the title (the display face) and its
 * line sit on that shade, over the progress. Focus adds the sheen, white at the top corner and gone by a third of
 * the diagonal, on top of Glacier's zoom and the white rim. Over a pure white still the words keep 4.5:1
 * (CinematicContrastTest).
 */
@Composable
internal fun StillCard(
    imageUrl: String?,
    title: String,
    line: String,
    progress: Float?,
    focused: Boolean,
    artwork: ArtworkDrawer,
) {
    Box(Modifier.fillMaxSize()) {
        artwork(imageUrl, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(*StillShadeStops)))
        if (focused) Box(Modifier.fillMaxSize().drawBehind { drawRect(sheen(size)) })
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = if (progress != null) 10.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = GlacierText.display(22),
                color = CinematicPalette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                line,
                style = GlacierText.body(16),
                color = CinematicPalette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress != null) StillProgress(progress, Modifier.padding(top = 8.dp))
        }
    }
}

/** The progress on a still: a faint track and the pink part watched, square-ended, 4 dp. */
@Composable
private fun StillProgress(progress: Float, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .drawBehind {
                drawRect(CinematicPalette.ProgressTrack)
                drawRect(CinematicPalette.Pink, size = Size(size.width * progress.coerceIn(0f, 1f), size.height))
            },
    )
}

/** Cinematic's focus sheen: white at the top-left corner, gone by 38 % of the way along the diagonal. */
internal fun sheen(size: Size): Brush = Brush.linearGradient(
    *SheenStops,
    start = Offset.Zero,
    end = Offset(size.width, size.height * SHEEN_SLOPE),
)

/**
 * The hero's pager, in place of Glacier's dots: the titles' stills at the bottom right, the one showing larger with a
 * white rim and, while the hero moves on by itself, the time to the next title filling a pink line under it. The
 * others are dimmed. At most [PAGER_WINDOW] stills show, around the current one. Decoration, as Glacier's dots are:
 * Left and Right on the hero's buttons change the title.
 */
@Composable
internal fun BoxScope.HeroPager(
    count: Int,
    index: Int,
    progress: () -> Float?,
    thumbnailUrls: List<String?>,
    artwork: ArtworkDrawer,
) {
    Row(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 80.dp, bottom = 74.dp)
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (i in pagerWindow(count, index)) {
            val current = i == index
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PagerStill(thumbnailUrls.getOrNull(i), current, artwork)
                // The line under the current still: the time to the next title; a gap of the same height elsewhere.
                Box(
                    Modifier
                        .width(if (current) PAGER_CURRENT_WIDTH else PAGER_WIDTH)
                        .height(3.dp)
                        .drawBehind {
                            if (!current) return@drawBehind
                            val shown = progress() ?: return@drawBehind
                            drawRect(CinematicPalette.ProgressTrack)
                            drawRect(
                                CinematicPalette.Pink,
                                size = Size(size.width * shown.coerceIn(0f, 1f), size.height),
                            )
                        },
                )
            }
        }
    }
}

/** The stills the pager shows: at most [PAGER_WINDOW], the current one as near the middle as the ends allow. */
internal fun pagerWindow(count: Int, index: Int): IntRange {
    val first = (index - PAGER_WINDOW / 2).coerceIn(0, (count - PAGER_WINDOW).coerceAtLeast(0))
    return first until minOf(count, first + PAGER_WINDOW)
}

@Composable
private fun PagerStill(
    url: String?,
    current: Boolean,
    artwork: ArtworkDrawer,
) {
    val width = if (current) PAGER_CURRENT_WIDTH else PAGER_WIDTH
    Box(
        Modifier
            .size(width, width * NINE_SIXTEENTHS)
            .clip(CinematicShape)
            .border(
                if (current) 2.dp else 1.dp,
                if (current) CinematicPalette.Rim else CinematicPalette.FilmEdge,
                CinematicShape,
            ),
    ) {
        artwork(url, Modifier.fillMaxSize())
        if (!current) Box(Modifier.fillMaxSize().background(CinematicPalette.Night.copy(alpha = 0.5f)))
    }
}

internal const val PAGER_WINDOW = 7
private val PAGER_WIDTH: Dp = 96.dp
private val PAGER_CURRENT_WIDTH: Dp = 128.dp
private const val NINE_SIXTEENTHS = 9f / 16f
private const val SHEEN_SLOPE = 0.7f

/** Clear over the upper art, deep under the words (the title from 56 % of the height, its line from 75 %). */
internal val StillShadeStops = arrayOf(
    0.22f to Color.Transparent,
    0.50f to CinematicPalette.Ground.copy(alpha = 0.80f),
    0.72f to CinematicPalette.Ground.copy(alpha = 0.90f),
    1f to CinematicPalette.Ground.copy(alpha = 0.95f),
)
private val SheenStops = arrayOf(0f to Color.White.copy(alpha = 0.18f), 0.38f to Color.Transparent)
