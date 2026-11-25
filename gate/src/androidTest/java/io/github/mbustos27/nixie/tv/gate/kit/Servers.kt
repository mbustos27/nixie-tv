// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.kit

/** The server list ("Saved servers", then servers found on the network, then "Enter server address manually"). */
object Servers {
    const val SAVED = "Saved servers"
    private const val MAX_CARDS = 8

    fun shown(): Boolean = Screen.shows(SignIn.MANUAL)

    /** From Home: Switch profile, then the server's line at the bottom of Who's watching?. */
    fun openFromHome(current: GateServer) {
        TopBar.open(TopBar.PROFILE)
        Profiles.waitShown()
        Profiles.serverLine(current)
        Screen.waitFor(SignIn.MANUAL, what = "the server list")
    }

    /** The saved servers' cards, by the address each shows. */
    fun saved(): List<String> = Screen.texts().filter { it.contains("://") }.distinct()

    /** Puts the focus on the card of [server] (by its address). */
    fun focusCard(server: GateServer) {
        Remote.focusUntil("the card of ${server.name}", MAX_CARDS) {
            val here = Screen.focusedText()
            if (here.contains(server.typed)) return@focusUntil null
            val card = Screen.boundsOf(server.typed) ?: fail("the server list shows no ${server.typed}: ${saved()}")
            val focus = Screen.focusedBounds()
            if (focus == null || focus.centerY() < card.centerY()) Remote::down else Remote::up
        }
    }

    /** Opens [server] from the list: its profiles, or its sign-in when nobody is signed in there. */
    fun pick(server: GateServer) {
        focusCard(server)
        Remote.ok()
    }
}
