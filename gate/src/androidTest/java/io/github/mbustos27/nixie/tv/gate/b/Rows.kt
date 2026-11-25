// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.b

import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mbustos27.nixie.tv.gate.kit.Gate
import io.github.mbustos27.nixie.tv.gate.kit.Nixie
import io.github.mbustos27.nixie.tv.gate.kit.Remote
import io.github.mbustos27.nixie.tv.gate.kit.Screen
import io.github.mbustos27.nixie.tv.gate.kit.check
import io.github.mbustos27.nixie.tv.gate.kit.fail
import io.github.mbustos27.nixie.tv.gate.kit.waitUntil
import kotlin.math.abs

/** Where the focus is, as the user sees it: the focused card's texts and its place on screen. */
data class Focus(val text: String, val bounds: Rect?) {
    /** The first text of the card, for messages: its title or name, cut short. */
    val name: String get() = text.split(" | ").firstOrNull { it.length > 2 }.orEmpty().ifEmpty { text }.take(NAME_MAX)

    /** A letter of the A–Z rail (one character each). */
    val onRail: Boolean get() = text.length == 1 || text == "#"

    override fun toString() = "'$name'"

    private companion object {
        const val NAME_MAX = 60
    }
}

/**
 * The focused element as the user sees it: the smallest node marked focused in the window's tree (Compose's own
 * "input focus" answer runs one key late), with its texts and its children's, as [Screen.focusedText] joins them.
 * Read in one walk of the tree from the app itself, faster than the kit's selector query on a busy emulator.
 */
fun focus(): Focus {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) automation.clearCache()
    val root = automation.rootInActiveWindow ?: return Focus(Screen.focusedText(), Screen.focusedBounds())
    var best: AccessibilityNodeInfo? = null
    var bestArea = Long.MAX_VALUE
    val r = Rect()
    fun visit(n: AccessibilityNodeInfo) {
        if (n.isFocused) {
            n.getBoundsInScreen(r)
            val area = r.width().toLong() * r.height()
            if (area in 1 until bestArea) {
                best = n
                bestArea = area
            }
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let(::visit)
    }
    visit(root)
    val node = best ?: return Focus("", null)
    val texts = mutableListOf<String>()
    fun collect(n: AccessibilityNodeInfo) {
        listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).filter { it.isNotBlank() }.forEach { texts += it }
        for (i in 0 until n.childCount) n.getChild(i)?.let(::collect)
    }
    collect(node)
    return Focus(texts.distinct().joinToString(" | "), Rect().also(node::getBoundsInScreen))
}


/**
 * The remote's arrows for walks: one key each, after the kit's check that Nixie is in front, and no waiting of their
 * own: [Rows.moved] waits for the focus (the kit's Remote reads the focus several times a key, slow on a busy emulator).
 * [go] presses one and waits for the focus to move.
 */
object Keys {
    fun up() = press(KeyEvent.KEYCODE_DPAD_UP, "Up")

    fun down() = press(KeyEvent.KEYCODE_DPAD_DOWN, "Down")

    fun left() = press(KeyEvent.KEYCODE_DPAD_LEFT, "Left")

    fun right() = press(KeyEvent.KEYCODE_DPAD_RIGHT, "Right")

    /** Back, with no waiting of its own (BackGate times what follows it). */
    fun back() = press(KeyEvent.KEYCODE_BACK, "Back")

    /** Presses [key] [times] times, each time until the focus moved (or stayed, at an end). */
    fun go(times: Int = 1, key: () -> Unit) = repeat(times) {
        val before = focus()
        key()
        Rows.moved(before)
    }

    private fun press(code: Int, name: String) {
        Nixie.requireInFront(name)
        Gate.device.pressKeyCode(code)
    }
}

/** A walk along a row with the remote: every card the focus was on, in order, and where it went if it left the row. */
data class Walk(val visited: List<Focus>, val leftTo: Focus?) {
    val first: Focus get() = visited.first()
    val last: Focus get() = visited.last()
}

/**
 * Rows and grids walked with the remote, as a viewer does: a key at a time, reading the focus after each press.
 * "Seen" means the card is fully on screen, not cut by the edge.
 */
object Rows {
    private const val MOVE_WAIT_MS = 2_500L

    /** A grid's next rows may still be coming from the server when Down reaches them. */
    private const val GRID_WAIT_MS = 6_000L
    private const val RAIL_SHARE = 0.9
    private const val POLL_MS = 100L
    private const val FULL = 0.85

    /**
     * Presses [key] (Right by default) until the focus stops moving or leaves the row it started in (up a row, to the
     * top bar, to the A–Z rail), at most [max] times; fails if it is still moving after [max] presses.
     */
    fun walk(max: Int, keyName: String = "Right", waitMs: Long = MOVE_WAIT_MS, key: () -> Unit = { Keys.right() }): Walk {
        val start = focus()
        check(start.text.isNotEmpty()) { "nothing had the focus before walking the row with $keyName" }
        val visited = mutableListOf(start)
        repeat(max) {
            val before = visited.last()
            key()
            val after = moved(before, waitMs) ?: return Walk(visited, null)
            if (!sameRow(start, after)) return Walk(visited, after)
            visited += after
        }
        fail("$keyName x$max from ${start} never reached the row's end; focus on ${visited.last()}")
    }

