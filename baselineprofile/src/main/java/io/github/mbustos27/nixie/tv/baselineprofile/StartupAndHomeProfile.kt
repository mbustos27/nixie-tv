// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The plan's start-up and Home journeys for the baseline profile: a cold start, signing in to the TV test server by
 * Quick Connect the first time, then Home: down through the rows and across one, as a viewer browses with the remote.
 * Runs on an emulator with the TV test server on the PC (testing/fakeserver, port [TEST_SERVER_PORT]) and
 * testing/emulator/Approve-QuickConnect.ps1 approving its code there; never against anyone's real server.
 */
@RunWith(AndroidJUnit4::class)
class StartupAndHomeProfile {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndHome() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        if (device.wait(Until.hasObject(By.text("Enter server address manually")), SHORT_MS) == true) signIn()
        device.wait(Until.hasObject(By.text("Home")), LONG_MS)
        device.waitForIdle()
        repeat(ROWS) {
            device.pressDPadDown()
            device.waitForIdle()
        }
        repeat(CARDS) {
            device.pressDPadRight()
            device.waitForIdle()
        }
        repeat(ROWS) {
            device.pressDPadUp()
            device.waitForIdle()
        }
    }

    private fun MacrobenchmarkScope.signIn() {
        device.pressDPadCenter()
        device.wait(Until.hasObject(By.text("Add server")), LONG_MS)
        device.pressDPadCenter()
        Thread.sleep(STEP_MS)
        device.executeShellCommand("input text $EMULATOR_HOST:$TEST_SERVER_PORT")
        Thread.sleep(STEP_MS)
        device.pressEnter()
        Thread.sleep(STEP_MS)
        device.pressDPadCenter()
        // The code is approved on the PC by testing/emulator/Approve-QuickConnect.ps1, as a signed-in phone would.
        device.wait(Until.hasObject(By.text("Home")), LONG_MS * 2)
    }

    private companion object {
        const val PACKAGE = "io.github.mbustos27.nixie.tv"
        const val EMULATOR_HOST = "10.0.2.2"
        const val TEST_SERVER_PORT = 18412
        const val ROWS = 3
        const val CARDS = 4
        const val STEP_MS = 1_500L
        const val SHORT_MS = 8_000L
        const val LONG_MS = 20_000L
    }
}
