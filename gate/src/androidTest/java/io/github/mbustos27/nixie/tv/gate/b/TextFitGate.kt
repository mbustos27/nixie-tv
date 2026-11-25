// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.b

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mbustos27.nixie.tv.gate.kit.Gate
import io.github.mbustos27.nixie.tv.gate.kit.GateRule
import io.github.mbustos27.nixie.tv.gate.kit.Remote
import io.github.mbustos27.nixie.tv.gate.kit.Screen
import io.github.mbustos27.nixie.tv.gate.kit.check
import io.github.mbustos27.nixie.tv.gate.kit.waitUntil
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Text fits: long titles and long names are drawn whole or end in an ellipsis the viewer sees, and names never run into
 * each other. The fixture's show has a 95-character title, long episode titles and 20 cast with long names and roles.
 * [Desktop 1.7.x, a tester: cast names running together.]
 */
@RunWith(AndroidJUnit4::class)
class TextFitGate {
    @get:Rule val gate = GateRule()

    @Test fun castNames_showWholeOrEndInAnEllipsis() {
        Pages.home()
        Pages.longShow()
        Pages.season(1)
        Pages.cast()
        val cuts = linkedMapOf<String, TextFit.Cut>()
        val walk = Rows.walk(max = Shows.CAST_COUNT + 5) { Keys.right().also { castBand()?.let { band -> collect(cuts, band) } } }
        castBand()?.let { collect(cuts, it) }
        check(walk.visited.size >= Shows.CAST_COUNT / 2) { "the cast row could not be walked (${walk.visited.size} names)" }
        check(cuts.isEmpty()) {
            "the cast row cuts ${cuts.size} names or roles with no ellipsis: ${cuts.values.take(MAX_LISTED).joinToString("; ")}"
        }
    }

    @Test fun castNames_neverRunTogether() {
        Pages.home()
        Pages.longShow()
        Pages.season(1)
        Pages.cast()
        val band = castBand() ?: Rect(0, 0, Gate.device.displayWidth, Gate.device.displayHeight)
        val names = TextFit.texts(band).filter { it.text.length > 2 }
        val overlaps = names.flatMapIndexed { i, a ->
            names.drop(i + 1).filter { b -> Rect.intersects(a.bounds, b.bounds) }.map { b -> "'${a.text}' over '${b.text}'" }
        }
        check(names.size >= MIN_NAMES) { "the cast row showed ${names.size} texts; expected the fixture's long names" }
        check(overlaps.isEmpty()) { "cast names run into each other: ${overlaps.take(MAX_LISTED).joinToString("; ")}" }
    }

    @Test fun longTitles_showWholeOrEndInAnEllipsis() {
        Pages.home()
        val cuts = mutableListOf<String>()
        Pages.library(Pages.SHOWS_ENTRY, Pages.SHOWS)
        cuts += onScreen("the Shows library")
        Pages.openLongShow()
        cuts += onScreen("the show's page")
        Pages.season(1)
        Rows.focusOn("Episode 3 of season 1", max = 4, keyName = "Right") { Keys.right() }
        cuts += onScreen("season 1's episodes")
        Rows.okOn("Episode 3 of season 1")
        check(waitUntil(PAGE_MS) { Screen.shows("Play S1 E3") || Screen.shows("Resume S1 E3") }) { "OK on E3 did not open its page" }
        cuts += onScreen("the episode's page")
        Remote.back()
        check(cuts.isEmpty()) { "long titles and names cut with no ellipsis: ${cuts.take(MAX_LISTED).joinToString("; ")}" }
    }

    /** The cut texts on screen, leaving out the focused card (its title scrolls as a marquee), named by [where]. */
    private fun onScreen(where: String): List<String> {
        val skip = focus().bounds
        return TextFit.cuts(skip = skip).map { "$where: $it" }
    }

    /** The band of the screen the cast row is in: the focused cast card's height across the screen. */
    private fun castBand(): Rect? = focus().bounds?.let { Rect(0, it.top, Gate.device.displayWidth, it.bottom) }

    private fun collect(into: MutableMap<String, TextFit.Cut>, band: Rect) {
        TextFit.cuts(band).forEach { into.putIfAbsent(it.text, it) }
    }

    private companion object {
        const val PAGE_MS = 20_000L
        const val MAX_LISTED = 4
        const val MIN_NAMES = 8
    }
}
