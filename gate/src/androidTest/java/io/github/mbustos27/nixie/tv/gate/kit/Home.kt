// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.kit

/**
 * The top bar (Search, Home, Libraries, the libraries, Collections, Favorites, Settings, Switch profile) and Home.
 */
object TopBar {
    const val SEARCH = "Search"
    const val HOME = "Home"
    const val LIBRARIES = "Libraries"
    const val COLLECTIONS = "Collections"
    const val FAVORITES = "Favorites"
    const val SETTINGS = "Settings"
    const val PROFILE = "Switch profile"
    private const val MAX_UP = 12
    private const val MAX_ACROSS = 14

    /** True when the focus is on an entry of the top bar (at the top of the screen: Settings has a "Home" too). */
    fun focused(): Boolean {
        val top = Screen.focusedBounds()?.top ?: return false
        return top < Gate.device.displayHeight / TOP_FRACTION && isEntry(Screen.focusedText())
    }

    private const val TOP_FRACTION = 8

    /** Up until the focus is on the top bar; fails if it never gets there. */
    fun enter() {
        var presses = 0
        while (!focused()) {
            if (presses == MAX_UP) fail("Up x$MAX_UP never reached the top bar; focus on '${Screen.focusedText()}'")
            Remote.up()
            presses++
        }
    }

    /** Opens the top bar's [entry] (one of the constants above, or a library's name). */
    fun open(entry: String) {
        focus(entry)
        Remote.ok()
    }

    /** Puts the focus on the top bar's [entry], Left or Right by where it sits (Left past Search leaves the bar). */
    fun focus(entry: String) {
        enter()
        if (Screen.focusedText().contains(entry)) return
        // Each step goes towards where the entry sits now (the bar scrolls), so an overshoot comes back.
        Remote.focusUntil("the top bar's $entry", MAX_ACROSS) {
            if (Screen.focusedText() == entry || Screen.focusedText().startsWith("$entry |")) return@focusUntil null
            check(focused()) { "the focus left the top bar on the way to $entry: '${Screen.focusedText()}'" }
            val target = entryX(entry) ?: fail("the top bar has no entry '$entry'; it shows ${entries()}")
            val here = Screen.focusedBounds()?.centerX() ?: 0
            if (target > here) Remote::right else Remote::left
        }
    }

    /** The top bar's entries, left to right, as the user reads them. */
    fun entries(): List<String> = bar().sortedBy { it.second }.map { it.first }

    private fun entryX(entry: String): Int? = bar().firstOrNull { it.first == entry }?.second

    private fun bar(): List<Pair<String, Int>> {
        val limit = Gate.device.displayHeight / TOP_FRACTION
        return Screen.seen().mapNotNull { seen ->
            val label = seen.label
            if (seen.bounds.top < limit && label != null && isEntry(label)) label to seen.bounds.centerX() else null
        }.distinct()
    }

    /** An entry's label: the fixed ones, and the libraries in between (any short label at the top of the screen). */
    private fun isEntry(text: String): Boolean = text.isNotBlank() && text.length <= MAX_LABEL && !text.contains('\n')

    private const val MAX_LABEL = 40
}

object Home {
    private const val HOME_MS = 60_000L
    private const val MAX_BACKS = 8

    /** Home's own row ("Up next", in every theme's case) or its empty state, with no dialog over it. */
    const val UP_NEXT = "Up next"
    const val EMPTY = "Nothing to continue yet"

    /** Home is shown: the top bar, and Home's "Up next" row or its empty state; no sign-in or dialog over it. */
    fun shown(): Boolean {
        val seen = Screen.seen()
        fun has(text: String, ignoreCase: Boolean = false) = seen.any { it.says(text, ignoreCase) }
        return has(TopBar.SETTINGS) && has(TopBar.PROFILE) && (has(UP_NEXT, ignoreCase = true) || has(EMPTY)) &&
            !has(SignIn.WHO) && !has(SignIn.RESTORE_TITLE)
    }

    /** Waits for Home after a sign-in or a start; fails with what showed instead. */
    fun waitShown(timeoutMs: Long = HOME_MS) {
        if (!waitUntil(timeoutMs) { Nixie.inFront() && shown() }) {
            fail("Home did not show within ${timeoutMs / 1000} s; the screen showed ${Screen.summary()}")
        }
    }

    /** Back to Home through the top bar's Home entry; true when Home showed. */
    fun reach(): Boolean {
        if (!Nixie.inFront()) return false
        var backs = 0
        // Pages opened from pages: Back until a page with the top bar shows, then its Home entry.
        while (!Screen.shows(TopBar.SETTINGS) && backs < MAX_BACKS) {
            Remote.back()
            backs++
        }
        if (!Screen.shows(TopBar.SETTINGS)) return false
        return runCatching {
            TopBar.open(TopBar.HOME)
            waitShown(Screen.WAIT_MS)
        }.isSuccess
    }
}
