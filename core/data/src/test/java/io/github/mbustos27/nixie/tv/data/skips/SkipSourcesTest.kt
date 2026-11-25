// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.skips

import io.github.glacier_jellyfin.androidtv.core.data.media.Chapter
import io.github.glacier_jellyfin.androidtv.core.data.playback.MediaSegment
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentPolicy
import io.github.mbustos27.nixie.tv.data.play.NixiePlaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSourcesTest {

    private fun chapter(name: String, startS: Long) = Chapter(name, startS * 1000, null)

    @Test
    fun `chapter names count as whole words, any case, punctuation and numbers ignored`() {
        val kinds = mapOf(
            "Intro" to SegmentKind.Intro,
            "OPENING 2" to SegmentKind.Intro,
            "Opening Credits" to SegmentKind.Intro,
            "OP" to SegmentKind.Intro,
            "Previously on..." to SegmentKind.Recap,
            "Recap" to SegmentKind.Recap,
            "End Credits" to SegmentKind.Outro,
            "Ending" to SegmentKind.Outro,
            "ED1" to SegmentKind.Outro,
            "Commercial break" to SegmentKind.Commercial,
            "Advertisement" to SegmentKind.Commercial,
        )
        kinds.forEach { (name, kind) -> assertEquals(name, kind, ChapterSkips.kind(name)) }
        listOf("Operation", "Introduction to the case", "Chapter 1", "Credit card", "Edward", "", "Opening night party")
            .forEach { assertNull(it, ChapterSkips.kind(it)) }
    }

    @Test
    fun `chapter segments run to the next chapter, the last to the end, one per kind`() {
        val chapters = listOf(
            chapter("Cold open", 0),
            chapter("Opening", 90),
            chapter("Part A", 180),
            chapter("Opening", 600),
            chapter("Credits", 1300),
        )
        val segments = ChapterSkips.segments(chapters, runtimeMs = 1_420_000)
        assertEquals(
            listOf(
                MediaSegment(SegmentKind.Intro, 90_000, 180_000, SegmentSource.Chapters),
                MediaSegment(SegmentKind.Outro, 1_300_000, 1_420_000, SegmentSource.Chapters),
            ),
            segments,
        )
    }

    @Test
    fun `an intro chapter longer than ten minutes is not one`() {
        val segments = ChapterSkips.segments(listOf(chapter("Intro", 0), chapter("Scene", 700)), runtimeMs = 2_000_000)
        assertTrue(segments.isEmpty())
    }

    private val wanted = SkipChain.wanted(SegmentPolicy())
    private val serverIntro = MediaSegment(SegmentKind.Intro, 60_000, 150_000)
    private val chapterIntro = MediaSegment(SegmentKind.Intro, 61_000, 151_000, SegmentSource.Chapters)
    private val chapterOutro = MediaSegment(SegmentKind.Outro, 1_300_000, 1_420_000, SegmentSource.Chapters)
    private val learnedIntro = MediaSegment(SegmentKind.Intro, 55_000, 150_000, SegmentSource.Learned)

    @Test
    fun `the server comes first, then the chapters, then a learned skip, one per kind`() {
        assertEquals(
            listOf(serverIntro, chapterOutro),
            SkipSources.combine(listOf(serverIntro), listOf(chapterIntro, chapterOutro), learnedIntro, wanted),
        )
        assertEquals(listOf(chapterIntro), SkipSources.combine(emptyList(), listOf(chapterIntro), learnedIntro, wanted))
        assertEquals(listOf(learnedIntro), SkipSources.combine(emptyList(), emptyList(), learnedIntro, wanted))
    }

    @Test
    fun `kinds set to no action take nothing from the other sources`() {
        // Glacier's default: previews and ads are left alone.
        val chapterAd = MediaSegment(SegmentKind.Commercial, 600_000, 660_000, SegmentSource.Chapters)
        assertEquals(emptyList<MediaSegment>(), SkipSources.combine(emptyList(), listOf(chapterAd), null, wanted))
    }

    @Test
    fun `an online answer takes a learned skip's place and yields to the chapters`() {
        val online = MediaSegment(SegmentKind.Intro, 58_000, 149_000, SegmentSource.Online)
        val onlineOutro = MediaSegment(SegmentKind.Outro, 1_310_000, 1_420_000, SegmentSource.Online)
        assertEquals(listOf(online), SkipSources.withOnline(listOf(learnedIntro), listOf(online), wanted))
        assertEquals(
            listOf(chapterIntro, chapterOutro),
            SkipSources.withOnline(listOf(chapterIntro, chapterOutro), listOf(online, onlineOutro), wanted),
        )
        assertEquals(
            setOf(SegmentKind.Intro, SegmentKind.Recap, SegmentKind.Outro),
            SkipSources.missing(listOf(learnedIntro), wanted),
        )
    }

    @Test
    fun `online lookups are off and learning is on until changed`() {
        assertFalse(NixiePlaySettings().lookupOnline)
        assertTrue(NixiePlaySettings().learnSkips)
    }
}
