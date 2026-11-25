// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.b

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mbustos27.nixie.tv.gate.kit.GateRule
import io.github.mbustos27.nixie.tv.gate.kit.Screen
import io.github.mbustos27.nixie.tv.gate.kit.check
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Right on the last card of a short row keeps the focus there (or takes it to the A–Z rail beside the grid); it never
 * jumps up a row or to the top bar. Revert checks: on 10101 the Collections page's short last row and the Libraries
 * page's last card sent the focus up (the quick basics check's Minor, fixed in tv-1.0-r1, fa06fcd7).
 */
@RunWith(AndroidJUnit4::class)
class ShortRowGate {
    @get:Rule val gate = GateRule()

    @Test fun collections_rightOnTheShortLastRowsEnd_staysOrGoesToTheRail() {
        Pages.home()
        Pages.collections()
        if (Screen.shows(CollectionsOrder.Z_TO_A)) {
            CollectionsOrder.flipTo(CollectionsOrder.A_TO_Z)
            Keys.go { Keys.down() }
        }
        // Down from the top, no A–Z jump (a jump starts the rows at its letter): 492 collections leave 2 in the last row.
        val count = Screen.texts().firstNotNullOfOrNull { Regex("^(\\d+) collections$").find(it)?.groupValues?.get(1)?.toInt() }
        check(count == null || count % FULL_ROW != 0) { "the Collections page has $count collections: no short last row to check" }
        Rows.toLastRow(max = MAX_ROWS)
        Pages.firstInRow()
        val walk = Rows.walk(max = FULL_ROW + 1)
        check(walk.visited.size < FULL_ROW) { "the Collections page's last row was full (${walk.visited.size} cards): no short last row to check" }
        val to = walk.leftTo
        check(to == null || to.onRail) {
            "Right on the last card ${walk.last} of the Collections page's short last row moved the focus up to $to"
        }
    }

    @Test fun libraries_rightOnTheLastCard_stays() {
        Pages.home()
        Pages.libraries()
        Pages.firstInRow()
        val walk = Rows.walk(max = MAX_LIBRARIES)
        check(walk.leftTo == null) { "Right on the Libraries page's last card ${walk.last} moved the focus to ${walk.leftTo}" }
    }

    @Test fun shortLibrary_rightOnTheLastCard_staysOrGoesToTheRail() {
        Pages.home()
        Pages.library(Pages.SHOWS_ENTRY, Pages.SHOWS)
        Pages.firstInRow()
        val walk = Rows.walk(max = FULL_ROW)
        val to = walk.leftTo
        check(to == null || to.onRail) { "Right on the Shows library's last card ${walk.last} (a row of ${walk.visited.size}) moved the focus to $to" }
    }

    private companion object {
        /** The library grid's posters per row at 1080p. */
        const val FULL_ROW = 7
        const val MAX_LIBRARIES = 12
        const val MAX_ROWS = 120
    }
}
