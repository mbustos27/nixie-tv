// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.a

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mbustos27.nixie.tv.gate.kit.Fixture
import io.github.mbustos27.nixie.tv.gate.kit.Gate
import io.github.mbustos27.nixie.tv.gate.kit.GateRule
import io.github.mbustos27.nixie.tv.gate.kit.Home
import io.github.mbustos27.nixie.tv.gate.kit.Jellyfin
import io.github.mbustos27.nixie.tv.gate.kit.Nixie
import io.github.mbustos27.nixie.tv.gate.kit.Profiles
import io.github.mbustos27.nixie.tv.gate.kit.Remote
import io.github.mbustos27.nixie.tv.gate.kit.Screen
import io.github.mbustos27.nixie.tv.gate.kit.Settings
import io.github.mbustos27.nixie.tv.gate.kit.SignIn
import io.github.mbustos27.nixie.tv.gate.kit.Themes
import io.github.mbustos27.nixie.tv.gate.kit.TopBar
import io.github.mbustos27.nixie.tv.gate.kit.check
import io.github.mbustos27.nixie.tv.gate.kit.waitUntil
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every menu and dialog item does only what it says, nothing passes through to what is under it, and Back closes it
 * leaving everything as it was: Continue watching's card menu, the quit question, removing a profile, and the
 * settings take-over question after a sign-in.
 */
@RunWith(AndroidJUnit4::class)
class MenusGate {
    @get:Rule val gate = GateRule()

    @Test fun continueWatchingMenu_backAndCancelKeep_removeAndUndo() {
        Nixie.signedIn()
        val title = firstContinueWatchingCard()

        // The menu key opens it; Back closes it and the card keeps the focus.
        Remote.menu()
        check(
            waitUntil(SHEET_MS) { Screen.shows(CW_SHEET) },
        ) { "the menu key on '$title' opened no menu: ${Screen.summary()}" }
        check(
            Screen.focusedText().contains(CANCEL),
        ) { "the card's menu opened with the focus on '${Screen.focusedText()}', not Cancel" }
        Remote.back()
        check(waitUntil(SHEET_MS) { !Screen.shows(CW_SHEET) }) { "Back did not close the card's menu" }
        check(
            Screen.focusedText().contains(title) && Home.shown(),
        ) { "Back on the card's menu left '${Screen.focusedText()}' focused, not '$title'" }

        // Holding OK opens it too, and does not open the title underneath.
        Remote.longOk()
        check(
            waitUntil(SHEET_MS) { Screen.shows(CW_SHEET) },
        ) { "holding OK on '$title' opened no menu: ${Screen.summary()}" }
        check(Screen.showsIgnoringCase(CONTINUE_ROW)) { "holding OK on '$title' left Home: ${Screen.summary()}" }
        check(
            Screen.focusedText().contains(CANCEL),
        ) { "holding OK pressed past Cancel: the focus is on '${Screen.focusedText()}'" }
        Remote.ok()
        check(waitUntil(SHEET_MS) { !Screen.shows(CW_SHEET) }) { "Cancel did not close the card's menu" }
        check(
            Screen.focusedText().contains(title),
        ) { "Cancel took '$title' away: the focus is on '${Screen.focusedText()}'" }

        // Remove takes out that card only, the focus stays in its place on the Undo card (for 10 s), and OK there puts
        // it back, resume point and all. Should the check stop in between, the resume point is put back on the server.
        val others = rowTitles() - title
        var undone = false
        try {
            Remote.menu()
            Screen.waitFor(CW_SHEET)
            Remote.focusOn(REMOVE, { Remote.right() }, max = 3, keyName = "Right")
            Remote.ok()
            // The Undo card stands in its place; themes that write on their cards (Cinematic) give it no caption.
            check(Screen.waitFocus(Screen.WAIT_MS) { it.contains(UNDO) }) {
                "after Remove the focus went to '${Screen.focusedText()}', not the Undo card in '$title''s place"
            }
            // OK at once: the Undo card lasts 10 s, and a whole-screen read can take seconds on the emulator (after the
            // 10 s an OK plays the next card, as designed). The rest of the row is compared after the Undo.
            Remote.ok()
            undone = waitUntil(Screen.WAIT_MS) { !Screen.shows(UNDO) && Screen.focusedText().contains(title) }
            check(undone) { "Undo did not bring '$title' back: focus on '${Screen.focusedText()}', row ${rowTitles()}" }
            val row = rowTitles()
            check(others.all { it in row }) { "Remove and Undo of '$title' lost others: ${others - row.toSet()}" }
        } finally {
            if (!undone) runCatching { Jellyfin.restoreResume(Fixture.basics, title) }
        }
    }

