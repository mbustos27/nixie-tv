// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.kit

import android.view.KeyEvent

/**
 * "Who's watching?": a tile per profile (only the picture or initial takes the focus; the name is under it),
 * "Other user", and the server's line at the bottom (it opens the server list).
 */
object Profiles {
    private const val MAX_ACROSS = 8
    private const val SHEET_MS = 5_000L

    fun shown(): Boolean = Screen.shows(SignIn.WHO)

    fun waitShown() = Screen.waitFor(SignIn.WHO, 30_000, "Who's watching?")

    /** The profile names shown, left to right. */
    fun names(): List<String> {
        val other = Screen.boundsOf(SignIn.OTHER_USER) ?: return emptyList()
        val tilesBottom = other.top
        val title = Screen.boundsOf(SignIn.WHO)?.bottom ?: 0
        return Screen.seen().mapNotNull { seen ->
            val b = seen.bounds
            val text = seen.text
            if (text != null && b.top > title && b.bottom < tilesBottom &&
                text.length > 1
            ) {
                text to b.centerX()
            } else {
                null
            }
        }.distinct().sortedBy { it.second }.map { it.first }
    }

    /** Puts the focus on the tile above [name] and presses OK. */
    fun pick(name: String) {
        val nameAt = Screen.boundsOf(name) ?: fail("Who's watching? shows no profile $name; it shows ${names()}")
        Remote.focusUntil("the tile of $name", MAX_ACROSS) {
            val f = Screen.focusedBounds()
            if (f != null && nameAt.centerX() in f.left..f.right && f.bottom <= nameAt.bottom) {
                null
            } else if (f == null || f.centerX() < nameAt.centerX()) {
                Remote::right
            } else {
                Remote::left
            }
        }
        Remote.ok()
    }

    /** "Other user": sign in another account on this server. */
    fun otherUser() {
        Remote.focusOn(SignIn.OTHER_USER, { Remote.down() }, max = 3, keyName = "Down")
        Remote.ok()
    }

    /** The server's line at the bottom: the server list, to add or pick another server. */
    fun serverLine(server: GateServer) {
        Remote.focusOn(server.typed, { Remote.down() }, max = 4, keyName = "Down")
        Remote.ok()
    }

    /** Waits for the PIN sheet of [name]. */
    fun waitPin(name: String) {
        check(waitUntil(SHEET_MS) { Screen.shows("PIN for $name") }) {
            "picking $name opened no PIN sheet; the screen showed ${Screen.summary()}"
        }
    }

    /** Types a PIN on the wheel: OK 0, Up 1, Right 2, Down 3, Left 4 (the gate's PINs use only these). */
    fun typePin(pin: String) {
        pin.forEach { digit ->
            when (digit) {
                '0' -> Remote.tap(KeyEvent.KEYCODE_DPAD_CENTER, "OK")
                '1' -> Remote.tap(KeyEvent.KEYCODE_DPAD_UP, "Up")
                '2' -> Remote.tap(KeyEvent.KEYCODE_DPAD_RIGHT, "Right")
                '3' -> Remote.tap(KeyEvent.KEYCODE_DPAD_DOWN, "Down")
                '4' -> Remote.tap(KeyEvent.KEYCODE_DPAD_LEFT, "Left")
                else -> fail("the gate types PINs of 0 to 4 only, not $digit")
            }
        }
    }
}
