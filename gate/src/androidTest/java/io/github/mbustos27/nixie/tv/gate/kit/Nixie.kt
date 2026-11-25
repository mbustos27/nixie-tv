// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.kit

/**
 * The app under test as a whole: start, stop, fresh data, and "signed in and on Home" for checks that browse.
 */
object Nixie {
    const val ACTIVITY = "io.github.glacier_jellyfin.androidtv.MainActivity"
    private const val FRONT_MS = 3_000L
    private const val START_MS = 12_000L
    private const val STAYS_MS = 2_500L
    private const val LAUNCH_TRIES = 4

    /** Who the kit last signed in during this run, on fresh data; null when unknown (a check changed accounts). */
    var signedInAs: GateUser? = null
        private set

    /** The next [signedIn] starts from fresh data: call it after a check that signs in, out, or switches users. */
    fun forgetState() {
        signedInAs = null
    }

    fun inFront(): Boolean = Gate.device.currentPackageName == Gate.pkg

    /** Fails at once, naming what is in front instead, unless Nixie is in the foreground ([what]: the key about to go). */
    fun requireInFront(what: String) {
        if (!waitUntil(FRONT_MS) { inFront() }) {
            fail("Nixie is not in the foreground before $what (${Gate.device.currentPackageName} is)")
        }
    }

    /** The app's data wiped, as after a new install. */
    fun clearData() {
        Gate.shell("pm clear ${Gate.pkg}")
        signedInAs = null
        Themes.forgetState()
    }

    /** Opens Nixie the way the TV's launcher does; fails unless it comes to the front. */
    fun launch() {
        // The emulator's Google TV launcher sometimes brings itself back to the front just after a force-stop (its
        // own start, a moment after Nixie's): Nixie is opened again then, as a viewer would, at most twice more.
        repeat(LAUNCH_TRIES) { attempt ->
            Gate.shell(
                "am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER " +
                    "-f 0x10200000 -n ${Gate.pkg}/$ACTIVITY",
            )
            if (waitUntil(START_MS) { inFront() } && !waitUntil(STAYS_MS) { !inFront() }) return
            android.util.Log.i(
                "NixieGate",
                "start ${attempt + 1}: ${Gate.device.currentPackageName} in front, not Nixie; opening it again",
            )
        }
        fail("Nixie did not stay in front after $LAUNCH_TRIES starts (${Gate.device.currentPackageName} is in front)")
    }

    /** Stopped as by Settings > Apps > Force stop. */
    fun forceStop() {
        Gate.shell("am force-stop ${Gate.pkg}")
        waitUntil(FRONT_MS) { pid().isEmpty() }
    }

    /**
     * Closed and opened again: Home on the remote, then opened from the launcher. The process is also ended in the
     * background when the system lets it (as a TV does when it needs the memory); Nixie keeps a service now and then,
     * and then this is a reopen of the running app (a force-stop is the cold start: [forceStop]).
     */
    fun restart() {
        Gate.device.pressHome()
        waitUntil(FRONT_MS) { !inFront() }
        Gate.shell("am kill ${Gate.pkg}")
        val cold = waitUntil(FRONT_MS) { pid().isEmpty() }
        android.util.Log.i(
            "NixieGate",
            "restart: ${if (cold) "process ended" else "process kept by the system: a reopen"}",
        )
        launch()
    }

    /** The app's process id, "" when it does not run. */
    fun pid(): String = Gate.shell("pidof ${Gate.pkg}").trim()

    /**
     * Home, signed in as [user] (on its server): from fresh data with a password sign-in, unless the kit already
     * signed that user in during this run and Home comes back with Back. Wears the run's theme.
     */
    fun signedIn(user: GateUser = Fixture.basics) {
        if (signedInAs == user && Home.reach()) return
        clearData()
        launch()
        SignIn.addServer(user.server)
        SignIn.withPassword(user)
        SignIn.landHome()
        signedInAs = user
        Themes.wear(Gate.theme)
    }
}
