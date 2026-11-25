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
import io.github.mbustos27.nixie.tv.gate.kit.Settings
import io.github.mbustos27.nixie.tv.gate.kit.SignIn
import io.github.mbustos27.nixie.tv.gate.kit.Themes
import io.github.mbustos27.nixie.tv.gate.kit.TopBar
import io.github.mbustos27.nixie.tv.gate.kit.check
import io.github.mbustos27.nixie.tv.gate.kit.waitUntil
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accounts with the remote: a password sign-in across a restart, a wrong password, Sign out and back in, a second
 * user and switching between both across a force-stop, and a profile's PIN.
 */
@RunWith(AndroidJUnit4::class)
class AccountsGate {
    @get:Rule val gate = GateRule()

    @After fun accountsChanged() = Nixie.forgetState()

    @Test fun password_staysSignedIn_afterRestartAndForceStop() {
        val user = Fixture.basics
        freshSignIn(user)
        Nixie.restart()
        expectHomeAs(user, "closed and opened again after a password sign-in")
        Nixie.forceStop()
        Nixie.launch()
        expectHomeAs(user, "a force-stop after a password sign-in")
    }

    @Test fun wrongPassword_saysSo_andStaysOnTheForm() {
        val user = Fixture.basics
        Nixie.clearData()
        Nixie.launch()
        SignIn.addServer(user.server)
        SignIn.withPassword(GateUser(user.key, user.name, user.server, user.password + "x"))
        check(waitUntil(Screen.WAIT_MS) { Screen.shows(WRONG) }) {
            "a wrong password did not say '$WRONG'; the screen showed ${Screen.summary()}"
        }
        check(SignIn.formShown() && !Home.shown()) { "after a wrong password the form was gone: ${Screen.summary()}" }
        // No loop and no sign-in behind it: a moment later it is still the form.
        Thread.sleep(SETTLE_AFTER_WRONG_MS)
        check(SignIn.formShown() && !Home.shown()) { "a wrong password left the form later: ${Screen.summary()}" }
    }

    @Test fun signOut_leadsToProfiles_andSigningBackInWorks() {
        val user = Fixture.basics
        freshSignIn(user)
        Settings.openAccount()
        Remote.focusOn(Settings.SIGN_OUT, { Remote.down() }, max = 40, keyName = "Down")
        Remote.ok()
        Profiles.waitShown()
        check(
            user.name in Profiles.names(),
        ) { "after Sign out, Who's watching? did not list ${user.name}: ${Profiles.names()}" }
        // A signed-out profile asks for the sign-in again; it does not open Home on the old session.
        Profiles.pick(user.name)
        val signIn = waitUntil(
            Screen.WAIT_MS,
        ) { Screen.shows(SignIn.QUICK_CONNECT_WAITING) || SignIn.formShown() || Home.shown() }
        check(signIn && !Home.shown()) { "the signed-out ${user.name} opened no sign-in: ${Screen.summary()}" }
        SignIn.withPassword(user)
        SignIn.landHome()
        check(Settings.signedInName().contains(user.name)) { "signed back in, Settings did not say ${user.name}" }
        Nixie.forceStop()
        Nixie.launch()
        expectHomeAs(user, "a force-stop after signing back in")
    }

    @Test fun secondUser_bothStaySignedIn_andSwitchAcrossAForceStop() {
        val first = Fixture.basics
        val second = Fixture.other
        freshSignIn(first)
        TopBar.open(TopBar.PROFILE)
        Profiles.waitShown()
        Profiles.otherUser()
        // The second user by Quick Connect, TV's usual way.
        SignIn.approveShownCode(second)
        SignIn.landHome()
        check(Settings.signedInName().contains(second.name)) { "after Other user, Settings did not say ${second.name}" }

        TopBar.open(TopBar.PROFILE)
        Profiles.waitShown()
        val names = Profiles.names()
        check(
            first.name in names && second.name in names,
        ) { "Who's watching? listed $names, not both ${first.name} and ${second.name}" }
        Profiles.pick(first.name)
        expectHomeAs(first, "switching to ${first.name}", fromStart = false)

        // Both sessions survive a force-stop: the start asks who's watching, and each opens without a sign-in.
        Nixie.forceStop()
        Nixie.launch()
        Profiles.waitShown()
        Profiles.pick(second.name)
        expectHomeAs(second, "a force-stop, then picking ${second.name}", fromStart = false)
        TopBar.open(TopBar.PROFILE)
        Profiles.waitShown()
        Profiles.pick(first.name)
        expectHomeAs(first, "switching back to ${first.name}", fromStart = false)
    }

