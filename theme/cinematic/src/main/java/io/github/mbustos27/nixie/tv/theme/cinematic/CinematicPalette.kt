// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.cinematic

import androidx.compose.ui.graphics.Color

/**
 * Cinematic's colours: the shared desert-sunset palette as Nixie for Windows' Cinematic theme uses it (its
 * Themes\Cinematic\Cards.xaml), dark only, as a TV is. The colour rule is every theme's: hot pink means "you can act
 * on this", text on pink is Night, peach is decoration. Whatever sits on artwork is near white over a dark scrim.
 */
object CinematicPalette {
    /** The page ground and the text on pink. */
    val Night = Color(0xFF1A0710)

    /** The darkest ground, and the scrims' colour (the hero's side scrim on Windows). */
    val Ground = Color(0xFF12050B)

    /** A raised sheet: the ground under artwork that has not loaded. */
    val ArtGround = Color(0xFF2A0F19)

    /** Text, warm white (on art and on the page), and its secondary line. */
    val Text = Color(0xFFFFF6F3)
    val TextSecondary = Color(0xFFE6D5D7)

    /**
     * "You can act on this", and its deep shade: a primary control at rest, with Night text on it (Glacier draws its
     * Void on both accents), so the deep shade is a rose light enough for 4.5:1, not the palette's crimson (3:1).
     */
    val Pink = Color(0xFFF96985)
    val DeepRose = Color(0xFFD9506A)

    /** Decoration only: the kicker peach and the coral of the horizon. */
    val Peach = Color(0xFFF7AA9C)
    val Coral = Color(0xFFEC6A79)

    /** The focus rim: white, inside the pink glow. */
    val Rim = Color(0xFFFFFFFF)

    /** The track under a progress line on art (a still, the hero's pager, the seek bar's buffer). */
    val ProgressTrack = Color(0x47FFFFFF)

    /** The chosen chip or letter: a faint warm-white plate under white text. */
    val ChosenPlate = Color(0x29FFEDEF)

    /** Plates under small text on art (badges, an episode's runtime) and their edge. */
    val BadgePlate = Color(0xB3170508)
    val BadgeStroke = Color(0x73FFFFFF)

    /** The player: the seek bar's film band, intro and credits on it, its perforations; Skip's plate; panels. */
    val FilmBand = Color(0xCC0E0408)
    val SegmentTint = Color(0x73F7AA9C)
    val Perforation = Color(0x42FFFFFF)
    val FilmEdgeLight = Color(0x66FFFFFF)
    val SkipPlate = Color(0xE61A0710)
    val PanelFill = Color(0xEB170508)

    /**
     * The island (the top bar): Tier B glass everywhere, a dense plum tint with a lit top edge and a faint sheen, no
     * live blur (the plan's low-memory rule). Dense enough that its secondary text stays above 4.5:1 over a white still.
     */
    val IslandTint = Color(0xD91E0812)
    val IslandSheenTop = Color(0x1AFFFFFF)
    val IslandSheenMid = Color(0x05FFFFFF)
    val IslandRimTop = Color(0x61FFFFFF)
    val IslandRimMid = Color(0x1AFFFFFF)
    val IslandRimFoot = Color(0x0FFFFFFF)

    /** The film base and its sprocket holes, and a frame's lit top and dark foot (Quick Connect's filmstrip). */
    val FilmBase = Color(0xFF0E0408)
    val FilmEdge = Color(0x1FFFFFFF)
    val Sprocket = Color(0xFF3B1A26)
    val FrameTop = Color(0xFF3A1426)
    val FrameFoot = Color(0xFF200914)
}
