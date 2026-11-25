// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.a

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mbustos27.nixie.tv.gate.kit.Fixture
import io.github.mbustos27.nixie.tv.gate.kit.GateRule
import io.github.mbustos27.nixie.tv.gate.kit.Home
import io.github.mbustos27.nixie.tv.gate.kit.Nixie
import io.github.mbustos27.nixie.tv.gate.kit.Screen
import io.github.mbustos27.nixie.tv.gate.kit.Settings
import io.github.mbustos27.nixie.tv.gate.kit.SignIn
import io.github.mbustos27.nixie.tv.gate.kit.check
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Quick Connect, TV's main way in, stays signed in: after the app is closed and opened again, and after a force-stop.
 * [The desktop's 1.7.x tester bug: a Quick Connect sign-in forgotten at the next start.]
 */
@RunWith(AndroidJUnit4::class)
class QuickConnectGate {
    @get:Rule val gate = GateRule()

    @After fun accountsChanged() = Nixie.forgetState()

    @Test fun quickConnect_staysSignedIn_afterRestartAndForceStop() {
        val user = Fixture.basics
        Nixie.clearData()
        Nixie.launch()
        SignIn.addServer(user.server)
        SignIn.approveShownCode(user)
        SignIn.landHome()
        check(
            Settings.signedInName().contains(user.name),
        ) { "after Quick Connect, Settings did not say signed in as ${user.name}" }

        Nixie.restart()
        afterStart("closed and opened again", user.name)

        Nixie.forceStop()
        Nixie.launch()
        afterStart("a force-stop", user.name)

        // Twice: a start that only reads what the last start saved still finds the session.
        Nixie.forceStop()
        Nixie.launch()
        afterStart("a second force-stop", user.name)
    }

    private fun afterStart(how: String, name: String) {
        val landed = Screen.waitForAny("Home", SignIn.QUICK_CONNECT_WAITING, SignIn.USERNAME, SignIn.WHO, SignIn.MANUAL)
        check(landed == "Home" && runCatching { Home.waitShown() }.isSuccess) {
            "after $how, a Quick Connect sign-in opened ${landed ?: "nothing known"} instead of Home: ${Screen.summary()}"
        }
        val signedIn = Settings.signedInName()
        check(signedIn.contains(name)) { "after $how, Settings said '$signedIn', not $name" }
    }
}
