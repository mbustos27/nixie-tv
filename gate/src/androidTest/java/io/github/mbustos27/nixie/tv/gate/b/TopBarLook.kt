// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.b

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mbustos27.nixie.tv.gate.kit.Gate
import io.github.mbustos27.nixie.tv.gate.kit.Screen
import io.github.mbustos27.nixie.tv.gate.kit.Seen
import io.github.mbustos27.nixie.tv.gate.kit.fail
import kotlin.math.sqrt

/**
 * Which top bar entry looks lit, read from the screen's pixels, as the viewer sees it: every theme marks the page's
 * entry with a line under its label (Glacier's own look tints the pill instead), and the other entries stay plain. The
 * entry whose lower band differs most from the others' is the lit one; none stands out when nothing is lit.
 */
object TopBarLook {
    /** The bar's entries by their words (Search and Settings by their descriptions). */
    val ENTRIES = listOf("Search", "Home", "Libraries", Pages.MOVIES_ENTRY, Pages.SHOWS_ENTRY, "Collections", "Favorites", "Settings")

    private const val TOP_SHARE = 0.12
    private const val DESIGN_HEIGHT = 54f
    private const val STANDS_OUT = 18.0
    private const val CLEAR_LEAD = 2.0
    private const val WORDS_PAD = 26
    private const val DESIGN_ROWS = 1080f
    private const val ICON_PAD = 16

    data class Reading(val lit: String?, val scores: Map<String, Double>) {
        override fun toString() = scores.entries.joinToString(", ") { (k, v) -> "$k ${"%.0f".format(v)}" }
    }

    /** The lit entry (null if none stands out) and every entry's score, for messages. */
    fun read(): Reading {
        val pills = pills()
        if (pills.size < 3) fail("the top bar showed ${pills.size} entries (${pills.keys}); not enough to compare")
        val shot: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: fail("no screenshot of the top bar")
        val bands = pills.mapValues { (_, r) -> lowerBand(shot, r) }
        val median = DoubleArray(3) { c -> bands.values.map { it[c] }.sorted()[bands.size / 2] }
        val scores = bands.mapValues { (_, v) -> sqrt((0..2).sumOf { c -> (v[c] - median[c]) * (v[c] - median[c]) }) }
        val ranked = scores.entries.sortedByDescending { it.value }
        val top = ranked[0]
        val second = ranked[1].value
        val lit = top.key.takeIf { top.value >= STANDS_OUT && top.value >= second * CLEAR_LEAD }
        return Reading(lit, scores)
    }

    /** Each entry's pill on screen, from one read of the screen. */
    private fun pills(): Map<String, Rect> {
        val seen = Screen.seen()
        return ENTRIES.mapNotNull { entry -> pillIn(seen, entry)?.let { entry to it } }.toMap()
    }

    /** [entry]'s pill on the top bar, or null when the bar does not show it. */
    fun pill(entry: String): Rect? = pillIn(Screen.seen(), entry)

    /**
     * The pill around an entry's words (26 dp each side) or icon (16 dp), 54 dp tall, centred on them: Glacier's NavItem.
     * The words' node is what the screen reports; the pill is drawn around it.
     */
    private fun pillIn(seen: List<Seen>, entry: String): Rect? {
        val top = Gate.device.displayHeight * TOP_SHARE
        val label = seen.firstOrNull { (it.text == entry || it.description == entry) && it.bounds.bottom < top } ?: return null
        val dp = scale()
        val side = ((if (label.text == entry) WORDS_PAD else ICON_PAD) * dp).toInt()
        val half = (DESIGN_HEIGHT / 2 * dp).toInt()
        val b = label.bounds
        return Rect(b.left - side, b.centerY() - half, b.right + side, b.centerY() + half)
    }

    /** Nixie lays the TV out in 1080 rows of its own units whatever the system's density: its unit on this screen. */
    private fun scale(): Float = Gate.device.displayHeight / DESIGN_ROWS

    /** The mean colour of the band where the page's line sits: 5 to 8 dp above the pill's foot, inside its rounded ends. */
    private fun lowerBand(shot: Bitmap, pill: Rect): DoubleArray {
        val scale = scale()
        val inset = (14 * scale).toInt()
        val y0 = pill.bottom - (9 * scale).toInt()
        val y1 = pill.bottom - (4 * scale).toInt()
        val sum = DoubleArray(3)
        var n = 0
        for (y in y0 until y1) {
            for (x in pill.left + inset until pill.right - inset) {
                if (x !in 0 until shot.width || y !in 0 until shot.height) continue
                val p = shot.getPixel(x, y)
                sum[0] += Color.red(p).toDouble()
                sum[1] += Color.green(p).toDouble()
                sum[2] += Color.blue(p).toDouble()
                n++
            }
        }
        return if (n == 0) sum else DoubleArray(3) { sum[it] / n }
    }
}
