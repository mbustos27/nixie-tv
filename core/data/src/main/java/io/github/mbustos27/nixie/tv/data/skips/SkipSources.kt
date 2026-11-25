// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.skips

import io.github.glacier_jellyfin.androidtv.core.data.media.Chapter
import io.github.glacier_jellyfin.androidtv.core.data.playback.MediaSegment
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind

/**
 * Where a Skip button came from, in desktop Nixie's order of precedence per kind: the server's media segments, then the
 * file's chapters, then an online lookup (opt-in), then a range skipped by hand earlier in the season. A source fills a
 * kind only when no source before it gave one, so the viewer never gets two buttons for the same part.
 */
enum class SegmentSource { Server, Chapters, Online, Learned }

/** Chapter names that stand for a part to skip (desktop Nixie's `intro_sources.chapter_segments`). */
object ChapterSkips {

    /** The longest an intro, recap or ad chapter may be: a mislabelled chapter that runs to the end is not one. */
    const val MAX_FRONT_MS = 600_000L

    private const val NUMBER = "( ?\\d{1,2})?"
    private val recap = Regex("recap|previously( on.*)?|story so far|last time on.*")
    private val intro = Regex(
        "(intro|introduction|opening|opening (credits|theme|titles?)|op|title sequence|main titles?|theme song)$NUMBER",
    )
    private val outro = Regex(
        "(credits|end credits|ending credits|closing credits|ending|ending theme|ed|outro|end titles?)$NUMBER",
    )
    private val commercial = Regex("(commercials?( break)?|ad break|ads?|advert(isement)?s?)$NUMBER")
    private val notWord = Regex("[^\\p{L}\\p{N}]+")

    /**
     * The kind a chapter name stands for, or null. Whole words only, the case, punctuation and a chapter number
     * ignored: "Opening 2" and "END CREDITS" count, "Operation" and "Introduction to the case" do not.
     */
    fun kind(name: String): SegmentKind? {
        val text = name.replace(notWord, " ").trim().lowercase()
        if (text.isEmpty()) return null
        return when {
            recap.matches(text) -> SegmentKind.Recap
            intro.matches(text) -> SegmentKind.Intro
            outro.matches(text) -> SegmentKind.Outro
            commercial.matches(text) -> SegmentKind.Commercial
            else -> null
        }
    }

    /**
     * At most one segment per kind, from a named chapter's start to the next chapter's (the last one's to [runtimeMs]).
     * Everything but credits must be shorter than [MAX_FRONT_MS].
     */
    fun segments(chapters: List<Chapter>, runtimeMs: Long?): List<MediaSegment> {
        val sorted = chapters.sortedBy { it.startMs }
        val seen = mutableSetOf<SegmentKind>()
        val out = mutableListOf<MediaSegment>()
        sorted.forEachIndexed { index, chapter ->
            val kind = kind(chapter.name) ?: return@forEachIndexed
            if (kind in seen) return@forEachIndexed
            val end = sorted.getOrNull(index + 1)?.startMs ?: runtimeMs?.takeIf { it > 0 } ?: return@forEachIndexed
            if (end <= chapter.startMs) return@forEachIndexed
            if (kind != SegmentKind.Outro && end - chapter.startMs > MAX_FRONT_MS) return@forEachIndexed
            seen += kind
            out += MediaSegment(kind, chapter.startMs, end, SegmentSource.Chapters)
        }
        return out
    }
}

/** How the sources are put together. */
object SkipSources {

    /**
     * The server's segments, then for each [wanted] kind still missing the chapters' and then the [learned] one. Sorted
     * by start.
     */
    fun combine(
        server: List<MediaSegment>,
        chapters: List<MediaSegment>,
        learned: MediaSegment?,
        wanted: Set<SegmentKind>,
    ): List<MediaSegment> {
        val out = server.toMutableList()
        val have = server.mapTo(mutableSetOf()) { it.kind }
        for (segment in chapters + listOfNotNull(learned)) {
            if (segment.kind in have || segment.kind !in wanted) continue
            out += segment
            have += segment.kind
        }
        return out.sortedBy { it.startMs }
    }

    /**
     * Segments found online, added late (the lookup never holds playback up): each takes the place of a learned one of
     * its kind and yields to the server's, the chapters' and an earlier online one.
     */
    fun withOnline(
        current: List<MediaSegment>,
        online: List<MediaSegment>,
        wanted: Set<SegmentKind>,
    ): List<MediaSegment> {
        var out = current
        for (segment in online.filter { it.kind in wanted }) {
            val taken = out.any { it.kind == segment.kind && it.source != SegmentSource.Learned }
            if (!taken) out = out.filterNot { it.kind == segment.kind } + segment.copy(source = SegmentSource.Online)
        }
        return out.sortedBy { it.startMs }
    }

    /** The kinds the server gave nothing for, among those [wanted]: what is still worth looking up. */
    fun missing(current: List<MediaSegment>, wanted: Set<SegmentKind>): Set<SegmentKind> =
        wanted - current.filter { it.source != SegmentSource.Learned }.mapTo(mutableSetOf()) { it.kind }
}
