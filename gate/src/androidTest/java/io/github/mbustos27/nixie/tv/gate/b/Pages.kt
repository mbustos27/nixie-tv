// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.b

import androidx.test.platform.app.InstrumentationRegistry
import io.github.mbustos27.nixie.tv.gate.kit.Fixture
import io.github.mbustos27.nixie.tv.gate.kit.Gate
import io.github.mbustos27.nixie.tv.gate.kit.Home
import io.github.mbustos27.nixie.tv.gate.kit.Nixie
import io.github.mbustos27.nixie.tv.gate.kit.Remote
import io.github.mbustos27.nixie.tv.gate.kit.Screen
import io.github.mbustos27.nixie.tv.gate.kit.SignIn
import io.github.mbustos27.nixie.tv.gate.kit.TopBar
import io.github.mbustos27.nixie.tv.gate.kit.check
import io.github.mbustos27.nixie.tv.gate.kit.fail
import io.github.mbustos27.nixie.tv.gate.kit.waitUntil

/**
 * Stream B's fixtures on the main throwaway Jellyfin (nixie-tv-wt\_real-jellyfin.md, "Shows"): a show with a season of
 * 13 and one of 24 episodes, long titles and 20 cast with long names; a second show of 24 episodes.
 */
object Shows {
    const val LONG = "The Remarkably Long and Winding Chronicles of the Lighthouse Keepers of the Northern Archipelago"
    const val SHORT = "Harbour Lights"
    const val LIBRARY = "Shows"
    const val E13_TITLE = "The Last Lamp Before Winter Closes the Sound for Good"
    const val E24_TITLE = "Twenty-Four Lamps"
    const val CAST_FIRST = "Maximiliano Alejandro de la Fuente-Villanueva"
    const val CAST_LAST = "Kalani Kealamakia-Nāone"
    const val CAST_COUNT = 20

    /** An episode card's number and season, from its synopsis ("Episode 13 of season 1, ..."): null for other cards. */
    fun episodeOf(card: Focus): Pair<Int, Int>? =
        Regex("Episode (\\d+) of season (\\d+)").find(card.text)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
}

/** The pages the checks open, with the remote, the way a viewer gets there. */
object Pages {
    /** The top bar's entries for the libraries: Glacier names them by kind (the server has one of each). */
    const val MOVIES_ENTRY = "Movie"
    const val SHOWS_ENTRY = "Show"

    /** The pages' headings, in words (each theme sets their case). */
    const val MOVIES = "Movies"
    const val SHOWS = "Shows"
    const val COLLECTIONS = "Collections"
    const val LIBRARIES = "Libraries"
    private const val ALL_TAB = "All"
    private val TABS = setOf("Collections", "Genres")
    /** Below the top bar's words (they end near 80 of 1080 rows); a page scrolled a little moves its heading up. */
    private const val BAR_BOTTOM = 0.09
    private const val HEADING_BOTTOM = 0.23
    private const val PAGE_MS = 30_000L
    private const val RAIL_SHARE = 0.9
    private const val HOME_MS = 60_000L

    /**
     * Home, signed in as the checks' user, with the run's theme. While writing checks, `-e gate.b.reuse true` keeps the
     * app as it is when Home comes back (signed in by hand beforehand); the gate's runner never passes it.
     */
    fun home() {
        val reuse = InstrumentationRegistry.getArguments().getString("gate.b.reuse") == "true"
        if (reuse || Nixie.signedInAs == Fixture.basics) {
            if (Nixie.inFront() && Bar.home()) return
            // A check that failed deep in a page: start the app again, still signed in, rather than from fresh data.
            Nixie.forceStop()
            Nixie.launch()
            // The emulator's launcher can put its "Set up Google TV" prompt over Nixie after a start: open Nixie again.
            if (waitUntil(HOME_MS) { (Nixie.inFront() || runCatching { Nixie.launch() }.isSuccess) && Home.shown() }) return
            check(!reuse) { "gate.b.reuse: Home did not come back after a restart; ${Screen.summary()}" }
        }
        Nixie.signedIn()
    }