    /**
     * Presses Down until the focus stops going down (the grid's last row); returns where it stopped. One Down each: a
     * Down that does nothing is the end (in Movies it was the app dropping a Down at the end of an A–Z jump's page,
     * its next page still coming: fixed in the app, HeldDown.kt).
     */
    fun toLastRow(max: Int): Focus = untilStill(max, "Down", waitMs = GRID_WAIT_MS) { Keys.down() }

    /** Presses [key] until the focus stays where it is; returns where it stopped; fails if it still moves after [max]. */
    fun untilStill(max: Int, keyName: String, waitMs: Long = MOVE_WAIT_MS, key: () -> Unit): Focus {
        var at = focus()
        repeat(max) {
            key()
            at = moved(at, waitMs) ?: return at
        }
        fail("$keyName x$max never stopped moving; focus on $at")
    }

    /**
     * In a grid with the A–Z rail: Right to the rail, Down to its last letter, OK (the jump), Left into the grid, Down
     * to the last row and Right along it. Returns that last walk.
     */
    fun gridEndByRail(): Walk {
        // The rail comes once the grid's first screen is done (later on a busy emulator): wait for it first.
        val width = Gate.device.displayWidth
        check(waitUntil(GRID_WAIT_MS * 4) { Screen.seen().any { it.text == "Z" && it.bounds.left > width * RAIL_SHARE } }) {
            "the grid showed no A–Z rail; ${Screen.summary()}"
        }
        val toRail = walk(max = 12)
        check(toRail.leftTo?.onRail == true) {
            "Right along the grid's row never reached the A–Z rail; it stopped on ${toRail.leftTo ?: toRail.last}"
        }
        val letter = untilStill(max = 40, keyName = "Down") { Keys.down() }
        check(letter.onRail) { "Down along the A–Z rail left it for $letter" }
        Remote.ok()
        Keys.go { Keys.left() }
        val inGrid = focus()
        check(!inGrid.onRail && inGrid.text.isNotEmpty()) { "Left from the rail's '${letter.text}' did not go into the grid; focus on $inGrid" }
        toLastRow(max = 120)
        walk(max = 12, keyName = "Left", waitMs = GRID_WAIT_MS) { Keys.left() }
        return walk(max = 12, waitMs = GRID_WAIT_MS)
    }

    /**
     * Where the focus went after a key, or null when it stayed on [before]: a slow answer (a long scroll) looks like an
     * end, so an unchanged focus is read again for [MOVE_WAIT_MS] before it counts as staying.
     */
    fun moved(before: Focus, waitMs: Long = MOVE_WAIT_MS): Focus? {
        val pressed = System.currentTimeMillis()
        while (true) {
            // Only a read that began [MOVE_WAIT_MS] after the key counts for "it stayed": a slow read can be stale.
            val began = System.currentTimeMillis()
            val now = focus()
            if (now.text != before.text && now.text.isNotEmpty()) return now
            if (began - pressed >= waitMs) return null
            Thread.sleep(POLL_MS)
        }
    }

    /** [b] is a card in the same row as [a]: about the same height on screen, and not the A–Z rail. */
    fun sameRow(a: Focus, b: Focus): Boolean {
        val ab = a.bounds ?: return false
        val bb = b.bounds ?: return false
        return !b.onRail && abs(ab.centerY() - bb.centerY()) < ab.height() / 2
    }

    /** The card is fully on screen: as wide as the row's other focused cards and inside the screen's edges. */
    fun fullySeen(card: Focus, walk: Walk, screenWidth: Int): Boolean {
        val b = card.bounds ?: return false
        val widths = walk.visited.mapNotNull { it.bounds?.width() }.sorted()
        val typical = widths[widths.size / 2]
        return b.left >= 0 && b.right <= screenWidth && b.width() >= typical * FULL
    }

    /** Presses [key] until the focused element's texts contain [text], at most [max] times; fails with where it ended. */
    fun focusOn(text: String, max: Int, keyName: String, key: () -> Unit) {
        var at = focus()
        var presses = 0
        while (!at.text.contains(text)) {
            if (presses == max) fail("$keyName x$max never reached '$text'; the focus ended on $at")
            key()
            at = moved(at) ?: at
            presses++
        }
    }

    /** Moves the focus onto the card whose texts contain [text] with [key], then checks it is there before OK. */
    fun okOn(text: String) {
        val at = focus()
        check(at.text.contains(text)) { "about to press OK on '$text' but the focus is on $at" }
        Remote.ok()
    }
}
