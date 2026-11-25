// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.kit

/**
 * Settings with the remote: the categories down the left (focusing one opens it), the cards on the right (Right from a
 * category lands on the first card's first control), Back from the cards to the category, from there to the top bar.
 */
object Settings {
    const val APPEARANCE = "Appearance"
    const val ACCOUNT = "Account"
    const val SYSTEM = "System"

    /** The theme row's first pill: Right from Appearance lands on it. */
    const val THEME = "Drive-in"
    const val SIGNED_IN_AS = "Signed in as"
    const val SIGN_OUT = "Sign out"
    const val REMOVE = "Remove"
    const val REMOVE_TITLE = "Remove profile from this device?"
    private val categories = listOf(APPEARANCE, "Home", "Playback", "Audio", "Subtitles", ACCOUNT, SYSTEM)
    private const val MAX_ROWS = 40
    private const val NEAR = 60

    /** Opens Settings from the top bar; the focus is on a category. */
    fun open() {
        TopBar.open(TopBar.SETTINGS)
        Screen.waitFor(ACCOUNT, what = "Settings")
        check(
            Screen.waitFocus { it in categories },
        ) { "Settings opened without the focus on a category: '${Screen.focusedText()}'" }
    }

    /** Focuses the category [name], which opens it. */
    fun category(name: String) {
        val now = categories.indexOf(Screen.focusedText())
        val target = categories.indexOf(name)
        check(
            now >= 0 && target >= 0,
        ) { "Settings: not on a category ('${Screen.focusedText()}') or no category $name" }
        if (target > now) Remote.down(target - now) else Remote.up(now - target)
        check(
            Screen.waitFocus { it == name },
        ) { "Settings: the focus went to '${Screen.focusedText()}', not the category $name" }
    }

    /** From a category: Right to its first control, then Down until a control saying [text] has the focus. */
    fun row(text: String) {
        Remote.right()
        Remote.focusOn(text, { Remote.down() }, max = MAX_ROWS, keyName = "Down")
    }

    /**
     * In the cards: Down until the control beside the card labelled [label] has the focus (a toggle or a button on
     * the card's right, level with its label and line), for controls without a text of their own.
     */
    fun controlOf(label: String) {
        Remote.focusUntil("the control of '$label'", MAX_ROWS) {
            val at = Screen.boundsOf(label)
            val f = Screen.focusedBounds()
            val beside =
                at != null && f != null && f.left > at.right && f.centerY() in (at.top - NEAR)..(at.bottom + NEAR)
            if (beside) null else Remote::down
        }
    }

    /** From Home: Settings > Account, its cards' first control focused. */
    fun openAccount() {
        open()
        category(ACCOUNT)
        Remote.right()
    }

    /** On a row of pills: Right or Left to the pill [option], then OK. */
    fun choose(option: String) {
        val toEnd = runCatching { Remote.focusOn(option, { Remote.right() }, max = 6, keyName = "Right") }
        if (toEnd.isFailure) Remote.focusOn(option, { Remote.left() }, max = 12, keyName = "Left")
        Remote.ok()
    }

    /** The focused control still says [text] (a pill keeps the focus after it was chosen). */
    fun focusedRowShows(text: String): Boolean = Screen.focusedText().contains(text)

    /** The name on Settings > Account > "Signed in as": opens Settings and comes back to Home. */
    fun signedInName(): String {
        open()
        category(ACCOUNT)
        val texts = Screen.texts()
        val at = texts.indexOfFirst { it.contains(SIGNED_IN_AS) }
        check(at >= 0) { "Settings > Account showed no 'Signed in as'; texts ${texts.take(12)}" }
        // The info card's texts: the label, its line below, then the value (merged into one node or one each).
        val card = texts.drop(at).take(3).joinToString(" | ")
        check(Home.reach()) { "Home did not come back from Settings" }
        return card
    }
}