    @Test fun quitQuestion_backAndCancelStay_quitLeaves() {
        Nixie.signedIn()
        askQuit()
        check(
            Screen.focusedText().contains(QUIT),
        ) { "the quit question opened with the focus on '${Screen.focusedText()}', not Quit" }
        Remote.back()
        check(waitUntil(SHEET_MS) { !Screen.shows(QUIT_TITLE) }) { "Back did not close the quit question" }
        check(Nixie.inFront() && Home.shown()) { "Back on the quit question left Home: ${Screen.summary()}" }

        askQuit()
        Remote.focusOn(CANCEL, { Remote.left() }, max = 2, keyName = "Left")
        Remote.ok()
        check(waitUntil(SHEET_MS) { !Screen.shows(QUIT_TITLE) }) { "Cancel did not close the quit question" }
        check(Nixie.inFront() && Home.shown()) { "Cancel on the quit question left Home: ${Screen.summary()}" }

        askQuit()
        Remote.focusOn(QUIT, { Remote.right() }, max = 2, keyName = "Right")
        Remote.ok()
        check(waitUntil(Screen.WAIT_MS) { !Nixie.inFront() }) { "Quit did not leave Nixie: ${Screen.summary()}" }
        // Quitting keeps the sign-in.
        Nixie.launch()
        check(
            waitUntil(Screen.WAIT_MS * 2) { Home.shown() },
        ) { "after Quit, Nixie opened ${Screen.summary()} instead of Home" }
    }

    @Test fun removeProfile_backAndCancelKeep_removeTakesOnlyThatProfile() {
        val first = Fixture.basics
        val second = Fixture.other
        Nixie.signedIn(first)
        TopBar.open(TopBar.PROFILE)
        Profiles.waitShown()
        Profiles.otherUser()
        SignIn.withPassword(second)
        SignIn.landHome()
        Themes.wear(Gate.theme)
        Nixie.forgetState()

        openRemoveProfile()
        Remote.back()
        check(
            waitUntil(SHEET_MS) { !Screen.shows(Settings.REMOVE_TITLE) },
        ) { "Back did not close 'Remove profile from this device?'" }
        check(
            Screen.focusedText() == Settings.REMOVE,
        ) { "Back on the remove question left the focus on '${Screen.focusedText()}', not Remove" }
        check(Home.reach()) { "Home did not come back from Settings" }
        check(Settings.signedInName().contains(second.name)) { "Back on the remove question changed the profile" }

        openRemoveProfile()
        check(
            Screen.focusedText().contains(CANCEL),
        ) { "the remove question opened on '${Screen.focusedText()}', not Cancel" }
        Remote.ok()
        check(
            waitUntil(SHEET_MS) { !Screen.shows(Settings.REMOVE_TITLE) },
        ) { "Cancel did not close the remove question" }
        check(Home.reach()) { "Home did not come back from Settings" }
        check(Settings.signedInName().contains(second.name)) { "Cancel on the remove question removed ${second.name}" }

        openRemoveProfile()
        Remote.down()
        check(
            Screen.focusedText().contains(Settings.REMOVE),
        ) { "below Cancel was '${Screen.focusedText()}', not a Remove" }
        Remote.ok()
        Profiles.waitShown()
        val names = Profiles.names()
        check(second.name !in names) { "Remove left ${second.name} in Who's watching?: $names" }
        check(first.name in names) { "removing ${second.name} took ${first.name} off too: $names" }
        Profiles.pick(first.name)
        check(
            waitUntil(Screen.WAIT_MS * 2) { Home.shown() },
        ) { "${first.name} needed more than a pick after another profile went: ${Screen.summary()}" }
    }

