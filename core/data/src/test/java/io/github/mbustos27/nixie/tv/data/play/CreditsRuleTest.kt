// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import io.github.glacier_jellyfin.androidtv.core.data.playback.MediaSegment
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
import io.github.mbustos27.nixie.tv.data.skips.SegmentSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditsRuleTest {

    private val duration = 2_832_000L
    private val intro = MediaSegment(SegmentKind.Intro, 50_000, 138_000)
    private val credits = MediaSegment(SegmentKind.Outro, 2_700_000, 2_830_000)

    @Test
    fun `stopping inside closing credits that run to the end counts as watched`() {
        assertTrue(CreditsRule.stoppedInClosingCredits(2_710_000, duration, listOf(intro, credits)))
        // From the chapters or an online lookup too.
        val chapters = credits.copy(source = SegmentSource.Chapters, endMs = duration)
        assertTrue(CreditsRule.stoppedInClosingCredits(2_750_000, duration, listOf(chapters)))
    }

    @Test
    fun `stopping before the credits, in credits with a scene after them, or in early credits does not`() {
        assertFalse(CreditsRule.stoppedInClosingCredits(2_690_000, duration, listOf(intro, credits)))
        val sceneAfter = MediaSegment(SegmentKind.Outro, 2_600_000, 2_700_000)
        assertFalse(CreditsRule.stoppedInClosingCredits(2_650_000, duration, listOf(sceneAfter)))
        // An "outro" in the first half is not the end credits.
        val early = MediaSegment(SegmentKind.Outro, 1_000_000, duration)
        assertFalse(CreditsRule.stoppedInClosingCredits(1_200_000, duration, listOf(early)))
        assertFalse(CreditsRule.stoppedInClosingCredits(2_710_000, 0, listOf(credits)))
        assertFalse(CreditsRule.stoppedInClosingCredits(2_710_000, duration, listOf(intro)))
    }
}
