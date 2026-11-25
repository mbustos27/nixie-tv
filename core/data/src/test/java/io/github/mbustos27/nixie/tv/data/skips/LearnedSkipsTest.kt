// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.skips

import io.github.glacier_jellyfin.androidtv.core.data.playback.MediaSegment
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedSkipsTest {

    private val season1 = LearnedSkipStore.key("srv", "user", "show", 1)
    private val season2 = LearnedSkipStore.key("srv", "user", "show", 2)

    /** Plays [itemId] from [fromS], every half second, with the remote's presses at the given seconds of play. */
    private class Viewer(val learner: SkipLearner, val key: String?) {
        var now = 0L
        var position = 0L
        var segments: List<MediaSegment> = emptyList()
        var item: Any = "e1"

        fun open(
            id: Any,
            segments: List<MediaSegment> = emptyList(),
            atMs: Long = 0,
        ) {
            item = id
            this.segments = segments
            position = atMs
            tick()
        }

        fun tick(learn: Boolean = true) = learner.sample(item, key, position, segments, learn, now)

        fun play(ms: Long, learn: Boolean = true) {
            repeat((ms / 500).toInt()) {
                now += 500
                position += 500
                tick(learn)
            }
        }

        /** A press of Right or Left: the position jumps by [byMs] between two readings. */
        fun press(byMs: Long) {
            now += 300
            position += byMs
            tick()
        }
    }

    private fun viewer(store: LearnedSkipStore, key: String? = season1) = Viewer(SkipLearner(store), key)

    @Test
    fun `three presses of Right in the first minutes are one skip, learned for the season`() {
        val store = LearnedSkipStore(null)
        val v = viewer(store)
        v.open("e1")
        v.play(40_000)
        v.press(30_000)
        v.press(30_000)
        v.press(30_000)
        v.play(5_000)
        val range = store.get(season1)!!
        assertEquals(40_000L, range.startMs)
        assertEquals(130_000L, range.endMs)
        val offered = SkipLearner.offered(store, season1, learn = true)!!
        assertEquals(MediaSegment(SegmentKind.Intro, 35_000, 130_000, SegmentSource.Learned), offered)
        assertNull(SkipLearner.offered(store, season2, learn = true))
        assertNull(SkipLearner.offered(store, season1, learn = false))
    }

    @Test
    fun `a step back after overshooting corrects where the skip ends`() {
        val store = LearnedSkipStore(null)
        val v = viewer(store)
        v.open("e1")
        v.play(40_000)
        repeat(4) { v.press(30_000) }
        v.press(-10_000)
        v.play(5_000)
        assertEquals(150_000L, store.get(season1)!!.endMs)
    }

    @Test
    fun `nothing is learned from the app's own skip, a short or late jump, or with learning off`() {
        val store = LearnedSkipStore(null)
        val v = viewer(store)
        v.open("e1")
        v.play(40_000)
        v.learner.appSkip(MediaSegment(SegmentKind.Recap, 0, 120_000), v.now)
        v.press(80_000)
        v.play(5_000)
        v.press(20_000)
        v.play(5_000)
        assertNull(store.get(season1))
        v.open("e1-late", atMs = 700_000)
        v.play(1_000)
        v.press(60_000)
        v.play(5_000)
        assertNull(store.get(season1))
        v.open("e2")
        v.play(40_000, learn = false)
        v.press(60_000)
        v.play(5_000, learn = false)
        assertNull(store.get(season1))
        // Not for a film either.
        val film = viewer(store, key = null)
        film.open("m1")
        film.play(40_000)
        film.press(60_000)
        film.play(5_000)
        assertNull(store.get(season1))
    }

    @Test
    fun `no learning where the episode already offers an intro`() {
        val store = LearnedSkipStore(null)
        val v = viewer(store)
        v.open("e1", listOf(MediaSegment(SegmentKind.Intro, 200_000, 290_000, SegmentSource.Chapters)))
        v.play(40_000)
        v.press(60_000)
        v.play(5_000)
        assertNull(store.get(season1))
    }

    @Test
    fun `two skips ten seconds or more apart in a season mark its intro as moving, and nothing is offered there`() {
        val store = LearnedSkipStore(null)
        val v = viewer(store)
        v.open("e1")
        v.play(10_000)
        v.press(60_000)
        v.play(5_000)
        assertTrue(SkipLearner.offered(store, season1, learn = true) != null)
        // Episode 2: the cold open is longer, the intro starts at 1:50.
        v.open("e2", listOfNotNull(SkipLearner.offered(store, season1, learn = true)))
        v.play(110_000)
        v.press(60_000)
        v.play(5_000)
        assertTrue(store.moves(season1))
        assertNull(SkipLearner.offered(store, season1, learn = true))
        // Season 2 is judged on its own.
        val other = viewer(store, season2)
        other.open("s2e1")
        other.play(10_000)
        other.press(60_000)
        other.play(5_000)
        assertFalse(store.moves(season2))
        assertTrue(SkipLearner.offered(store, season2, learn = true) != null)
    }

    @Test
    fun `a skip overlapping the learned one but a minute later marks the season, and is not taking it`() {
        val store = LearnedSkipStore(null)
        val v = viewer(store)
        // Episode 1: 1:00 to 2:30 by hand.
        v.open("e1")
        v.play(60_000)
        repeat(3) { v.press(30_000) }
        v.play(5_000)
        val offered = SkipLearner.offered(store, season1, learn = true)!!
        assertEquals(MediaSegment(SegmentKind.Intro, 55_000, 150_000, SegmentSource.Learned), offered)
        // Episode 2: a longer cold open; the viewer skips 2:00 to 3:30 by hand, inside the offered 0:55 to 2:30.
        v.open("e2", listOf(offered))
        v.play(120_000)
        repeat(3) { v.press(30_000) }
        v.play(3_000)
        assertTrue(store.moves(season1))
        assertNull(SkipLearner.offered(store, season1, learn = true))
        // A seek back into the offered range right after it: not an undo of the offer, which was never taken.
        v.press(-70_000)
        v.play(1_000)
        assertNull(store.get(season1))
    }

    @Test
    fun `skips of different lengths are different parts, and close skips are the same spot`() {
        val store = LearnedSkipStore(null)
        assertFalse(store.noteSkip(season1, 10_000, 70_000))
        // A recap of 40 s skipped later on: another part.
        assertFalse(store.noteSkip(season1, 100_000, 140_000))
        // The same intro, 5 s later: the same spot.
        assertFalse(store.noteSkip(season1, 15_000, 75_000))
        assertTrue(store.noteSkip(season1, 90_000, 150_000))
    }

    @Test
    fun `a learned skip taken back twice is dropped, a seek back into it later forgets it`() {
        val store = LearnedSkipStore(null)
        store.learn(season1, 40_000, 130_000)
        repeat(2) { round ->
            val offered = SkipLearner.offered(store, season1, learn = true)!!
            val v = viewer(store)
            v.open("e${round + 2}", listOf(offered))
            v.play(40_000)
            v.learner.appSkip(offered, v.now)
            v.press(90_000)
            v.play(3_000)
            v.press(-10_000)
            v.play(1_000)
        }
        assertNull(store.get(season1))
        store.learn(season1, 40_000, 130_000)
        val offered = SkipLearner.offered(store, season1, learn = true)!!
        val v = viewer(store)
        v.open("e5", listOf(offered), atMs = 150_000)
        v.play(60_000)
        v.press(-90_000)
        v.play(1_000)
        assertNull(store.get(season1))
    }

    @Test
    fun `the store keeps what it learned in its file and reads it back`() {
        val dir = Files.createTempDirectory("learned").toFile()
        try {
            val file = File(dir, "nixie/learned-skips.json")
            val store = LearnedSkipStore(file)
            store.learn(season1, 40_000, 130_000)
            store.noteSkip(season2, 10_000, 70_000)
            store.noteSkip(season2, 110_000, 170_000)
            val again = LearnedSkipStore(file)
            assertEquals(130_000L, again.get(season1)!!.endMs)
            assertTrue(again.moves(season2))
            again.clear()
            assertNull(LearnedSkipStore(file).get(season1))
            file.writeText("not json")
            assertNull(LearnedSkipStore(file).get(season1))
        } finally {
            dir.deleteRecursively()
        }
    }
}
