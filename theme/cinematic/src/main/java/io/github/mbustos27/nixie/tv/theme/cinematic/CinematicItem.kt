// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.cinematic

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.startGradient
import io.github.mbustos27.nixie.tv.designsystem.Artwork
import io.github.mbustos27.nixie.tv.designsystem.ItemSlots

/**
 * Cinematic on the item pages: the backdrop stage again (the art full bleed under a side scrim long enough for an
 * 84 sp title, a band under the island, the foot settling into Night), the crumb in peach capitals, the title in the
 * display face, the facts with their badges on plates, a horizon over the Play area, and episode stills with a
 * frame number, the runtime on a plate and the progress along the foot.
 */
val CinematicItem = ItemSlots(
    backdrop = { imageUrl, _, artwork ->
        artwork(imageUrl, Modifier.fillMaxSize())
        ItemScrims()
    },
    crumb = { text -> Kicker(text) },
    title = { text, size, maxWidth -> ItemTitle(text, size, maxWidth) },
    facts = { rating, ageRating, facts, tech -> Facts(rating, ageRating, facts, tech) },
    playArea = { horizonAbove() },
    episodeStill = { imageUrl, number, runtime, progress, played, current, focused, artwork ->
        EpisodeStill(imageUrl, number, runtime, progress, played, current, focused, artwork)
    },
)

/** The item page's scrims over its art; text over a pure white still keeps 4.5:1 (CinematicContrastTest). */
@Composable
internal fun ItemScrims() {
    Box(
        Modifier
            .fillMaxSize()
            .startGradient(*ItemSideScrim)
            .background(Brush.verticalGradient(*HeroTopScrim))
            .background(Brush.verticalGradient(*ItemFootScrim)),
    )
}

/** Small tracked capitals in peach: the hero's kicker and the item page's crumb ("SHOWS · SEASON 2"). */
@Composable
internal fun Kicker(text: String) {
    Text(
        text.uppercase(),
        style = GlacierText.body(17, FontWeight.SemiBold).copy(letterSpacing = 0.14.em),
        color = CinematicPalette.Peach,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ItemTitle(
    text: String,
    size: Int,
    maxWidth: Int,
) {
    Text(
        text,
        style = GlacierText.display(size)
            .copy(lineHeight = (size * TITLE_LEADING).sp, shadow = Shadow(TitleShadow, blurRadius = TITLE_SHADOW_BLUR)),
        color = CinematicPalette.Text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = maxWidth.dp).semantics { heading() },
    )
}

/** The facts: the rating in pink, the age rating on an outlined plate, the facts, the picture's badge on a plate. */
@Composable
private fun Facts(
    rating: String?,
    ageRating: String?,
    facts: List<String>,
    tech: String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        if (rating != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    GlacierIcons.Star,
                    contentDescription = null,
                    tint = CinematicPalette.Pink,
                    modifier = Modifier.size(18.dp),
                )
                Text(rating, style = GlacierText.body(20, FontWeight.Bold), color = CinematicPalette.Pink)
            }
        }
        if (ageRating != null) Badge(ageRating, filled = false)
        if (facts.isNotEmpty()) {
            Text(facts.joinToString("  ·  "), style = GlacierText.body(20), color = CinematicPalette.TextSecondary)
        }
        if (tech != null) Badge(tech, filled = true)
    }
}

/** A badge on a plate: dark and outlined over art, so its text reads on any picture. */
@Composable
private fun Badge(text: String, filled: Boolean) {
    Box(
        Modifier
            .clip(CinematicShape)
            .background(if (filled) CinematicPalette.BadgePlate else Color.Transparent)
            .border(1.dp, CinematicPalette.BadgeStroke, CinematicShape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            style = GlacierText.body(if (filled) 15 else 17, FontWeight.SemiBold).copy(letterSpacing = 0.06.em),
            color = CinematicPalette.Text,
        )
    }
}

