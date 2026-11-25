// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.b

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mbustos27.nixie.tv.gate.kit.GateRule
import io.github.mbustos27.nixie.tv.gate.kit.Remote
import io.github.mbustos27.nixie.tv.gate.kit.Screen
import io.github.mbustos27.nixie.tv.gate.kit.check
import io.github.mbustos27.nixie.tv.gate.kit.waitUntil
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back to a page just left shows it as it was, at once: the same card focused, in the same place (no scroll back to the
 * start), with no reload. [basics-sweep class 13, the owner: "going back to the collection from a movie in the
 * collection makes u wait for the collection to load again".]
 */
@RunWith(AndroidJUnit4::class)
class BackGate {
    @get:Rule val gate = GateRule()

    @Test fun back_fromAFilm_toHome() {
        Pages.home()
        if (Bar.focused()) step(1) { Keys.down() }
        step(2) { Keys.right() }
        roundTrip(page = "Home", child = "a film's page")
    }

    @Test fun back_fromAFilm_toTheLibrary() {
        Pages.home()
        Pages.library(Pages.MOVIES_ENTRY, Pages.MOVIES)
        step(1) { Keys.down() }
        step(2) { Keys.right() }
        roundTrip(page = "the Movies library", child = "a film's page")
    }

    @Test fun back_fromALibrary_toLibraries() {
        Pages.home()
        Pages.libraries()
        Pages.firstInRow()
        step(1) { Keys.right() }
        roundTrip(page = "the Libraries page", child = "a library")
    }

    @Test fun back_fromACollection_toTheCollectionsPage() {
        Pages.home()
        Pages.collections()
        step(1) { Keys.down() }
        step(2) { Keys.right() }
        roundTrip(page = "the Collections page", child = "a collection")
    }

    @Test fun back_fromAFilm_toItsCollection() {
        Pages.home()
        Pages.collections()
        step(1) { Keys.right() }
        Remote.ok()
        check(waitUntil(PAGE_MS) { !Pages.onPage(Pages.COLLECTIONS) }) { "OK on a collection did not open it" }
        // The collection's films: Down from its buttons until a poster (a film's card) has the focus.
        var downs = 0
        while ((focus().bounds?.height() ?: 0) < POSTER_MIN_PX) {
            check(downs < MAX_DOWNS) { "Down x$MAX_DOWNS on the collection's page never reached its films; focus on ${focus()}" }
            Keys.go { Keys.down() }
            downs++
        }
        step(1) { Keys.right() }
        roundTrip(page = "the collection", child = "a film's page")
    }

    @Test fun back_fromAnEpisode_toItsShow() {
        Pages.home()
        Pages.longShow()
        Pages.season(2)
        Rows.walk(max = 30)
        roundTrip(page = "the show's page (season 2)", child = "the episode's page")
    }

    @Test fun back_fromAPerson_toTheCast() {
        Pages.home()
        Pages.longShow()
        Pages.season(1)
        Pages.cast()
        step(4) { Keys.right() }
        roundTrip(page = "the show's cast", child = "the person's page")
    }

    @Test fun back_fromAResult_toSearch() {
        Pages.home()
        Pages.search("Harbour")
        Pages.firstSearchResult()
        roundTrip(page = "the search results", child = "the show's page")
    }

    private fun step(times: Int, key: () -> Unit) = Keys.go(times, key)

    /**
     * OK on the focused card opens [child]; Back returns to [page] with the same card focused, in the same place,
     * within [INSTANT_MS].
     */
    private fun roundTrip(page: String, child: String, maxBacks: Int = 1) {
        val before = focus()
        check(before.text.isNotEmpty()) { "nothing had the focus on $page before opening $child" }
        // "At once" on this device: a key's own answer on the page sets the scale (a busy emulator answers in seconds).
        val instantMs = maxOf(INSTANT_MS, keyAnswerMs() * LAG_FACTOR)
        Remote.ok()
        check(waitUntil(PAGE_MS) { focus().text.let { it.isNotEmpty() && it != before.text } }) {
            "OK on $before did not open $child; ${Screen.summary()}"
        }
        // The child page fully in, as a viewer would look at it before going back.
        Thread.sleep(CHILD_MS)
        var pressed = 0L
        var back = focus()
        var presses = 0
        while (presses < maxBacks && back.text != before.text) {
            pressed = System.currentTimeMillis()
            Keys.back()
            presses++
            waitUntil(if (presses < maxBacks) BETWEEN_BACKS_MS else BACK_MS, POLL_MS) { focus().also { back = it }.text == before.text }
        }
        val took = System.currentTimeMillis() - pressed
        check(back.text == before.text) {
            val keys = if (maxBacks == 1) "Back" else "Back x$maxBacks"
            "$keys from $child to $page put the focus on $back, not $before where it was"
        }
        check(took <= instantMs) {
            "Back from $child to $page took $took ms to show $before again (a key takes ${instantMs / LAG_FACTOR} ms here): a reload, not at once"
        }
        // By the card's centre: its focus scale grows it about its centre (read part way through it, its corner moves).
        val a = before.bounds
        val b = back.bounds
        val near = a == null || b == null ||
            (abs(a.centerX() - b.centerX()) <= NEAR_PX && abs(a.centerY() - b.centerY()) <= NEAR_PX)
        check(near) {
            "Back from $child to $page showed $before at $b, not where it was ($a): the page had scrolled"
        }
    }

    /**
     * How long the page takes to show a key's answer: Right then Left, back to the same card (at a row's end Right
     * stays, Left moves, and one more Right comes back), the longest move.
     */
    private fun keyAnswerMs(): Long {
        val start = focus()
        var longest = 0L
        for (key in listOf<() -> Unit>({ Keys.right() }, { Keys.left() })) {
            val at = focus()
            val pressed = System.currentTimeMillis()
            key()
            if (Rows.moved(at) != null) longest = maxOf(longest, System.currentTimeMillis() - pressed)
        }
        if (focus().text != start.text) Keys.go { Keys.right() }
        check(focus().text == start.text) { "timing a key on the page lost the card ${start}; focus on ${focus()}" }
        return longest
    }

    private companion object {
        const val PAGE_MS = 20_000L
        const val CHILD_MS = 1_500L
        const val BACK_MS = 6_000L
        const val BETWEEN_BACKS_MS = 3_000L
        const val POLL_MS = 50L

        /**
         * "At once", allowing for the emulator's own lag (the accessibility tree follows a key 1-2 s late on a busy one):
         * a page that loads again takes longer, and shows its loading state first.
         */
        const val INSTANT_MS = 2_000L

        /** Back may take this many key answers: a reload (the page's titles asked for again) takes far more. */
        const val LAG_FACTOR = 2L
        const val NEAR_PX = 16
        const val POSTER_MIN_PX = 200
        const val MAX_DOWNS = 4
    }
}
