// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.kit

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** One element on screen: its text and description (either may be null) and where it is. */
data class Seen(
    val text: String?,
    val description: String?,
    val bounds: Rect,
) {
    /** Its text, else its description. */
    val label: String? get() = text ?: description

    fun says(part: String, ignoreCase: Boolean = false): Boolean =
        text?.contains(part, ignoreCase) == true || description?.contains(part, ignoreCase) == true
}

/**
 * What the user sees, read from the accessibility tree of Nixie's windows: the element with the input focus and the
 * texts on screen. Compose merges a card's texts into the card's node, so texts are matched as substrings.
 *
 * Read straight from UiAutomation (the input focus in one call, Nixie's windows walked once): UiAutomator's own
 * searches walk every window and took 2 to 4 s per read on the emulator's Home.
 */
object Screen {
    const val WAIT_MS = 15_000L
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    /**
     * The focused element: the smallest focused node of Nixie's windows (the window and the Compose view hold focus
     * around it). Not UiAutomation's findFocus: it kept a Continue watching card after the focus went up to the bar.
     */
    private fun focusedNode(): AccessibilityNodeInfo? {
        // Quick: the input focus UiAutomation knows, refreshed from the app (it can be a stale node), when it is
        // still focused and not the whole screen; else the walk.
        val quick = runCatching { automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (quick != null && quick.packageName == Gate.pkg && runCatching { quick.refresh() }.getOrDefault(false) &&
            quick.isFocused && !wholeScreen(quick)
        ) {
            return quick
        }
        val focused = mutableListOf<AccessibilityNodeInfo>()
        walkAll { node -> if (node.isFocused) focused += node }
        return focused.minByOrNull { node ->
            Rect().also {
                node.getBoundsInScreen(
                    it,
                )
            }.let { it.width().toLong() * it.height() }
        }
    }

    private fun wholeScreen(node: AccessibilityNodeInfo): Boolean {
        val b = Rect().also { node.getBoundsInScreen(it) }
        return b.width() >= Gate.device.displayWidth && b.height() >= Gate.device.displayHeight
    }

    /** Visits every node of Nixie's windows, freshly read. */
    private fun walkAll(visit: (AccessibilityNodeInfo) -> Unit) {
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            visit(node)
            for (i in 0 until node.childCount) walk(runCatching { child(node, i) }.getOrNull(), depth + 1)
        }
        fresh()
        roots().forEach { walk(it, 0) }
    }