    @Test fun takeOverSettingsQuestion_backCloses_andHomeStays() {
        val user = Fixture.basics
        Nixie.clearData()
        Nixie.launch()
        SignIn.addServer(user.server)
        SignIn.withPassword(user)
        val offered = waitUntil(
            TAKE_OVER_MS,
        ) { Screen.shows(SignIn.RESTORE_TITLE) || Home.shown() } && Screen.shows(SignIn.RESTORE_TITLE)
        assumeTrue("the server keeps no settings of ${user.name} from another device yet", offered)
        Remote.back()
        check(
            waitUntil(SHEET_MS) { !Screen.shows(SignIn.RESTORE_TITLE) },
        ) { "Back did not close 'Take over your settings?'" }
        check(
            waitUntil(Screen.WAIT_MS) { Home.shown() },
        ) { "Back on 'Take over your settings?' left no Home: ${Screen.summary()}" }
        Nixie.forgetState()
    }

    /** From Home: the first card of Continue watching, focused; its title. */
    private fun firstContinueWatchingCard(): String {
        TopBar.focus(TopBar.HOME)
        Remote.down()
        // The card says its hint ("Hold OK for options"), its title and its year: the title is the line that is neither.
        val title = Screen.focusedText().split(
            " | ",
        ).firstOrNull { !it.startsWith(HOLD_HINT) && !it.matches(YEAR) }.orEmpty()
        check(title.isNotBlank() && Screen.showsIgnoringCase(CONTINUE_ROW)) {
            "Down from the top bar did not land on a Continue watching card: '${Screen.focusedText()}'"
        }
        return title
    }

    /** The titles in Continue watching that the screen shows (its cards' first lines). */
    private fun rowTitles(): List<String> {
        val header = Screen.boundsOf(CONTINUE_ROW, ignoreCase = true) ?: return emptyList()
        val next = Screen.boundsOf(Home.UP_NEXT, ignoreCase = true)
        return Screen.seen().mapNotNull { seen ->
            val b = seen.bounds
            val text = seen.text
            if (text != null && b.top > header.bottom && (next == null || b.bottom < next.top) && !text.matches(
                    YEAR,
                )
            ) {
                text
            } else {
                null
            }
        }.distinct()
    }

    /** Back from Home until the quit question shows (the first Back may only move the focus up to the bar). */
    private fun askQuit() {
        check(Home.reach()) { "no Home to quit from" }
        repeat(MAX_BACKS) {
            if (Screen.shows(QUIT_TITLE)) return
            Remote.back()
            waitUntil(SHEET_MS) { Screen.shows(QUIT_TITLE) }
        }
        check(Screen.shows(QUIT_TITLE)) { "Back from Home never asked '$QUIT_TITLE': ${Screen.summary()}" }
    }

    private fun openRemoveProfile() {
        Settings.openAccount()
        Settings.controlOf(REMOVE_ROW)
        Remote.ok()
        check(
            waitUntil(SHEET_MS) { Screen.shows(Settings.REMOVE_TITLE) },
        ) { "Remove opened no question: ${Screen.summary()}" }
    }

    private companion object {
        const val CW_SHEET = "Remove from Continue watching?"
        const val CONTINUE_ROW = "Continue watching"
        const val CANCEL = "Cancel"
        const val REMOVE = "Remove"
        const val UNDO = "Select to undo"
        const val QUIT_TITLE = "Quit Nixie?"
        const val QUIT = "Quit"
        const val REMOVE_ROW = "Remove from this device"
        const val SHEET_MS = 5_000L
        const val TAKE_OVER_MS = 60_000L
        const val MAX_BACKS = 3
        const val HOLD_HINT = "Hold OK"
        val YEAR = Regex("^\\d{4}$")
    }
}
