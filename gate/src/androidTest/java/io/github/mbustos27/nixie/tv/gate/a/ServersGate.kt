// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.a

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mbustos27.nixie.tv.gate.kit.Fixture
import io.github.mbustos27.nixie.tv.gate.kit.Gate
import io.github.mbustos27.nixie.tv.gate.kit.GateRule
import io.github.mbustos27.nixie.tv.gate.kit.GateUser
import io.github.mbustos27.nixie.tv.gate.kit.Home
import io.github.mbustos27.nixie.tv.gate.kit.Nixie
import io.github.mbustos27.nixie.tv.gate.kit.Profiles
import io.github.mbustos27.nixie.tv.gate.kit.Remote
import io.github.mbustos27.nixie.tv.gate.kit.Screen
import io.github.mbustos27.nixie.tv.gate.kit.Servers
import io.github.mbustos27.nixie.tv.gate.kit.Settings
import io.github.mbustos27.nixie.tv.gate.kit.SignIn
import io.github.mbustos27.nixie.tv.gate.kit.Themes
import io.github.mbustos27.nixie.tv.gate.kit.check
import io.github.mbustos27.nixie.tv.gate.kit.waitUntil
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two servers with the remote: a second one added, switching between them both ways (each keeps its profile), and
 * removing one through its menu, which takes only that one off the list and can be added again.
 * [Desktop 1.7.x, a tester: a server's "…" opened its sign-in, so Remove could not be pressed.]
 */
@RunWith(AndroidJUnit4::class)
class ServersGate {
    @get:Rule val gate = GateRule()

    @After fun accountsChanged() = Nixie.forgetState()

    @Test fun secondServer_added_andSwitchedBothWays() {
        twoServers()
        // Back to the first: its profile opens without a sign-in.
        Servers.openFromHome(Fixture.second)
        check(
            Servers.saved().any {
                it.contains(
                    Fixture.main.typed,
                )
            } && Servers.saved().any { it.contains(Fixture.second.typed) },
        ) {
            "Saved servers listed ${Servers.saved()}, not both servers"
        }
        Servers.pick(Fixture.main)
        Profiles.waitShown()
        Profiles.pick(Fixture.basics.name)
        expectHomeOn(Fixture.basics, "switching to ${Fixture.main.name}")
        // And to the second again.
        Servers.openFromHome(Fixture.main)
        Servers.pick(Fixture.second)
        Profiles.waitShown()
        Profiles.pick(Fixture.secondUser.name)
        expectHomeOn(Fixture.secondUser, "switching to ${Fixture.second.name}")
    }

    @Test fun server_removedThroughItsMenu_onlyThatOne_andAddedAgain() {
        twoServers()
        // The second server is the one in use: signed in there last.
        val second = Fixture.second
        Servers.openFromHome(second)
        Servers.focusCard(second)

        // Menu opens the card's menu, and nothing else happens.
        Remote.menu()
        check(waitUntil(MENU_MS) { Screen.shows(REMOVE_ITEM) }) {
            val after = if (Servers.shown() && !Profiles.shown()) "the list stayed" else "it opened ${Screen.summary()}"
            "Menu on a saved server's card offered no '$REMOVE_ITEM' ($after)"
        }
        check(Servers.shown() && !Profiles.shown()) { "Menu on the server's card also opened it: ${Screen.summary()}" }

        // Back closes it with nothing done: the card keeps the focus and the server stays.
        Remote.back()
        check(waitUntil(MENU_MS) { !Screen.shows(REMOVE_ITEM) }) { "Back did not close the server's menu" }
        check(Screen.focusedText().contains(second.typed) && Servers.saved().any { it.contains(second.typed) }) {
            "Back on the server's menu left the focus on '${Screen.focusedText()}', servers ${Servers.saved()}"
        }

        // A long OK opens the same menu, never the server's "Who's watching?".
        Remote.longOk()
        check(waitUntil(MENU_MS) { Screen.shows(REMOVE_ITEM) || Profiles.shown() } && Screen.shows(REMOVE_ITEM)) {
            val after = if (Servers.shown() && !Profiles.shown()) "the list stayed" else "it opened ${Screen.summary()}"
            "a long OK on a saved server's card offered no '$REMOVE_ITEM' ($after)"
        }
        check(Servers.shown() && !Profiles.shown()) { "a long OK on the server's card also opened it: ${Screen.summary()}" }

        // Remove asks once; Cancel keeps the server.
        Remote.focusOn(REMOVE_ITEM, { Remote.down() }, max = 3, keyName = "Down")
        Remote.ok()
        check(waitUntil(MENU_MS) { Screen.shows(QUESTION) }) { "Remove did not ask first: ${Screen.summary()}" }
        check(Screen.focusedText() == CANCEL) { "the question opened with the focus on '${Screen.focusedText()}', not Cancel" }
        Remote.ok()
        check(waitUntil(MENU_MS) { !Screen.shows(QUESTION) }) { "Cancel did not close the question" }
        check(Servers.saved().any { it.contains(second.typed) }) { "Cancel removed ${second.typed}: ${Servers.saved()}" }

        // Again, and Remove: only that server leaves the list.
        Servers.focusCard(second)
        Remote.menu()
        Screen.waitFor(REMOVE_ITEM)
        Remote.focusOn(REMOVE_ITEM, { Remote.down() }, max = 3, keyName = "Down")
        Remote.ok()
        Screen.waitFor(QUESTION)
        Remote.focusOn(REMOVE, { Remote.right() }, max = 2, keyName = "Right")
        Remote.ok()
        check(waitUntil(Screen.WAIT_MS) { Servers.saved().none { it.contains(second.typed) } }) {
            "Remove left ${second.typed} in Saved servers: ${Servers.saved()}"
        }
        check(Servers.saved().any { it.contains(Fixture.main.typed) }) { "Remove took ${Fixture.main.typed} off too: ${Servers.saved()}" }

        // Added again by its address, nothing of it is left here: it asks for a sign-in, not "Who's watching?".
        Remote.focusOn(SignIn.MANUAL, { Remote.down() }, max = 8, keyName = "Down")
        Remote.ok()
        Screen.waitFor(SignIn.ADD_SERVER)
        SignIn.typeIntoFocusedField(second.typed)
        Remote.focusOn(SignIn.CONNECT, { Remote.down() }, max = 3, keyName = "Down")
        Remote.ok()
        check(waitUntil(Screen.WAIT_MS * 2) { Screen.shows(SignIn.QUICK_CONNECT_WAITING) || SignIn.formShown() || Profiles.shown() }) {
            "adding ${second.typed} again opened ${Screen.summary()}"
        }
        check(!Profiles.shown()) { "the removed server's profile was still on this TV: ${Profiles.names()}" }
        SignIn.withPassword(Fixture.secondUser)
        SignIn.landHome()
        expectHomeOn(Fixture.secondUser, "adding ${second.name} again")
    }
    /** Signed in on the main server as tvbasics, then on the second as gate2 (added from the server list). */
    private fun twoServers() {
        Nixie.clearData()
        Nixie.launch()
        SignIn.addServer(Fixture.main)
        SignIn.withPassword(Fixture.basics)
        SignIn.landHome()
        Themes.wear(Gate.theme)
        Servers.openFromHome(Fixture.main)
        Remote.focusOn(SignIn.MANUAL, { Remote.down() }, max = 8, keyName = "Down")
        Remote.ok()
        Screen.waitFor(SignIn.ADD_SERVER)
        SignIn.typeIntoFocusedField(Fixture.second.typed)
        Remote.focusOn(SignIn.CONNECT, { Remote.down() }, max = 3, keyName = "Down")
        Remote.ok()
        SignIn.withPassword(Fixture.secondUser)
        SignIn.landHome()
        expectHomeOn(Fixture.secondUser, "adding ${Fixture.second.name}")
    }

    private fun expectHomeOn(user: GateUser, how: String) {
        check(waitUntil(HOME_MS) { Home.shown() }) { "after $how, no Home for ${user.name}: ${Screen.summary()}" }
        val name = Settings.signedInName()
        check(name.contains(user.name)) { "after $how, Settings said '$name', not ${user.name} on ${user.server.name}" }
    }

    private companion object {
        const val REMOVE = "Remove"
        const val REMOVE_ITEM = "Remove from this TV"
        const val QUESTION = "from this TV?"
        const val CANCEL = "Cancel"
        const val MENU_MS = 3_000L
        const val HOME_MS = 45_000L
    }
}
