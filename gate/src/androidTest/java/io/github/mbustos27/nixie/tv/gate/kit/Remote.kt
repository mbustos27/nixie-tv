// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.kit

import android.util.Log
import android.view.KeyEvent

/**
 * The remote: the D-pad, OK, Back and Menu, as a TV remote sends them. Every press first checks that Nixie is in the
 * foreground, so a key never lands in another app (the launcher, the system's dialogs).
 */
object Remote {
    /** The pause after each key, for the app to answer it (animations are off on the gate's emulator). */
    var paceMs: Long = 150

    fun up(times: Int = 1) = repeat(times) { press(KeyEvent.KEYCODE_DPAD_UP, "Up") }

    fun down(times: Int = 1) = repeat(times) { press(KeyEvent.KEYCODE_DPAD_DOWN, "Down") }

    fun left(times: Int = 1) = repeat(times) { press(KeyEvent.KEYCODE_DPAD_LEFT, "Left") }

    fun right(times: Int = 1) = repeat(times) { press(KeyEvent.KEYCODE_DPAD_RIGHT, "Right") }

    fun ok() = press(KeyEvent.KEYCODE_DPAD_CENTER, "OK")

    fun back() = press(KeyEvent.KEYCODE_BACK, "Back")

    fun menu() = press(KeyEvent.KEYCODE_MENU, "Menu")

    /** The keyboard's Enter (the system keyboard's done key while it is open). */
    fun enter() = press(KeyEvent.KEYCODE_ENTER, "Enter")

    /** Holding OK (a long press), as for a card's options. */
    fun longOk() {
        Nixie.requireInFront("holding OK")
        Gate.shell("input keyevent --longpress ${KeyEvent.KEYCODE_DPAD_CENTER}")
        Thread.sleep(paceMs)
        trace("hold OK")
    }

    /** Empties the field the open system keyboard writes to (End, then Delete a few dozen times). */
    fun clearField() {
        Nixie.requireInFront("clearing a field")
        Gate.shell("input keyevent ${KeyEvent.KEYCODE_MOVE_END}")
        Gate.shell("input keyevent " + List(CLEAR_KEYS) { KeyEvent.KEYCODE_DEL }.joinToString(" "))
        Thread.sleep(paceMs)
    }

    /** Types [text] into the open system keyboard. Never pass a secret into a failure message. */
    fun type(text: String) {
        Nixie.requireInFront("typing")
        // No shell between: the command is split at spaces and run as is, so nothing is quoted; input text reads %s
        // as a space.
        Gate.shell("input text ${text.replace(" ", "%s")}")
        Thread.sleep(paceMs)
    }

    /**
     * Presses [key] until the focused element's texts contain [text], at most [max] times (none if it is focused
     * already); fails with where the focus went.
     */
    fun focusOn(
        text: String,
        key: () -> Unit,
        max: Int = 25,
        keyName: String = "the key",
    ) {
        val start = Screen.focusedText()
        var presses = 0
        while (!Screen.focusedText().contains(text)) {
            if (presses == max) {
                fail("$keyName x$max from '$start' never reached '$text'; focus ended on '${Screen.focusedText()}'")
            }
            key()
            presses++
        }
    }

    /** How long a key waits for the focus to move before it counts as a key that moved nothing (a row's end). */
    var moveWaitMs: Long = 1_200

    /**
     * Presses the key, then waits until the focus moved (its texts or place changed) or [moveWaitMs] passed, and lets
     * the screen settle: the next read sees what this key did, not the one before.
     */

    /**
     * Moves the focus until [decide] returns null (arrived); otherwise it names the key to press next. Fails after
     * [max] presses, naming [what] and where the focus ended.
     */
    fun focusUntil(
        what: String,
        max: Int,
        decide: () -> (() -> Unit)?,
    ) {
        repeat(max + 1) {
            val next = decide() ?: return
            if (it == max) return@repeat
            next()
        }
        fail("$max keys never reached $what; focus ended on '${Screen.focusedText()}'")
    }

    /** A key that moves no focus (the PIN wheel's digits): pressed, then a short pause. */
    fun tap(code: Int, name: String) {
        Nixie.requireInFront(name)
        Gate.device.pressKeyCode(code)
        Thread.sleep(TAP_MS)
        trace(name)
    }

    fun press(code: Int, name: String) {
        Nixie.requireInFront(name)
        val before = Screen.focusedText()
        Gate.device.pressKeyCode(code)
        // The texts, not the place: a row or the top bar scrolling under the same element is no move.
        waitUntil(moveWaitMs, MOVE_STEP_MS) { Screen.focusedText() != before }
        settle()
        trace(name)
    }

    /**
     * Until the focus has stayed put for [stableMs] (the app and the accessibility tree lag the key on a slow
     * emulator: a read too soon sees the key before, and a row of keys then overshoots).
     */
    private fun settle() {
        var last = Screen.focusSignature()
        var since = System.currentTimeMillis()
        waitUntil(SETTLE_MS, STEP_MS) {
            val now = Screen.focusSignature()
            if (now != last) {
                last = now
                since = System.currentTimeMillis()
            }
            System.currentTimeMillis() - since >= stableMs
        }
    }

    /** How long the focus must stay put after a key before the next read counts. */
    var stableMs: Long = 500

    /** Each key and where the focus went, in logcat under NixieGate (the runner keeps it with a failed run). */
    private fun trace(name: String) {
        if (Log.isLoggable(TAG, Log.INFO)) Log.i(TAG, "$name -> ${Screen.focusedText().take(TRACE_MAX)}")
    }

    private const val TAG = "NixieGate"
    private const val TRACE_MAX = 80
    private const val MOVE_STEP_MS = 60L
    private const val SETTLE_MS = 3_000L
    private const val STEP_MS = 120L
    private const val TAP_MS = 300L
    private const val CLEAR_KEYS = 48
}
