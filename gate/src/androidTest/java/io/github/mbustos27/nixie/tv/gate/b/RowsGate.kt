// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.b

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mbustos27.nixie.tv.gate.kit.Fixture
import io.github.mbustos27.nixie.tv.gate.kit.Gate
import io.github.mbustos27.nixie.tv.gate.kit.GateRule
import io.github.mbustos27.nixie.tv.gate.kit.Screen
import io.github.mbustos27.nixie.tv.gate.kit.check
import io.github.mbustos27.nixie.tv.gate.kit.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every row reaches its last item with the remote, and that item is seen whole: Home's Continue watching, a library of
 * about 25,000 films, the Libraries page, the Collections page (about 500), a season of 13 and one of 24 episodes, a
 * cast of 20, and search results behind "Show N more". [Desktop 1.7.x, a tester: a 13-episode season's row that
 * couldn't scroll past E7.]
 */
@RunWith(AndroidJUnit4::class)
class RowsGate {
    @get:Rule val gate = GateRule()

    private val screenWidth: Int get() = Gate.device.displayWidth

    @Test fun seasonOf13_reachesE13() {
        Pages.home()
        Pages.longShow()
        Pages.season(1)
        reachesEpisode(last = 13, season = 1, title = Shows.E13_TITLE)
    }

    @Test fun seasonOf24_reachesE24() {
        Pages.home()
        Pages.longShow()
        Pages.season(2)
        reachesEpisode(last = 24, season = 2, title = Shows.E24_TITLE)
    }

    @Test fun cast_reachesItsLastName() {
        Pages.home()
        Pages.longShow()
        Pages.season(1)
        Pages.cast()
        val walk = Rows.walk(max = Shows.CAST_COUNT + 5)
        check(walk.last.text.contains(Shows.CAST_LAST)) {
            "Right along the cast of ${Shows.CAST_COUNT} stopped on ${walk.last} after ${walk.visited.size}, not '${Shows.CAST_LAST}'"
        }
        seenAndStays(walk, "the cast's last name")
        check(walk.visited.size == Shows.CAST_COUNT) { "the cast row showed ${walk.visited.size} names, not ${Shows.CAST_COUNT}" }
    }

    @Test fun home_continueWatching_reachesItsLastTitle() {
        Pages.home()
        val expected = ServerFacts.resumeTitles(Fixture.basics, HOME_ROW_LIMIT)
        check(expected.size > VISIBLE_CARDS) { "the fixture's Continue watching has ${expected.size} titles: too few to need scrolling" }
        if (Bar.focused()) Keys.go { Keys.down() }
        Pages.firstInRow()
        check(focus().text.contains(expected.first())) {
            "Home's first row starts on ${focus()}, not Continue watching's '${expected.first()}'"
        }
        val walk = Rows.walk(max = expected.size + 3)
        check(walk.last.text.contains(expected.last())) {
            "Right along Continue watching (${expected.size} titles) stopped on ${walk.last} after ${walk.visited.size}, not '${expected.last()}'"
        }
        seenAndStays(walk, "Continue watching's last title")
        check(walk.visited.size == expected.size) { "Continue watching showed ${walk.visited.size} titles, the server has ${expected.size}" }
    }

    /** Up next holds two shows (the fixture): a short row whose last card is reached and where Right stays. */
    @Test fun home_upNext_reachesItsLastShow() {
        Pages.home()
        val expected = ServerFacts.nextUpShows(Fixture.basics, HOME_ROW_LIMIT)
        check(expected.size >= 2) { "the fixture's Up next has ${expected.size} shows; it needs two (upnext.py)" }
        if (Bar.focused()) Keys.go { Keys.down() }
        // Down from Continue watching lands on the Up next card nearest below it, any of them; then to the row's start.
        val shows = expected.map { it.take(SHOW_PREFIX) }
        var downs = 0
        while (shows.none { focus().text.contains(it) }) {
            check(downs < MAX_DOWNS) { "Down x$MAX_DOWNS from Home's first row never reached Up next; focus on ${focus()}" }
            Keys.go { Keys.down() }
            downs++
        }
        Pages.firstInRow()
        check(focus().text.contains(shows.first())) { "Up next starts on ${focus()}, not '${expected.first()}'" }
        val walk = Rows.walk(max = expected.size + 3)
        check(walk.last.text.contains(expected.last().take(SHOW_PREFIX))) {
            "Right along Up next (${expected.size} shows) stopped on ${walk.last} after ${walk.visited.size}, not '${expected.last()}'"
        }
        seenAndStays(walk, "Up next's last show")
    }

    @Test fun library_reachesItsLastFilm() {
        Pages.home()
        val expected = ServerFacts.lastByName(Fixture.basics, "Movies", "Movie")
        Pages.library(Pages.MOVIES_ENTRY, Pages.MOVIES)
        val walk = Rows.gridEndByRail()
        check(walk.last.text.contains(expected)) {
            "the Movies grid's last row (by the A-Z rail, Down, Right) ended on ${walk.last}, not the last film '$expected'"
        }
        check(Rows.fullySeen(walk.last, walk, screenWidth)) { "the last film '$expected' was reached but not fully on screen (${walk.last.bounds})" }
    }

    @Test fun collections_reachesItsLastCollection() {
        Pages.home()
        val expected = ServerFacts.lastCollection(Fixture.basics)
        Pages.collections()
        aToZ()
        val walk = Rows.gridEndByRail()
        check(walk.last.text.contains(expected)) {
            "the Collections grid's last row (by the A-Z rail, Down, Right) ended on ${walk.last}, not the last collection '$expected'"
        }
        check(Rows.fullySeen(walk.last, walk, screenWidth)) { "the last collection '$expected' was reached but not fully on screen" }
    }

    @Test fun libraries_reachesItsLastLibrary() {
        Pages.home()
        Pages.libraries()
        val count = Screen.texts().firstNotNullOfOrNull { Regex("^(\\d+) librar(y|ies)$").find(it)?.groupValues?.get(1)?.toInt() }
            ?: fail("the Libraries page shows no count of libraries; ${Screen.summary()}")
        Pages.firstInRow()
        val walk = Rows.walk(max = count + 3)
        check(walk.visited.size == count) {
            "Right along the Libraries page (it says $count) visited ${walk.visited.size} cards, ending on ${walk.last}"
        }
        check(Rows.fullySeen(walk.last, walk, screenWidth)) { "the last library ${walk.last} was reached but not fully on screen" }
    }

    /**
     * Search results behind "Show N more": every result is reached, row by row (Right along one row, Down, Left along
     * the next), as many as the heading says.
     */
    @Test fun search_reachesEveryResult() {
        Pages.home()
        Pages.search(SEARCH_QUERY)
        Pages.firstSearchResult()
        check(focus().text.contains(SEARCH_SHOWN_FIRST)) { "the first result for '$SEARCH_QUERY' is ${focus()}, not '$SEARCH_SHOWN_FIRST'" }
        val more = Rows.walk(max = 6)
        check(more.last.text.startsWith("Show ") && more.last.text.contains(" more")) {
            "the results for '$SEARCH_QUERY' had no 'Show N more' card after ${more.last}"
        }
        Rows.okOn(" more")
        Screen.waitGone(more.last.text)
        val count = Screen.texts().firstNotNullOfOrNull { Regex("^(\\d+) results? for").find(it)?.groupValues?.get(1)?.toInt() }
            ?: fail("the search heading shows no count after 'Show N more'; ${Screen.summary()}")
        // From the grid's first card, snake down: Right along a row, Down, Left along the next.
        // Up to the type chips, Left to "All", Down to the grid's first result.
        Rows.focusOn("All", max = 10, keyName = "Up") { if (focus().text in CHIPS) Keys.left() else Keys.up() }
        Keys.go { Keys.down() }
        Pages.firstInRow()
        val seen = linkedSetOf<String>()
        repeat(count) { row ->
            // Right along the row from its first result; Down; Left back to the next row's first (Left past it is
            // the search keypad, so one Right comes back).
            val walk = Rows.walk(max = 8)
            walk.visited.filter(::isResult).forEach { seen += it.text }
            check(walk.leftTo == null) { "Right at the end of results row ${row + 1} moved the focus to ${walk.leftTo}" }
            val before = focus()
            Keys.down()
            if (Rows.moved(before) == null) {
                check(seen.size == count) { "the search grid let the remote reach ${seen.size} of the $count results it counts; it ended on $before" }
                check(Rows.fullySeen(before, walk, screenWidth) || walk.visited.size == 1) { "the last result $before was not fully on screen" }
                return
            }
            val back = Rows.walk(max = 8, keyName = "Left") { Keys.left() }
            back.visited.filter(::isResult).forEach { seen += it.text }
            if (back.leftTo != null) Keys.go { Keys.right() }
        }
        fail("Down through $count results never reached the grid's last row")
    }

    /** A result card (a poster), not a key of the search keypad that a Left past the row's start reaches. */
    private fun isResult(card: Focus): Boolean = (card.bounds?.height() ?: 0) >= RESULT_MIN_PX

    /** From the season's first episode: Right reaches E[last] of [season], seen whole, and Right there stays. */
    private fun reachesEpisode(last: Int, season: Int, title: String) {
        val walk = Rows.walk(max = last + 5)
        val reached = Shows.episodeOf(walk.last)
        check(reached == (last to season)) {
            "Right along season $season's $last episodes stopped on E${reached?.first} ${walk.last} after ${walk.visited.size} cards, not E$last"
        }
        check(walk.last.text.contains(title)) { "E$last of season $season shows ${walk.last}, not '$title'" }
        seenAndStays(walk, "E$last")
        check(walk.visited.size == last) { "season $season's row showed ${walk.visited.size} episodes on the way to E$last" }
    }

    private fun seenAndStays(walk: Walk, what: String) {
        check(walk.leftTo == null) { "Right on $what ${walk.last} moved the focus out of the row, to ${walk.leftTo}" }
        check(Rows.fullySeen(walk.last, walk, screenWidth)) { "$what ${walk.last} was reached but not fully on screen (${walk.last.bounds})" }
    }

    /** The Collections page listed A to Z (another check may have left it Z to A); without the order button, as it is. */
    private fun aToZ() {
        if (!Screen.shows(CollectionsOrder.Z_TO_A)) return
        CollectionsOrder.flipTo(CollectionsOrder.A_TO_Z)
        Keys.go { Keys.down() }
    }

    private companion object {
        /** Home's rows ask for 16 titles (HomeRepository.ROW_LIMIT). */
        const val HOME_ROW_LIMIT = 16
        const val VISIBLE_CARDS = 5
        const val SHOW_PREFIX = 18
        const val MAX_DOWNS = 3
        const val RESULT_MIN_PX = 200
        val CHIPS = setOf("Shows", "Episodes", "Movies", "Collections", "People")
        const val SEARCH_QUERY = "Harbour"
        const val SEARCH_SHOWN_FIRST = Shows.SHORT
    }
}
