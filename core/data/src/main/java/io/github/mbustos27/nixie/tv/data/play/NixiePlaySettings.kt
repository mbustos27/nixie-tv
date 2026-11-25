// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import kotlinx.serialization.Serializable

/**
 * The play stream's own settings (Settings › Playback), kept in their own key of a profile's settings so they never
 * mix with Glacier's.
 */
@Serializable
data class NixiePlaySettings(
    /** Skips looked up online (TheIntroDB, AniSkip) when the server and the chapters have none. Off until switched on. */
    val lookupOnline: Boolean = false,
    /** A range skipped by hand in the first minutes is offered as "Skip intro" on the season's later episodes. */
    val learnSkips: Boolean = true,
)
