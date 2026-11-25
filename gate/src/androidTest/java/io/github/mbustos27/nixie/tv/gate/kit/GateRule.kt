// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.kit

import androidx.test.uiautomator.Configurator
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Every gate class's rule: the device ready for the remote, and on a failure a screenshot and the window tree under
 * <Class>.<check> in the run's folder; the next check then starts from fresh app data.
 */
class GateRule : TestWatcher() {
    override fun starting(description: Description) {
        // Nixie's screens animate without end (a pulsing dot, the focus glow): never wait for idle, wait for what shows.
        Configurator.getInstance().waitForIdleTimeout = IDLE_MS
        Configurator.getInstance().waitForSelectorTimeout = 0
        Gate.device.wakeUp()
        // The first read after the test process connects finds an empty tree until Nixie turns its own on.
        if (!warmedUp) {
            Thread.sleep(WARM_UP_MS)
            warmedUp = true
        }
    }

    override fun failed(e: Throwable, description: Description) {
        val name = "${description.testClass.simpleName}.${description.methodName}"
        runCatching { Screen.shot(name) }
        runCatching { Screen.dump(name) }
        Nixie.forgetState()
    }

    private companion object {
        const val IDLE_MS = 0L
        const val WARM_UP_MS = 2_000L
        var warmedUp = false
    }
}
