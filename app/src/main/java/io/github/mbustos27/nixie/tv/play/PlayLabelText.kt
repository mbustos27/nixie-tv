// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.mbustos27.nixie.tv.data.play.EpisodeCode
import io.github.mbustos27.nixie.tv.data.play.PlayLabel
import io.github.mbustos27.nixie.tv.data.play.PlayLabels

/** "S1 E4", or "S1 E4–5" for a file with several episodes. */
@Composable
fun episodeCodeText(code: EpisodeCode): String = code.through
    ?.let { stringResource(R.string.nixie_episode_code_range, code.season, code.episode, it) }
    ?: stringResource(R.string.nixie_episode_code, code.season, code.episode)

/**
 * The main Play button's label for [target] (the episode, or the one a show plays next): "Resume S1 E4", "Play S2 E1",
 * "Resume S1 E4–5". Null for a film or an episode without numbers, which keep Glacier's label.
 */
@Composable
fun playLabelText(target: MediaItem?): String? = when (val label = PlayLabels.main(target)) {
    is PlayLabel.Resume -> stringResource(R.string.nixie_play_resume_episode, episodeCodeText(label.code))
    is PlayLabel.Play -> stringResource(R.string.nixie_play_episode, episodeCodeText(label.code))
    null -> null
}
