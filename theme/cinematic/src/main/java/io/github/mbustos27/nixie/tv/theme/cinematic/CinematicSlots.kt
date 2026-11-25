// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.cinematic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.startGradient
import io.github.mbustos27.nixie.tv.designsystem.NavEntryLook
import io.github.mbustos27.nixie.tv.designsystem.NixieSlots
import io.github.mbustos27.nixie.tv.designsystem.nixieShadow

/**
 * Cinematic's layout slots: the stage (page ground), the island (top bar), the shelf titles with their horizon, the
 * hero's full-bleed art under its scrims with its kicker and its pager of stills, the 16:9 stills with the title over
 * the art, Quick Connect's filmstrip and the dusk still behind sign-in. Every slot
 * draws gradients and shapes only: no backdrop blur (the island's one soft shadow is the kind Glacier's own bar
 * casts), no image of its own, no motion beyond Glacier's.
 */
val CinematicSlots = NixieSlots(
    pageGround = { drawStage() },
    topBarChrome = { island() },
    topBarEntry = ::islandEntry,
    rowHeader = { title, subtitle, modifier -> ShelfTitle(title, subtitle, modifier) },
    heroBackdrop = { imageUrl, artwork ->
        artwork(imageUrl, Modifier.fillMaxSize())
        HeroScrims()
    },
    heroPositionMarks = { count, index, progress, thumbnailUrls, artwork ->
        HeroPager(count, index, progress, thumbnailUrls, artwork)
    },
    heroKicker = { text -> Kicker(text) },
    stillCard = { imageUrl, title, line, progress, focused, artwork ->
        StillCard(imageUrl, title, line, progress, focused, artwork)
    },
    quickConnectCode = { code, length -> FilmstripCode(code, length) },
    setupBackdrop = { DuskStill(Modifier.fillMaxSize()) },
    browse = CinematicBrowse,
    item = CinematicItem,
    player = CinematicPlayer,
)

/**
 * The stage, behind pages without a picture: Night, a faint warm light falling from above the middle (the projector's
 * spill on a dark room) and the foot settling into the darkest ground. Three shapes, so a redraw costs nothing.
 */
internal fun DrawScope.drawStage() {
    drawRect(CinematicPalette.Night)
    val center = Offset(size.width * 0.5f, -size.height * 0.18f)
    val rx = size.width * 0.62f
    val ry = size.height * 0.78f
    scale(scaleX = 1f, scaleY = ry / rx, pivot = center) {
        drawRect(
            brush = Brush.radialGradient(*StageSpillStops, center = center, radius = rx),
            topLeft = Offset(0f, center.y - rx),
            size = size.copy(height = rx * 2),
        )
    }
    drawRect(Brush.verticalGradient(*StageFootStops))
}

/**
 * The island: the bar as one floating rounded surface of dense plum glass (Tier B: a tint, not a blur), lit along its
 * top edge, with a soft shadow under it so it floats over a hero and over the stage alike.
 */
internal fun Modifier.island(): Modifier = this
    // The soft shadow only where the box affords it (NixieVisuals: tiers Fake and Flat draw none).
    .nixieShadow {
        dropShadow(
            CinematicShape,
            Shadow(
                radius = 30.dp,
                spread = (-10).dp,
                color = Color.Black.copy(alpha = 0.55f),
                offset = DpOffset(0.dp, 12.dp),
            ),
        )
    }
    .clip(CinematicShape)
    .background(CinematicPalette.IslandTint)
    .background(Brush.verticalGradient(*IslandSheenStops))
    .border(1.dp, Brush.verticalGradient(*IslandRimStops), CinematicShape)

/**
 * An island entry: the page you are on is white with a pink underline; focus is a solid pink plate with Night text and
 * a white rim (shape and fill change, not colour alone), keeping the underline, in Night, when it is the page too.
 */
internal fun islandEntry(focused: Boolean, active: Boolean): NavEntryLook = when {
    focused -> NavEntryLook(
        background = CinematicPalette.Pink,
        foreground = CinematicPalette.Night,
        border = CinematicPalette.Rim,
        underline = if (active) CinematicPalette.Night else Color.Unspecified,
    )

    active -> NavEntryLook(
        background = Color.Transparent,
        foreground = CinematicPalette.Text,
        border = Color.Transparent,
        underline = CinematicPalette.Pink,
    )

    else -> NavEntryLook(Color.Transparent, CinematicPalette.TextSecondary, Color.Transparent)
}

/**
 * A shelf's title: the display face at 30, its count or line beside it on one baseline, and the horizon, a hairline
 * from coral to pink that fades out towards the end of the row (Cinematic's line where the art meets the shelf).
 */
@Composable
internal fun ShelfTitle(
    title: String,
    subtitle: String?,
    modifier: Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            title,
            style = GlacierText.display(30),
            color = CinematicPalette.Text,
            maxLines = 1,
            modifier = Modifier.alignByBaseline().semantics { heading() },
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = GlacierText.body(18),
                color = CinematicPalette.TextSecondary,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .padding(start = 6.dp, bottom = 11.dp)
                .height(2.dp)
                .startGradient(*HorizonStops),
        )
    }
}

/**
 * The hero's scrims over its full-bleed art: a side scrim under the words at the start (the art shows at the end), a
 * band under the island at the top, and the foot settling into Night where the rows begin. Strong enough that warm
 * white and the secondary text stay above 4.5:1 over a pure white still (CinematicContrastTest).
 */
@Composable
internal fun HeroScrims() {
    Box(
        Modifier
            .fillMaxSize()
            .startGradient(*HeroSideScrim)
            .background(Brush.verticalGradient(*HeroTopScrim))
            .background(Brush.verticalGradient(*HeroFootScrim)),
    )
}

internal val HeroSideScrim = arrayOf(
    0f to CinematicPalette.Ground.copy(alpha = 0.94f),
    0.22f to CinematicPalette.Ground.copy(alpha = 0.90f),
    0.40f to CinematicPalette.Ground.copy(alpha = 0.82f),
    0.52f to CinematicPalette.Ground.copy(alpha = 0.74f),
    0.60f to CinematicPalette.Ground.copy(alpha = 0.50f),
    0.72f to CinematicPalette.Ground.copy(alpha = 0.16f),
    0.84f to Color.Transparent,
)

internal val HeroTopScrim = arrayOf(
    0f to CinematicPalette.Ground.copy(alpha = 0.60f),
    0.24f to Color.Transparent,
)

internal val HeroFootScrim = arrayOf(
    0.62f to Color.Transparent,
    1f to CinematicPalette.Night.copy(alpha = 0.80f),
)

private val StageSpillStops = arrayOf(
    0f to CinematicPalette.Peach.copy(alpha = 0.10f),
    0.55f to CinematicPalette.Coral.copy(alpha = 0.04f),
    1f to Color.Transparent,
)

private val StageFootStops = arrayOf(0.55f to Color.Transparent, 1f to CinematicPalette.Ground.copy(alpha = 0.85f))

private val IslandSheenStops = arrayOf(
    0f to CinematicPalette.IslandSheenTop,
    0.4f to CinematicPalette.IslandSheenMid,
    1f to Color.Transparent,
)

private val IslandRimStops = arrayOf(
    0f to CinematicPalette.IslandRimTop,
    0.3f to CinematicPalette.IslandRimMid,
    1f to CinematicPalette.IslandRimFoot,
)
