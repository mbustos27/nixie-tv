// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.designsystem

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope

/** Draws an image (Glacier's `Artwork`, with its loading and retries) wherever a slot places it. */
typealias Artwork = @Composable (imageUrl: String?, modifier: Modifier) -> Unit

/**
 * Phase 2: the library pages (`LibraryScreen`, `FilterChip`, `GridCard`, `PosterCard`). Every slot is null by
 * default, which keeps Glacier's own drawing.
 */
@Immutable
class BrowseSlots(
    /** A library page's heading: its [title] and the [line] under it (the count, or the folder path). */
    val libraryHeader: (@Composable (title: String, line: String, modifier: Modifier) -> Unit)? = null,

    /**
     * Glacier's chips (`FilterChip`): the library's filters, the detail page's season picker and the track chips.
     * [active] is the chosen one. Returns the chip's colours; the underline, when set, marks [active].
     */
    val chip: ((focused: Boolean, active: Boolean) -> NavEntryLook)? = null,

    /** One letter of the A–Z rail: [current] is the letter in view, [available] has titles. */
    val letterRail: ((focused: Boolean, current: Boolean, available: Boolean) -> NavEntryLook)? = null,

    /**
     * A poster's art (the library grid's `GridCard` and the rows' `PosterCard`), inside Glacier's focus frame and click
     * handling; the caption under it stays Glacier's. [artwork] draws [imageUrl].
     */
    val posterArt: (
        @Composable BoxScope.(imageUrl: String?, focused: Boolean, artwork: Artwork) -> Unit
    )? = null,
)

/** Phase 2: the item pages (`DetailScreen`, `EpisodeCard`, `DetailBackdrop`, `FactsRow`). */
@Immutable
class ItemSlots(
    /**
     * The picture at the top of an item page (`DetailBackdrop`), [height] dp tall, scrolling away with the page; the
     * title and facts sit over its lower left. [artwork] draws [imageUrl] with Glacier's slow zoom.
     */
    val backdrop: (
        @Composable BoxScope.(imageUrl: String?, height: Int, artwork: Artwork) -> Unit
    )? = null,

    /** The small line above the title ("MOVIES · SEASON 2"), already in its final case. */
    val crumb: (@Composable (text: String) -> Unit)? = null,

    /** The title, at most two lines, [size] sp and [maxWidth] dp wide. */
    val title: (@Composable (text: String, size: Int, maxWidth: Int) -> Unit)? = null,

    /**
     * The facts line: the community [rating] ("7.8"), the [ageRating] badge ("PG-13"), the [facts] ("2026", "1 h 58
     * min", "Drama") and the [tech] badge ("4K · HDR10"); any of them may be missing.
     */
    val facts: (
        @Composable (rating: String?, ageRating: String?, facts: List<String>, tech: String?) -> Unit
    )? = null,

    /**
     * The Play area's frame: the row of Play or Resume, favourite, watched, trailer and the track chips gets this
     * modifier (a board, a marquee, padding), its buttons and focus staying Glacier's.
     */
    val playArea: (Modifier.() -> Modifier)? = null,

    /**
     * An episode's still (`EpisodeCard`, the series and episode pages' rows) inside Glacier's frame and click handling;
     * the title and line under it stay Glacier's. [number] and [runtimeMinutes] may be missing; [progress] is null
     * when unstarted; [current] is the episode the page is about.
     */
    val episodeStill: (
        @Composable BoxScope.(
            imageUrl: String?,
            number: Int?,
            runtimeMinutes: Int?,
            progress: Float?,
            played: Boolean,
            current: Boolean,
            focused: Boolean,
            artwork: Artwork,
        ) -> Unit
    )? = null,
)

/** Phase 2: the player's chrome (`PlayerOsd`, `PlayerScreen`'s scrims, `SkipButton`, `UpNextCard`, `OptionsPanel`). */
@Immutable
class PlayerSlots(
    /** The shade behind the OSD at the [top] (title, clock) or the bottom (timeline, buttons), over the picture. */
    val osdScrim: (DrawScope.(top: Boolean) -> Unit)? = null,

    /**
     * The seek bar's drawing inside Glacier's focusable bar (keys, scrubbing and preview stay Glacier's): [position]
     * and [buffered] as shares of the length, [segments] (intro, credits) and [chapters] starts as shares too.
     */
    val seekBar: (
        @Composable BoxScope.(
            position: Float,
            buffered: Float,
            focused: Boolean,
            segments: List<ClosedFloatingPointRange<Float>>,
            chapters: List<Float>,
        ) -> Unit
    )? = null,

    /** The times under the seek bar: [position] ("1:02:03"), the [chapter] name or "", and [remaining] ("−41:20"). */
    val times: (@Composable (position: String, chapter: String, remaining: String?) -> Unit)? = null,

    /**
     * The Skip button's face ("Skip intro"), inside Glacier's clickable; [remainingSeconds] until the segment ends and
     * [played] (0..1, read while drawing) for a countdown.
     */
    val skipButton: (
        @Composable (label: String, focused: Boolean, remainingSeconds: Long, played: () -> Float) -> Unit
    )? = null,

    /**
     * Up next's line: "Next episode in 23 s" with [seconds] left, or, when the server's autoplay is off, "Up next" with
     * [seconds] null (no countdown: the episode waits for the viewer). Changed 2026-11-21: [seconds] became nullable.
     */
    val upNextCountdown: (@Composable (text: String, seconds: Long?) -> Unit)? = null,

    /** The container of the player's cards and panels (Up next, the track menus): clip, fill, edge. */
    val panelChrome: (Modifier.() -> Modifier)? = null,
)
