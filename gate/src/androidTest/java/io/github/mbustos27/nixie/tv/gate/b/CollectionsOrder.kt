// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.b

import io.github.mbustos27.nixie.tv.gate.kit.Screen
import io.github.mbustos27.nixie.tv.gate.kit.check
import io.github.mbustos27.nixie.tv.gate.kit.fail

/** The Collections page's order button (tv-1.0-r1): its words say the list's direction. */
object CollectionsOrder {
    const val A_TO_Z = "A to Z"
    const val Z_TO_A = "Z to A"
    private const val MAX_UP = 80

    /** A toolbar pill is short; a card is a poster. */
    private const val TOOLBAR_MAX_HEIGHT = 120

    fun shown(): Boolean = Screen.shows(A_TO_Z) || Screen.shows(Z_TO_A)

    /** The direction the button says, or null when the page has none. */
    fun says(): String? = when {
        Screen.shows(Z_TO_A) -> Z_TO_A
        Screen.shows(A_TO_Z) -> A_TO_Z
        else -> null
    }

    /** From anywhere on the Collections page: Up to the toolbar, then onto the order button. */
    fun focusButton() {
        check(shown()) { "the Collections page shows no order button (no '$A_TO_Z' or '$Z_TO_A'); ${Screen.summary()}" }
        var presses = 0
        var at = focus()
        while ((at.bounds?.height() ?: 0) > TOOLBAR_MAX_HEIGHT || at.text.isEmpty()) {
            if (presses == MAX_UP) fail("Up x$MAX_UP never reached the Collections page's toolbar; focus on $at")
            Keys.up()
            at = Rows.moved(at) ?: at
            presses++
        }
        val label = says() ?: A_TO_Z
        val right = runCatching { Rows.focusOn(label, max = 6, keyName = "Right") { Keys.right() } }
        if (right.isFailure) Rows.focusOn(label, max = 10, keyName = "Left") { Keys.left() }
    }

    /** Flips the list until the button says [label]; ends on the button. */
    fun flipTo(label: String) {
        focusButton()
        if (focus().text.contains(label)) return
        Rows.okOn(if (label == A_TO_Z) Z_TO_A else A_TO_Z)
        check(io.github.mbustos27.nixie.tv.gate.kit.waitUntil(Screen.WAIT_MS) { focus().text.contains(label) }) {
            "OK on the order button did not turn it to '$label'; it says ${focus()}"
        }
    }
}
