// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.b

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mbustos27.nixie.tv.gate.kit.Fixture
import io.github.mbustos27.nixie.tv.gate.kit.GateRule
import io.github.mbustos27.nixie.tv.gate.kit.Remote
import io.github.mbustos27.nixie.tv.gate.kit.Screen
import io.github.mbustos27.nixie.tv.gate.kit.check
import io.github.mbustos27.nixie.tv.gate.kit.waitUntil
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Collections page's order button: its words say the direction, OK flips A to Z and Z to A, and Z to A is kept after
 * Back from a collection and on a new visit. Revert check: 10101 has no order button (added in tv-1.0-r1, 85d9e1c0).
 * [The owner, 2026-11-23: "we should be able to reverse the direction of the sort in collections".]
 */
@RunWith(AndroidJUnit4::class)
class CollectionsOrderGate {
    @get:Rule val gate = GateRule()

    /** Leaves the page A to Z for the next check, whatever happened. */
    @After fun backToAToZ() {
        runCatching {
            if (Pages.onPage(Pages.COLLECTIONS) && Screen.shows(CollectionsOrder.Z_TO_A)) CollectionsOrder.flipTo(CollectionsOrder.A_TO_Z)
        }
    }

    @Test fun orderButton_flipsAToZ_andZToA() {
        Pages.home()
        val last = ServerFacts.lastCollection(Fixture.basics)
        Pages.collections()
        CollectionsOrder.flipTo(CollectionsOrder.A_TO_Z)
        val firstAToZ = firstCard()
        check(!firstAToZ.text.contains(last)) { "A to Z starts on the last collection $firstAToZ" }

        CollectionsOrder.flipTo(CollectionsOrder.Z_TO_A)
        val firstZToA = firstCard()
        check(firstZToA.text.contains(last)) { "Z to A starts on $firstZToA, not the last collection by name '$last'" }

        CollectionsOrder.flipTo(CollectionsOrder.A_TO_Z)
        val again = firstCard()
        check(again.text == firstAToZ.text) { "A to Z again starts on $again, not $firstAToZ as before" }
    }

    @Test fun zToA_isKept_afterBackFromACollection_andOnANewVisit() {
        Pages.home()
        val last = ServerFacts.lastCollection(Fixture.basics)
        Pages.collections()
        CollectionsOrder.flipTo(CollectionsOrder.Z_TO_A)
        val first = firstCard()
        check(first.text.contains(last)) { "Z to A starts on $first, not '$last'" }

        Rows.okOn(last)
        check(waitUntil(Screen.WAIT_MS) { !Pages.onPage(Pages.COLLECTIONS) }) { "OK on the collection '$last' did not open it" }
        Remote.back()
        Pages.waitOn(Pages.COLLECTIONS)
        check(CollectionsOrder.says() == CollectionsOrder.Z_TO_A) {
            "after Back from the collection '$last' the order button says '${CollectionsOrder.says()}', not '${CollectionsOrder.Z_TO_A}'"
        }
        check(focus().text.contains(last)) { "after Back from the collection '$last' the focus is on ${focus()}" }

        check(Bar.home()) { "Home did not come back through the top bar; ${Screen.summary()}" }
        Pages.collections()
        check(CollectionsOrder.says() == CollectionsOrder.Z_TO_A) {
            "a new visit to Collections says '${CollectionsOrder.says()}', not '${CollectionsOrder.Z_TO_A}' as left"
        }
        CollectionsOrder.focusButton()
        val firstAgain = firstCard()
        check(firstAgain.text.contains(last)) { "a new visit to Collections (Z to A) starts on $firstAgain, not '$last'" }
    }

    /** From the order button: Down into the grid, Left to the first card; its focus. */
    private fun firstCard(): Focus {
        Keys.go { Keys.down() }
        Pages.firstInRow()
        return focus()
    }
}
