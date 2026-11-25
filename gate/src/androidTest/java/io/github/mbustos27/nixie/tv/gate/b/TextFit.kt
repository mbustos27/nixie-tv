// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.b

import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Does a text fit: a title or name is drawn whole, or ends in an ellipsis the viewer sees, never cut off at its last
 * line with the rest missing and no "…". Read from the app's text layout through the accessibility service (each
 * character's place on screen): a text cut at its line limit without an ellipsis lays its remaining characters over
 * its last line again, from the line's start (the layout has no line for them), so they run backwards along that
 * line. An ellipsized text hides its remaining characters behind the "…" with no width (with a theme's negative
 * letter spacing, Editorial's titles, each lies a hair before the one ahead), and a card half out of view only loses
 * characters at the screen's edge: neither counts.
 */
object TextFit {
    private const val KEY = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY

    /** A text the viewer sees only part of, with no ellipsis: [shown] is what is drawn. */
    data class Cut(val text: String, val shown: String, val bounds: Rect) {
        override fun toString() = "'$text' shows '$shown'"
    }

    /** A text on screen and where it is drawn. */
    data class Shown(val text: String, val bounds: Rect)

    /** The texts on screen inside [area] (all when null), leaving out those inside [skip] (a focused card's marquee). */
    fun texts(area: Rect? = null, skip: Rect? = null): List<Shown> = nodes(area, skip).map { (n, b) -> Shown(n.text.toString(), b) }

    /** The texts inside [area] that are cut without an ellipsis. */
    fun cuts(area: Rect? = null, skip: Rect? = null): List<Cut> = nodes(area, skip).mapNotNull { (node, bounds) ->
        val text = node.text.toString()
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, text.length)
        }
        if (!node.refreshWithExtraData(KEY, args)) return@mapNotNull null
        val rects: Array<RectF?> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            node.extras.getParcelableArray(KEY, RectF::class.java)
        } else {
            @Suppress("DEPRECATION")
            node.extras.getParcelableArray(KEY)?.map { it as? RectF }?.toTypedArray()
        } ?: return@mapNotNull null
        val cut = overflowStart(text, rects) ?: return@mapNotNull null
        Cut(text, text.substring(0, cut).trim(), bounds)
    }

    /**
     * The first character laid over a line already filled (back along the same line, from its start), or null when every
     * drawn character sits after the one before it, on its line or a later one.
     */
    private fun overflowStart(text: String, rects: Array<RectF?>): Int? {
        var lineTop = Float.NaN
        var lineRight = Float.NEGATIVE_INFINITY
        for (i in text.indices) {
            val r = rects.getOrNull(i) ?: continue
            if (text[i].isWhitespace()) continue
            // Hidden behind an ellipsis: no width (or a negative letter spacing's sliver less than none).
            if (r.right - r.left < DRAWN_PX) continue
            if (lineTop.isNaN() || kotlin.math.abs(r.top - lineTop) > SAME_LINE_PX) {
                lineTop = r.top
                lineRight = r.right
                continue
            }
            if (r.left < lineRight - SAME_LINE_PX) return i
            lineRight = maxOf(lineRight, r.right)
        }
        return null
    }

    private const val SAME_LINE_PX = 1.5f

    /** The narrowest a drawn character is (a thin "i" or "l" in a small text is several pixels). */
    private const val DRAWN_PX = 1f

    private fun nodes(area: Rect?, skip: Rect?): List<Pair<AccessibilityNodeInfo, Rect>> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) automation.clearCache()
        val root = automation.rootInActiveWindow ?: return emptyList()
        val found = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        fun visit(node: AccessibilityNodeInfo) {
            if (!node.text.isNullOrBlank() && node.isVisibleToUser && KEY in node.availableExtraData) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val inArea = area == null || Rect.intersects(area, bounds)
                val skipped = skip != null && skip.contains(bounds.centerX(), bounds.centerY())
                if (inArea && !skipped) found += node to bounds
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        visit(root)
        return found
    }
}
