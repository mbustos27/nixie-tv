// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.cinematic

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.startGradient
import io.github.mbustos27.nixie.tv.designsystem.Artwork
import io.github.mbustos27.nixie.tv.designsystem.BrowseSlots
import io.github.mbustos27.nixie.tv.designsystem.NavEntryLook

/**
 * Cinematic on the library pages: a page title in the display face over its line and a short horizon, chips and
 * letters in the island's language (a pink plate and a white rim for focus, an underline or a plate for the chosen
 * one), and posters with a lit top edge and the focus sheen.
 */
val CinematicBrowse = BrowseSlots(
    libraryHeader = { title, line, modifier -> LibraryHeader(title, line, modifier) },
    chip = ::cinematicChip,
    letterRail = ::cinematicLetter,
    posterArt = { imageUrl, focused, artwork -> PosterArt(imageUrl, focused, artwork) },
)

@Composable
private fun LibraryHeader(
    title: String,
    line: String,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = GlacierText.display(50),
            color = CinematicPalette.Text,
            maxLines = 1,
            modifier = Modifier.semantics { heading() },
        )
        Text(line, style = GlacierText.body(19), color = CinematicPalette.TextSecondary, maxLines = 1)
        Box(Modifier.width(360.dp).height(2.dp).startGradient(*HorizonStops))
    }
}

/**
 * A chip (the library's filters, the season picker): focus is a pink plate with Night text and a white rim; the
 * chosen one is white on a faint plate with a pink underline (Night when it has focus too); the rest are glass.
 */
internal fun cinematicChip(focused: Boolean, active: Boolean): NavEntryLook = when {
    focused -> NavEntryLook(
        background = CinematicPalette.Pink,
        foreground = CinematicPalette.Night,
        border = CinematicPalette.Rim,
        underline = if (active) CinematicPalette.Night else Color.Unspecified,
    )

    active -> NavEntryLook(
        CinematicPalette.ChosenPlate,
        CinematicPalette.Text,
        Color.Transparent,
        CinematicPalette.Pink,
    )

    else -> NavEntryLook(CinematicTokens.glassFill, CinematicPalette.TextSecondary, Color.Transparent)
}

/**
 * A letter of the A–Z rail (34 × 20, too small for an underline): focus is the pink plate, the letter in view a faint
 * plate in white, letters with titles the secondary colour, letters without them faint.
 */
internal fun cinematicLetter(
    focused: Boolean,
    current: Boolean,
    available: Boolean,
): NavEntryLook = when {
    focused -> NavEntryLook(CinematicPalette.Pink, CinematicPalette.Night, CinematicPalette.Rim)
    current -> NavEntryLook(CinematicPalette.ChosenPlate, CinematicPalette.Text, Color.Transparent)
    available -> NavEntryLook(Color.Transparent, CinematicPalette.TextSecondary, Color.Transparent)
    else -> NavEntryLook(Color.Transparent, CinematicPalette.TextSecondary.copy(alpha = 0.32f), Color.Transparent)
}

/** A poster: the art, a lit edge along its top (light falling on a print), and the sheen when it has focus. */
@Composable
private fun BoxScope.PosterArt(
    imageUrl: String?,
    focused: Boolean,
    artwork: Artwork,
) {
    artwork(imageUrl, Modifier.fillMaxSize())
    Box(Modifier.fillMaxSize().border(1.dp, Brush.verticalGradient(*ArtEdgeStops), CinematicShape))
    if (focused) Box(Modifier.fillMaxSize().drawBehind { drawRect(sheen(size)) })
}

/** The horizon: coral into pink, fading out (the shelf titles' line). */
internal val HorizonStops = arrayOf(
    0f to CinematicPalette.Coral.copy(alpha = 0.55f),
    0.35f to CinematicPalette.Pink.copy(alpha = 0.35f),
    1f to Color.Transparent,
)

/** A print's lit edge: brightest along the top. */
internal val ArtEdgeStops = arrayOf(0f to Color(0x2EFFFFFF), 0.2f to Color(0x1AFFFFFF), 1f to Color(0x0FFFFFFF))