/** The horizon over the Play area: where the words meet the controls, coral into pink, fading out. */
private fun Modifier.horizonAbove(): Modifier = drawBehind {
    val width = HORIZON_LENGTH.dp.toPx()
    val top = -HORIZON_GAP.dp.toPx()
    val rtl = layoutDirection == LayoutDirection.Rtl
    val startX = if (rtl) size.width - width else 0f
    drawRect(
        Brush.horizontalGradient(
            *HorizonStops,
            startX = if (rtl) size.width else 0f,
            endX = if (rtl) size.width - width else width,
        ),
        topLeft = Offset(startX, top),
        size = Size(width, 2.dp.toPx()),
    )
}

/**
 * An episode's still: the art under a foot shade; its number at the top left in the frame-counter style (pink with
 * Night when it is the page's episode), the runtime on a plate at the top right; a played one dimmed with a check;
 * the progress along the foot. Focus adds the sheen; Glacier's frame draws the rim.
 */
@Composable
private fun BoxScope.EpisodeStill(
    imageUrl: String?,
    number: Int?,
    runtimeMinutes: Int?,
    progress: Float?,
    played: Boolean,
    current: Boolean,
    focused: Boolean,
    artwork: Artwork,
) {
    artwork(imageUrl, Modifier.fillMaxSize())
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(*EpisodeShadeStops)))
    if (played) Box(Modifier.fillMaxSize().background(CinematicPalette.Night.copy(alpha = 0.45f)))
    if (focused) Box(Modifier.fillMaxSize().drawBehind { drawRect(sheen(size)) })
    if (number != null) {
        Plate(
            stringResource(R.string.cinematic_episode_number, number),
            Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 12.dp),
            highlighted = current,
        )
    }
    if (runtimeMinutes != null) {
        Plate(
            stringResource(R.string.cinematic_runtime_minutes, runtimeMinutes),
            Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 12.dp),
        )
    }
    if (played) {
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp)
                .size(34.dp)
                .clip(CinematicShape)
                .background(CinematicPalette.Pink),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                GlacierIcons.Check,
                contentDescription = null,
                tint = CinematicPalette.Night,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (progress != null && !played) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(4.dp)
                .drawBehind {
                    drawRect(CinematicPalette.ProgressTrack)
                    drawRect(CinematicPalette.Pink, size = Size(size.width * progress.coerceIn(0f, 1f), size.height))
                },
        )
    }
}

/** Small text on a dark plate over the art; [highlighted]: Night on pink. */
@Composable
private fun Plate(
    text: String,
    modifier: Modifier,
    highlighted: Boolean = false,
) {
    Box(
        modifier
            .clip(CinematicShape)
            .background(if (highlighted) CinematicPalette.Pink else CinematicPalette.BadgePlate)
            .border(1.dp, if (highlighted) Color.Transparent else CinematicPalette.BadgeStroke, CinematicShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = GlacierText.body(16, FontWeight.Bold).copy(letterSpacing = 0.04.em),
            color = if (highlighted) CinematicPalette.Night else CinematicPalette.Text,
        )
    }
}

private const val TITLE_LEADING = 1.03f
private const val TITLE_SHADOW_BLUR = 30f
private const val HORIZON_LENGTH = 900
private const val HORIZON_GAP = 18
private val TitleShadow = Color(0x99000000)

/** Long enough for an 84 sp title two lines wide (to 66 % of the width) over a white still. */
internal val ItemSideScrim = arrayOf(
    0f to CinematicPalette.Ground.copy(alpha = 0.94f),
    0.30f to CinematicPalette.Ground.copy(alpha = 0.88f),
    0.50f to CinematicPalette.Ground.copy(alpha = 0.80f),
    0.66f to CinematicPalette.Ground.copy(alpha = 0.68f),
    0.78f to CinematicPalette.Ground.copy(alpha = 0.30f),
    0.92f to Color.Transparent,
)

/** The foot settles fully into Night, where the page's rows begin. */
internal val ItemFootScrim = arrayOf(0.58f to Color.Transparent, 1f to CinematicPalette.Night)

/** An episode still's foot shade: only under its progress and check (its title stays under the card). */
internal val EpisodeShadeStops = arrayOf(0.55f to Color.Transparent, 1f to CinematicPalette.Ground.copy(alpha = 0.7f))