    /** Opens a top bar entry from wherever the page is and waits for [title] (the page's heading). */
    fun fromBar(entry: String, title: String) {
        Bar.open(entry)
        waitFromBar(title)
        // The page's grid takes the focus from the bar once its first titles are in.
        waitUntil(PAGE_MS) { !Bar.focused() }
    }

    /** A library's page from the top bar, on its "All" tab (a library keeps the tab last chosen, Collections say). */
    fun library(entry: String, heading: String) {
        fromBar(entry, heading)
        if (Screen.texts().none { Regex("^\\d+ collections$").matches(it) }) return
        Rows.focusOn(ALL_TAB, max = 8, keyName = "Up") { if (focus().text in TABS) Keys.left() else Keys.up() }
        Rows.okOn(ALL_TAB)
        check(waitUntil(PAGE_MS) { Screen.texts().any { Regex("^\\d+ titles$").matches(it) } }) {
            "the $heading page did not go back to its All tab; ${Screen.summary()}"
        }
        Keys.go { Keys.down() }
    }

    fun collections() = fromBar(TopBar.COLLECTIONS, COLLECTIONS)

    fun libraries() = fromBar(TopBar.LIBRARIES, LIBRARIES)

    /**
     * The page with [heading] is shown: its heading under the top bar, above its tabs, in any theme's case (Drive-in
     * writes "SHOWS", Cinematic "Shows"); the bar's entry and a tab of the same name don't count.
     */
    fun onPage(heading: String): Boolean {
        val h = Gate.device.displayHeight
        return Screen.seen().any {
            (it.text ?: it.description).orEmpty().equals(heading, ignoreCase = true) &&
                it.bounds.bottom > h * BAR_BOTTOM && it.bounds.top < h * HEADING_BOTTOM
        }
    }

    /**
     * Waits for the page a top bar entry opened: its [heading], or a library page back on the title it showed last, its
     * heading scrolled away (BrowseMemory: a library opened from the bar comes back where it was left): the A–Z rail on
     * screen and the focus in the page, off the bar.
     */
    fun waitFromBar(heading: String) {
        val width = Gate.device.displayWidth
        val back = { !Bar.focused() && Screen.seen().any { it.text == "Z" && it.bounds.left > width * RAIL_SHARE } }
        check(waitUntil(PAGE_MS) { onPage(heading) || back() }) {
            "the $heading page did not show within ${PAGE_MS / 1000} s; ${Screen.summary()}"
        }
    }

    /** Waits for the page with [heading]; fails with what showed. */
    fun waitOn(heading: String, timeoutMs: Long = PAGE_MS) {
        check(waitUntil(timeoutMs) { onPage(heading) }) { "the $heading page did not show within ${timeoutMs / 1000} s; ${Screen.summary()}" }
    }

    /** The long show's page, from the Shows library. */
    fun longShow() {
        library(SHOWS_ENTRY, SHOWS)
        openLongShow()
    }

    /** On the Shows library: OK on the long show's card. */
    fun openLongShow() {
        Rows.focusOn("The Remarkably Long", max = 4, keyName = "Right") { Keys.right() }
        Rows.okOn("The Remarkably Long")
        Screen.waitFor("Season 1", PAGE_MS)
    }

    /** On a show's page: [season] chosen, the focus on its first episode card. */
    fun season(season: Int) {
        Rows.focusOn("Season ", max = 4, keyName = "Down") { Keys.down() }
        if (!focus().text.contains("Season $season")) {
            Rows.focusOn("Season $season", max = 4, keyName = "Right") { Keys.right() }
            Rows.okOn("Season $season")
        }
        // The row opens on the first unwatched episode, so E1 may be out of view: any of the season's synopses.
        Screen.waitFor("of season $season,", PAGE_MS, "season $season's episodes")
        Rows.focusOn("of season $season", max = 2, keyName = "Down") { Keys.down() }
        firstInRow()
    }

