// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.kit

/** Adding a server and signing in, with the remote, as a viewer does on a new TV. */
object SignIn {
    const val MANUAL = "Enter server address manually"
    const val ADD_SERVER = "Add server"
    const val CONNECT = "Connect"
    const val QUICK_CONNECT_WAITING = "Waiting for approval"
    const val USE_PASSWORD = "Sign in with password"
    const val NEW_CODE = "New code"
    const val SIGN_IN = "Sign in"
    const val USERNAME = "Username"
    const val PASSWORD = "Password"
    const val WHO = "Who’s watching?"
    const val OTHER_USER = "Other user"
    private const val CONNECT_MS = 30_000L
    private const val CODE_MS = 20_000L
    private const val FIELD_SWITCH_MS = 800L
    private const val CODE_LENGTH = 6
    private const val LIST_READY_MS = 1_500L
    private const val OPEN_MS = 5_000L
    private const val FORM_KEYS = 4
    private const val LABEL_SLACK = 12

    const val RESTORE_TITLE = "Take over your settings?"
    const val NOT_NOW = "Not now"
    private const val HOME_MS = 60_000L

    /**
     * After a sign-in: Home, past the one-time "Take over your settings?" (offered when the server keeps this user's
     * settings from another device), answered "Not now".
     */
    fun landHome() {
        val shown = waitUntil(HOME_MS) { Screen.shows(RESTORE_TITLE) || Home.shown() }
        check(shown) { "no Home after the sign-in within ${HOME_MS / 1000} s; the screen showed ${Screen.summary()}" }
        if (Screen.shows(RESTORE_TITLE)) {
            Remote.focusOn(NOT_NOW, { Remote.down() }, max = 8, keyName = "Down")
            Remote.ok()
            Screen.waitGone(RESTORE_TITLE)
        }
        Home.waitShown()
    }

    /** From the server list (Nixie's first screen): "Enter server address manually", the address, Connect. */
    fun addServer(server: GateServer) {
        Screen.waitFor(MANUAL, CONNECT_MS)
        Remote.focusOn(MANUAL, { Remote.down() }, keyName = "Down")
        // The list takes no keys until it knows the saved servers: a press right after the start can go unheard.
        Thread.sleep(LIST_READY_MS)
        Remote.ok()
        // Again only if nothing moved: the focus still on the button and no "Add server".
        if (!waitUntil(OPEN_MS) { Screen.shows(ADD_SERVER) || !Screen.focusedText().contains(MANUAL) }) Remote.ok()
        Screen.waitFor(ADD_SERVER)
        typeIntoFocusedField(server.typed)
        // The keyboard's done key closes it and moves the focus to Connect.
        Screen.waitFocus { it.contains(CONNECT) }.let { onConnect ->
            if (!onConnect) Remote.focusOn(CONNECT, { Remote.down() }, max = 3, keyName = "Down")
        }
        Remote.ok()
    }

    /** OK on the focused field opens the system keyboard; [text] is typed and the keyboard's done key closes it. */
    fun typeIntoFocusedField(text: String) {
        Remote.ok()
        check(
            waitUntil(Screen.WAIT_MS) { keyboardShown() },
        ) { "OK on the field '${Screen.focusedText()}' opened no keyboard" }
        Remote.type(text)
        Remote.enter()
    }

    fun keyboardShown(): Boolean = Gate.shell("dumpsys input_method").contains("mInputShown=true")

    /** On the Quick Connect screen: the code the TV shows (the theme's tubes, filmstrip or figures read it out). */
    fun quickConnectCode(timeoutMs: Long = CODE_MS): String {
        var code: String? = null
        val found = waitUntil(timeoutMs) {
            code = readCode()
            code != null
        }
        check(
            found,
        ) { "Quick Connect showed no code within ${timeoutMs / 1000} s; the screen showed ${Screen.summary()}" }
        return code!!
    }

    private val spokenCode = Regex("^\\d( \\d){5}$")
    private val digit = Regex("^\\d$")

    private fun readCode(): String? {
        val seen = Screen.seen()
        // Drive-in, Cinematic and Editorial speak the code digit by digit; Glacier's boxes are one text per digit.
        seen.firstOrNull {
            it.description?.matches(
                spokenCode,
            ) == true
        }?.let { return it.description!!.replace(" ", "") }
        val digits = seen.filter { it.text?.matches(digit) == true }.sortedBy { it.bounds.left }.mapNotNull { it.text }
        return digits.joinToString("").takeIf { it.length == CODE_LENGTH }
    }

    /** Quick Connect from its screen: reads the code, approves it as [user] the way a phone would. */
    fun approveShownCode(user: GateUser) {
        Screen.waitFor(QUICK_CONNECT_WAITING, CONNECT_MS, "the Quick Connect screen")
        Jellyfin.approveQuickConnect(user, quickConnectCode())
    }

    /**
     * After the server: Nixie opens Quick Connect first when the server offers it; "Sign in with password" leads to
     * the form. Fills the user name and password with the system keyboard; the password's done key signs in.
     */
    fun withPassword(user: GateUser) {
        var on: String? = null
        waitUntil(CONNECT_MS) {
            val seen = Screen.seen()
            on = when {
                seen.any { it.says(QUICK_CONNECT_WAITING) } -> QUICK_CONNECT_WAITING
                formShown(seen) -> USERNAME
                else -> null
            }
            on != null
        }
        when (on) {
            QUICK_CONNECT_WAITING -> {
                Remote.focusOn(USE_PASSWORD, { Remote.right() }, max = 3, keyName = "Right")
                Remote.ok()
                check(waitUntil(Screen.WAIT_MS) { formShown(Screen.seen()) }) {
                    "'$USE_PASSWORD' opened no sign-in form: ${Screen.summary()}"
                }
            }

            USERNAME -> Unit

            else -> fail("neither Quick Connect nor the sign-in form showed; the screen showed ${Screen.summary()}")
        }
        fillPassword(user)
    }

    /** The sign-in form: its user name and password labels (in the theme's case). */
    fun formShown(seen: List<Seen> = Screen.seen()): Boolean = seen.any {
        it.text.equals(
            USERNAME,
            ignoreCase = true,
        )
    } && seen.any { it.text.equals(PASSWORD, ignoreCase = true) }

    /** On the sign-in form: the user name's field (under its label, above the password's), then both fields. */
    fun fillPassword(user: GateUser) {
        Remote.focusUntil("the user name's field", FORM_KEYS) {
            val seen = Screen.seen()
            val name = seen.firstOrNull { it.text.equals(USERNAME, ignoreCase = true) }?.bounds
            val password = seen.firstOrNull { it.text.equals(PASSWORD, ignoreCase = true) }?.bounds
            val f = Screen.focusedBounds()
            if (name != null && password != null && f != null && f.top >= name.top &&
                f.bottom <= password.top + LABEL_SLACK
            ) {
                null
            } else {
                Remote::up
            }
        }
        Remote.ok()
        check(waitUntil(Screen.WAIT_MS) { keyboardShown() }) { "OK on the user name opened no keyboard" }
        // A profile picked from Who's watching? fills the name in already.
        Remote.clearField()
        Remote.type(user.name)
        Remote.enter()
        // Done on the user name moves on to the password and opens the keyboard again.
        Thread.sleep(FIELD_SWITCH_MS)
        check(
            waitUntil(Screen.WAIT_MS) { keyboardShown() },
        ) { "done on the user name opened no keyboard for the password" }
        Remote.clearField()
        Remote.type(user.password)
        Remote.enter()
    }
}
