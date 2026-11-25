// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import io.github.glacier_jellyfin.androidtv.core.data.playback.MediaSegment
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
import io.github.glacier_jellyfin.androidtv.core.data.playback.UpNextMode

/** Desktop Nixie's rule for stopping in the credits (1.2.0). */
object CreditsRule {

    /**
     * Whether stopping at [positionMs] counts the title as watched: inside its closing credits, an outro (from any
     * source) that starts in the second half and runs to the end, give or take [UpNextMode.TRAILING_SCENE_MS]. The
     * server marks a title played only from about 90 %, and credits often start earlier. Credits with a scene after
     * them do not run to the end: stopping there keeps the resume point.
     */
    fun stoppedInClosingCredits(
        positionMs: Long,
        durationMs: Long,
        segments: List<MediaSegment>,
    ): Boolean {
        if (durationMs <= 0) return false
        return segments.any {
            it.kind == SegmentKind.Outro &&
                it.startMs >= durationMs / 2 &&
                it.startMs < durationMs &&
                durationMs - it.endMs <= UpNextMode.TRAILING_SCENE_MS &&
                positionMs >= it.startMs
        }
    }
}