    /**
     * Drops UiAutomation's cached nodes, so a read sees the screen now: without it a read seconds after a new screen
     * still found the old one's focus and texts (Compose's change events reach the cache late).
     */
    private fun fresh() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) runCatching { automation.clearCache() }
    }

    /** The focused element's texts and descriptions, its children's after its own, joined with " | "; "" if none. */
    fun focusedText(): String = focusedNode()?.let { textsOf(it).joinToString(" | ") }.orEmpty()

    fun focusedBounds(): Rect? = focusedNode()?.let { node -> Rect().also { node.getBoundsInScreen(it) } }

    /** The focused element's texts and place, to tell whether a key moved the focus. */
    fun focusSignature(): String = focusedNode()?.let { node ->
        "${textsOf(node).joinToString(" | ")}@${Rect().also { node.getBoundsInScreen(it) }.toShortString()}"
    }.orEmpty()

    /** Every element of Nixie's windows that has a text or a description, in tree order. */
    fun seen(): List<Seen> {
        val out = mutableListOf<Seen>()
        walkAll { node ->
            if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) {
                out += Seen(
                    node.text?.toString(),
                    node.contentDescription?.toString(),
                    Rect().also { node.getBoundsInScreen(it) },
                )
            }
        }
        return out
    }

    /** A child, fetched with its subtree in as few calls as the system allows (Android 13 on). */
    private fun child(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            node.getChild(index, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
        } else {
            node.getChild(index)
        }

    /** Nixie's windows (the screen, a dialog over it), top first; the active window when the list is not there. */
    private fun roots(): List<AccessibilityNodeInfo> {
        val windows = runCatching { automation.windows }.getOrNull().orEmpty()
            .sortedByDescending { it.layer }
            .mapNotNull { window ->
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        window.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
                    } else {
                        window.root
                    }
                }.getOrNull()
            }
            .filter { it.packageName == Gate.pkg }
        return windows.ifEmpty { listOfNotNull(runCatching { automation.rootInActiveWindow }.getOrNull()) }
    }

    /** Every text and description on screen, in tree order. */
    fun texts(): List<String> = seen().flatMap { listOfNotNull(it.text, it.description) }

    /** True when some text or description on screen contains [text]. */
    fun shows(text: String): Boolean = seen().any { it.says(text) }

    /** As [shows], whatever the case (themes write row titles in capitals). */
    fun showsIgnoringCase(text: String): Boolean = seen().any { it.says(text, ignoreCase = true) }

    /** Where the first element whose text or description contains [text] sits, or null. */
    fun boundsOf(text: String, ignoreCase: Boolean = false): Rect? = seen().firstOrNull {
        it.says(
            text,
            ignoreCase,
        )
    }?.bounds

    /** Waits until [text] shows; fails with what the screen showed instead. */
    fun waitFor(
        text: String,
        timeoutMs: Long = WAIT_MS,
        what: String = "'$text'",
    ) {
        if (!waitUntil(timeoutMs) { shows(text) }) {
            fail("$what did not show within ${timeoutMs / 1000} s; the screen showed ${summary()}")
        }
    }

    /** Waits until any of [texts] shows; returns the first one found, or null. */
    fun waitForAny(vararg texts: String, timeoutMs: Long = WAIT_MS): String? {
        var found: String? = null
        waitUntil(timeoutMs) {
            val now = seen()
            found = texts.firstOrNull { t -> now.any { it.says(t) } }
            found != null
        }
        return found
    }

    /** Waits until [text] is gone; fails if it stays. */
    fun waitGone(text: String, timeoutMs: Long = WAIT_MS) {
        if (!waitUntil(timeoutMs) { !shows(text) }) fail("'$text' still showed after ${timeoutMs / 1000} s")
    }

    /** Waits until the focused element's texts satisfy [predicate]; true when they did. */
    fun waitFocus(timeoutMs: Long = WAIT_MS, predicate: (String) -> Boolean): Boolean =
        waitUntil(timeoutMs) { predicate(focusedText()) }

    /** A screenshot into the run's folder (pulled by the runner), as <name>.png. */
    fun shot(name: String): File {
        val file = File(Gate.out, "${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.png")
        Gate.device.takeScreenshot(file)
        return file
    }

    /** The window tree into the run's folder, as <name>.xml. */
    fun dump(name: String): File {
        val file = File(Gate.out, "${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.xml")
        runCatching { Gate.device.dumpWindowHierarchy(file) }
        return file
    }

    /** A short line of what is on screen, for failure messages: the focus and the first texts. */
    fun summary(): String {
        val focus = focusedText().ifEmpty { "nothing" }
        val texts = texts().map { it.replace('\n', ' ').take(MAX_TEXT) }.distinct().take(MAX_TEXTS)
        return "focus on '$focus', texts ${texts.joinToString(", ", "[", "]")}"
    }

    private fun textsOf(node: AccessibilityNodeInfo, depth: Int = 0): List<String> {
        if (depth > MAX_DEPTH) return emptyList()
        val own = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).filter { it.isNotBlank() }
        val children = (0 until node.childCount).flatMap { i ->
            runCatching { child(node, i) }.getOrNull()?.let { textsOf(it, depth + 1) }.orEmpty()
        }
        return (own + children).distinct()
    }

    private const val MAX_TEXT = 40
    private const val MAX_TEXTS = 12
    private const val MAX_DEPTH = 60
}
