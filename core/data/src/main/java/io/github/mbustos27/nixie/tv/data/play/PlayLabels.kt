// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem

/** An episode's place in its show: "S1 E4", or "S1 E4–5" for a file that holds two or more episodes. */
data class EpisodeCode(
    val season: Int,
    val episode: Int,
    val lastEpisode: Int? = null,
) {
    /** The last episode when the file holds more than one; null otherwise. */
    val through: Int? get() = lastEpisode?.takeIf { it > episode }
}

/** What the main Play button says (desktop Nixie 1.2.0). */
sealed interface PlayLabel {
    /** "Resume S1 E4". */
    data class Resume(val code: EpisodeCode) : PlayLabel

    /** "Play S2 E1". */
    data class Play(val code: EpisodeCode) : PlayLabel
}

object PlayLabels {

    /** The episode's code; null for anything but an episode with a season and an episode number. */
    fun code(item: MediaItem): EpisodeCode? {
        if (item.kind != ItemKind.Episode) return null
        val season = item.seasonNumber ?: return null
        val episode = item.episodeNumber ?: return null
        return EpisodeCode(season, episode, item.episodeNumberEnd)
    }

    /**
     * The label for playing [target]: the episode itself, or the one a show's page or the hero plays (its next episode).
     * Resume when it has progress, Play otherwise, always naming the episode. Null for a film and for anything without
     * an episode code: Glacier's own label stays ("Resume · 71 min left", "Play").
     */
    fun main(target: MediaItem?): PlayLabel? {
        val code = target?.let(::code) ?: return null
        return if (target.progress != null) PlayLabel.Resume(code) else PlayLabel.Play(code)
    }

    /** "Play from the start" goes beside it when the title has progress. */
    fun fromStart(target: MediaItem?): Boolean = target?.progress != null
}