    @Test fun pinOnProfileSwitch_asksForThePin_andBackClosesIt() {
        val user = Fixture.basics
        freshSignIn(user)
        Settings.openAccount()
        Settings.controlOf(PIN_ON_SWITCH)
        Remote.ok()
        // No PIN yet: switching the option on asks for a new one, twice.
        Screen.waitFor(NEW_PIN, what = "the new PIN's sheet")
        Profiles.typePin(PIN)
        Screen.waitFor(CONFIRM_PIN, what = "the PIN's confirmation")
        Profiles.typePin(PIN)
        check(
            waitUntil(Screen.WAIT_MS) { !Screen.shows(CONFIRM_PIN) },
        ) { "the PIN's sheet stayed after the confirmation" }
        check(Home.reach()) { "Home did not come back from Settings" }

        TopBar.open(TopBar.PROFILE)
        Profiles.waitShown()
        Profiles.pick(user.name)
        Profiles.waitPin(user.name)
        Profiles.typePin(WRONG_PIN)
        check(
            waitUntil(Screen.WAIT_MS) { Screen.shows(WRONG_PIN_TEXT) },
        ) { "a wrong PIN did not say '$WRONG_PIN_TEXT': ${Screen.summary()}" }
        check(!Home.shown()) { "a wrong PIN opened Home" }
        // Back with nothing typed closes the sheet and stays on Who's watching?.
        Remote.back()
        check(
            waitUntil(Screen.WAIT_MS) { !Screen.shows("PIN for ${user.name}") },
        ) { "Back did not close the PIN sheet" }
        check(Profiles.shown()) { "Back on the PIN sheet left Who's watching?: ${Screen.summary()}" }
        Profiles.pick(user.name)
        Profiles.waitPin(user.name)
        Profiles.typePin(PIN)
        expectHomeAs(user, "the right PIN", fromStart = false)
    }

    private fun freshSignIn(user: GateUser) {
        Nixie.clearData()
        Nixie.launch()
        SignIn.addServer(user.server)
        SignIn.withPassword(user)
        SignIn.landHome()
        Themes.wear(Gate.theme)
    }

    private fun expectHomeAs(
        user: GateUser,
        how: String,
        fromStart: Boolean = true,
    ) {
        val shown = waitUntil(
            HOME_MS,
        ) { Home.shown() || Profiles.shown() || Screen.shows(SignIn.QUICK_CONNECT_WAITING) || SignIn.formShown() }
        check(shown && Home.shown()) {
            "after $how, ${user.name} ${if (fromStart) "started on" else "got"} ${Screen.summary()} instead of Home"
        }
        val name = Settings.signedInName()
        check(name.contains(user.name)) { "after $how, Settings said '$name', not ${user.name}" }
    }

    private companion object {
        const val WRONG = "Wrong username or password"
        const val PIN_ON_SWITCH = "PIN on profile switch"
        const val NEW_PIN = "Set a new PIN"
        const val CONFIRM_PIN = "Confirm the new PIN"
        const val PIN = "1234"
        const val WRONG_PIN = "0000"
        const val WRONG_PIN_TEXT = "Wrong PIN"
        const val HOME_MS = 45_000L
        const val SETTLE_AFTER_WRONG_MS = 4_000L
    }
}