    /** On a show's page with the focus on an episode: the cast row, on its first card. */
    fun cast() {
        Keys.go { Keys.down() }
        firstInRow()
        check(focus().text.contains(Shows.CAST_FIRST)) {
            "Down from the episodes and Left to the row's start did not reach the cast's first name; focus on ${focus()}"
        }
    }

    /** Left until the row's first card (the focus stops). */
    fun firstInRow() {
        Rows.walk(max = 40, keyName = "Left") { Keys.left() }
    }

    /** Search: the query typed with the system keyboard; waits for the results' heading. */
    fun search(query: String) {
        fromBarNoWait(TopBar.SEARCH)
        Rows.focusOn("Title, genre, person", max = 3, keyName = "Up") { Keys.up() }
        SignIn.typeIntoFocusedField(query)
        Screen.waitFor("for “$query”", PAGE_MS, "the results' heading for '$query'")
    }

    /** On the search page with results: Right from the field to the type chips, Down to the first result. */
    fun firstSearchResult() {
        Rows.focusOn("All", max = 6, keyName = "Right") { Keys.right() }
        Keys.go { Keys.down() }
    }

    private fun fromBarNoWait(entry: String) = Bar.open(entry)
}

/**
 * The top bar, found by where it is (the entries along the top of the screen), so a library's own entry ("Movie",
 * "Show") counts as the bar too.
 */
object Bar {
    private const val TOP_SHARE = 0.12
    private const val MAX_UP = 8
    private const val MAX_ACROSS = 14
    private const val MAX_BACKS = 8
    private const val HOME_MS = 30_000L

    /** The focus is on an entry of the top bar. */
    fun focused(): Boolean = focus().bounds?.let { it.bottom < Gate.device.displayHeight * TOP_SHARE } == true

    /** Up until the focus is on the bar. */
    fun enter() {
        // Up on the A–Z rail moves along its letters: back into the grid first.
        if (focus().onRail) Keys.go { Keys.left() }
        if (upTo(MAX_UP)) return
        // Deep in a grid or the search keypad: Back takes the focus to the page's top or the bar, as a viewer does.
        val before = focus()
        Remote.back()
        Rows.moved(before)
        if (upTo(MAX_UP)) return
        fail("Up never reached the top bar; focus on ${focus()}")
    }

    private fun upTo(max: Int): Boolean {
        repeat(max) {
            if (focused()) return true
            Keys.go { Keys.up() }
        }
        return focused()
    }

    /** Opens the entry whose words are exactly [entry]; from a page without the bar, Back until one shows it. */
    fun open(entry: String) {
        var backs = 0
        while (!Screen.shows(TopBar.SETTINGS) && backs < MAX_BACKS) {
            Remote.back()
            backs++
        }
        enter()
        if (focus().text != entry) {
            // Towards the entry's place on the bar, so no key is spent at the bar's far end.
            val at = focus().bounds?.centerX() ?: 0
            val target = TopBarLook.pill(entry)?.centerX()
            if (target != null && target < at) {
                along(entry, MAX_ACROSS) { Keys.left() }
            } else {
                val right = runCatching { along(entry, MAX_ACROSS) { Keys.right() } }
                if (right.isFailure) along(entry, MAX_ACROSS * 2) { Keys.left() }
            }
        }
        Rows.okOn(entry)
    }

    /** Home through the bar; true when Home shows. */
    fun home(): Boolean = runCatching {
        open(TopBar.HOME)
        check(waitUntil(HOME_MS) { Home.shown() }) { "Home did not show after its entry; ${Screen.summary()}" }
    }.isSuccess

    private fun along(entry: String, max: Int, key: () -> Unit) {
        var presses = 0
        while (focus().text != entry) {
            if (presses == max) fail("the top bar has no entry '$entry'; focus ended on ${focus()}")
            val before = focus()
            key()
            Rows.moved(before)
            presses++
        }
    }
}
