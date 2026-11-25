// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.skips

import io.github.glacier_jellyfin.androidtv.core.data.playback.MediaSegment
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A range skipped by hand, offered as "Skip intro" on the season's later episodes. */
@Serializable
data class LearnedRange(
    val startMs: Long,
    val endMs: Long,
    val undone: Int = 0,
    val learnedAt: Long = 0,
)

/** The season's last skips by hand, and whether its intro moves (no learned spot is offered then). */
@Serializable
data class SeasonSkips(val skips: List<List<Long>> = emptyList(), val moves: Boolean = false)

@Serializable
private data class LearnedFile(
    val version: Int = 1,
    val entries: Map<String, LearnedRange> = emptyMap(),
    val seasons: Map<String, SeasonSkips> = emptyMap(),
)

/**
 * Learned skips per server, user, show and season (desktop Nixie's `learned-intros.json`), kept on this device only.
 * Read once, written whole through [save] (a background thread in the app, at once in tests).
 */
class LearnedSkipStore(
    private val file: File?,
    private val save: (Runnable) -> Unit = { it.run() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var loaded = false
    private var entries = mapOf<String, LearnedRange>()
    private var seasons = mapOf<String, SeasonSkips>()

    private fun load() {
        if (loaded) return
        loaded = true
        val text = try {
            file?.takeIf { it.exists() }?.readText()
        } catch (ignored: IOException) {
            null
        } ?: return
        try {
            val data = json.decodeFromString(LearnedFile.serializer(), text)
            entries = data.entries.filterValues { it.endMs > it.startMs }
            seasons = data.seasons.mapValues { (_, v) -> v.copy(skips = v.skips.filter { it.size == 2 }) }
        } catch (ignored: SerializationException) {
            // Unreadable: start over.
        }
    }

    private fun persist() {
        val target = file ?: return
        val text = json.encodeToString(LearnedFile.serializer(), LearnedFile(entries = entries, seasons = seasons))
        save(
            Runnable {
                try {
                    target.parentFile?.mkdirs()
                    val tmp = File(target.path + ".tmp")
                    tmp.writeText(text)
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (ignored: IOException) {
                    // Best effort: the next change writes the whole file again.
                }
            },
        )
    }

    private inline fun <T> locked(block: () -> T): T = synchronized(lock) {
        load()
        block()
    }

    fun get(key: String): LearnedRange? = locked { entries[key] }

    fun learn(
        key: String,
        startMs: Long,
        endMs: Long,
    ) = locked {
        entries = entries + (key to LearnedRange(startMs, endMs, 0, clock()))
        persist()
    }

    fun forget(key: String) = locked {
        if (key in entries) {
            entries = entries - key
            persist()
        }
    }

    /** A skip of the learned range taken back; dropped after [MAX_UNDOS]. */
    fun undo(key: String) = locked {
        val entry = entries[key] ?: return@locked
        val undone = entry.undone + 1
        entries = if (undone >= MAX_UNDOS) entries - key else entries + (key to entry.copy(undone = undone))
        persist()
    }

    /**
     * An episode's first skip by hand from [startMs] to [endMs]. One of the same part (lengths [MOVES_SAME_PART_MS] or
     * less apart) starting [MOVES_GAP_MS] or more from an earlier one in the season marks the season "moves" for good: its
     * intro follows a cold open of a different length each time, and one learned spot would be wrong in the next
     * episode. True when this skip marked it.
     */
    fun noteSkip(
        season: String,
        startMs: Long,
        endMs: Long,
    ): Boolean = locked {
        val entry = seasons[season] ?: SeasonSkips()
        if (entry.moves) return@locked false
        val skip = listOf(startMs, endMs)
        val moved = entry.skips.any { samePart(skip, it) && apart(skip, it) }
        seasons = seasons + (
            season to SeasonSkips(
                if (moved) emptyList() else (entry.skips + listOf(skip)).takeLast(MOVES_KEEP),
                moved,
            )
            )
        persist()
        moved
    }

    fun moves(season: String): Boolean = locked { seasons[season]?.moves == true }

    /** "Forget learned skips" in Settings. */
    fun clear() = locked {
        entries = emptyMap()
        seasons = emptyMap()
        persist()
    }

    companion object {
        /** A learned skip taken back this many times is dropped. */
        const val MAX_UNDOS = 2

        /** Two skips of one part whose starts are this far apart in one season: the intro moves. */
        const val MOVES_GAP_MS = 10_000L

        /** Two skips whose lengths differ by more than this are different parts (a recap one time, the intro the next). */
        const val MOVES_SAME_PART_MS = 15_000L

        /** The season's latest skips kept to compare a new one with. */
        const val MOVES_KEEP = 8

        private val json = Json { ignoreUnknownKeys = true }

        private fun samePart(a: List<Long>, b: List<Long>) = abs((a[1] - a[0]) - (b[1] - b[0])) <= MOVES_SAME_PART_MS

        // Review (M1): how far apart the skips start, as desktop Nixie measures it, not the gap between the ranges: a
        // skip shifted by a longer cold open overlaps the earlier one and still moves.
        private fun apart(a: List<Long>, b: List<Long>) = abs(a[0] - b[0]) >= MOVES_GAP_MS

        /** Where a season's learned skip and its "moves" mark are kept: per server, user, show and season number. */
        fun key(
            serverId: String,
            userId: String,
            seriesId: String,
            season: Int,
        ): String =
            listOf(serverId, userId, seriesId).joinToString(":") { it.replace("-", "").lowercase() } + ":$season"
    }
}

/**
 * Learns skips from the viewer's own seeks while an episode plays (desktop Nixie's `SkipLearner`), one [sample] per
 * position reading.
 *
 * On a TV a skip by hand is several presses of Right (30 s each by default) or a move along the timeline, so jumps
 * forward that follow each other within [SETTLE_MS] are one skip, from where the first left to where the last landed
 * (a step back inside it corrects the landing). A skip of [MIN_JUMP_MS] to [MAX_JUMP_MS] that leaves in the first
 * [MAX_START_MS]:
 * - with no intro on offer, is learned for the season (unless it leaves from inside another segment);
 * - is the episode's first one, noted for the season's "moves" mark;
 * - from the start of a learned intro on offer (within [START_TOLERANCE_MS] and [LearnedSkipStore.MOVES_GAP_MS]),
 *   counts as taking that skip; one that leaves later inside it is a skip shifted by a longer cold open.
 *
 * A seek back of [BACK_MIN_MS] or more into a learned intro on offer takes it back ([UNDO_WINDOW_MS] after its skip, and
 * twice drops it) or, later, forgets it. The app's own skips ([appSkip]) never teach.
 */
class SkipLearner(private val store: LearnedSkipStore) {
    private class Run(
        val fromMs: Long,
        var toMs: Long,
        var atMs: Long,
    )

    private var item: Any? = null
    private var lastPosition: Long? = null
    private var lastAt = 0L
    private var run: Run? = null
    private var appSkipUntil = Long.MIN_VALUE
    private var skipped: Pair<MediaSegment, Long>? = null
    private var noted = false

    /** The app itself seeks past [segment] (its Skip button or an automatic skip): not the viewer finding the intro. */
    fun appSkip(segment: MediaSegment, now: Long) {
        appSkipUntil = now + APP_SKIP_MS
        run = null
        if (segment.source == SegmentSource.Learned) skipped = segment to now
    }

    /**
     * [itemId]: the title playing; [key]: its season's key ([LearnedSkipStore.key]), null for anything but an episode.
     * [learn]: the Settings switch.
     */
    fun sample(
        itemId: Any,
        key: String?,
        positionMs: Long,
        segments: List<MediaSegment>,
        learn: Boolean,
        now: Long,
    ) {
        if (itemId != item) {
            item = itemId
            lastPosition = null
            run = null
            skipped = null
            noted = false
        }
        val previous = lastPosition
        val elapsed = now - lastAt
        lastPosition = positionMs
        lastAt = now
        if (previous == null || key == null) return
        val delta = positionMs - previous
        val open = run
        when {
            delta > elapsed.coerceAtLeast(0) + JUMP_SLACK_MS -> {
                if (now < appSkipUntil) {
                    run = null
                } else if (open == null) {
                    run = Run(previous, positionMs, now)
                } else {
                    open.toMs = positionMs
                    open.atMs = now
                }
            }

            delta <= -BACK_MIN_MS && open != null -> {
                // A step back right after the presses forward: the landing, corrected.
                if (positionMs <= open.fromMs) {
                    run = null
                } else {
                    open.toMs = positionMs
                    open.atMs = now
                }
            }

            delta <= -BACK_MIN_MS -> seekBack(key, positionMs, segments, now)
        }
        val settled = run
        if (settled != null && now - settled.atMs >= SETTLE_MS) {
            run = null
            handSkip(key, settled.fromMs, settled.toMs, segments, learn, now)
        }
    }

    private fun handSkip(
        key: String,
        fromMs: Long,
        toMs: Long,
        segments: List<MediaSegment>,
        learn: Boolean,
        now: Long,
    ) {
        if (toMs - fromMs !in MIN_JUMP_MS..MAX_JUMP_MS || fromMs !in 0..MAX_START_MS) return
        if (learn && !noted) {
            noted = true
            store.noteSkip(key, fromMs, toMs)
        }
        val intros = segments.filter { it.kind == SegmentKind.Intro }
        val learned = intros.firstOrNull { it.source == SegmentSource.Learned }
        if (learned != null) {
            if (fromMs - learned.startMs in 0 until START_TOLERANCE_MS + LearnedSkipStore.MOVES_GAP_MS) {
                skipped = learned to now
            }
        } else if (learn && intros.isEmpty() && segments.none { fromMs in it }) {
            store.learn(key, fromMs, toMs)
        }
    }

    private fun seekBack(
        key: String,
        positionMs: Long,
        segments: List<MediaSegment>,
        now: Long,
    ) {
        val learned =
            segments.firstOrNull { it.kind == SegmentKind.Intro && it.source == SegmentSource.Learned } ?: return
        if (positionMs !in learned) return
        val taken = skipped
        skipped = null
        val undo = taken != null && taken.first == learned && now - taken.second <= UNDO_WINDOW_MS
        if (undo) store.undo(key) else store.forget(key)
    }

    companion object {
        const val MIN_JUMP_MS = 30_000L
        const val MAX_JUMP_MS = 150_000L
        const val MAX_START_MS = 600_000L
        const val BACK_MIN_MS = 5_000L
        const val UNDO_WINDOW_MS = 20_000L

        /** Jumps forward this close together are one skip. */
        const val SETTLE_MS = 2_500L

        /** More than the time that passed plus this between two readings is a jump, not playing. */
        const val JUMP_SLACK_MS = 3_000L

        /** How long after the app's own skip a jump is still the app's. */
        const val APP_SKIP_MS = 5_000L

        /** How much earlier than the learned start the skip is offered: the cold open is not the same length twice. */
        const val START_TOLERANCE_MS = 5_000L

        /** The learned intro offered for a season, or null (learning off, none learned, or the season's intro moves). */
        fun offered(
            store: LearnedSkipStore,
            key: String?,
            learn: Boolean,
        ): MediaSegment? {
            if (!learn || key == null || store.moves(key)) return null
            val range = store.get(key) ?: return null
            return MediaSegment(
                SegmentKind.Intro,
                (range.startMs - START_TOLERANCE_MS).coerceAtLeast(0),
                range.endMs,
                SegmentSource.Learned,
            )
        }
    }
}
