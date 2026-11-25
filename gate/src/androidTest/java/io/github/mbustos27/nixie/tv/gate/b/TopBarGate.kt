// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.b

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mbustos27.nixie.tv.gate.kit.GateRule
import io.github.mbustos27.nixie.tv.gate.kit.Home
import io.github.mbustos27.nixie.tv.gate.kit.Remote
import io.github.mbustos27.nixie.tv.gate.kit.Screen
import io.github.mbustos27.nixie.tv.gate.kit.TopBar
import io.github.mbustos27.nixie.tv.gate.kit.check
import io.github.mbustos27.nixie.tv.gate.kit.waitUntil
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The top bar lights the section the page belongs to, read from the screen's pixels with the focus on the page (not on
 * the bar): each entry's own page, a library opened from the Libraries page, and pages come back to with Back.
 * [Desktop 1.7.x, a tester: the top bar lit "Home" on a season page.]
 */
@RunWith(AndroidJUnit4::class)
class TopBarGate {
    @get:Rule val gate = GateRule()

    @Test fun eachEntry_lightsItsOwnPage() {
        Pages.home()
        val wrong = mutableListOf<String>()
        for ((entry, heading) in PAGES) {
            Bar.open(entry)
            if (heading != null) Pages.waitFromBar(heading)
            intoPage()
            lit(entry, "the $entry page")?.let { wrong += it }
        }
        check(wrong.isEmpty()) { wrong.joinToString("; ") }
    }

    @Test fun pagesReachedFromPages_lightTheirSection() {
        Pages.home()
        val wrong = mutableListOf<String>()

        // A library opened from the Libraries page belongs to its library, not to Libraries.
        Pages.libraries()
        Pages.firstInRow()
        Rows.okOn("Movies")
        Pages.waitOn(Pages.MOVIES)
        intoPage()
        lit(Pages.MOVIES_ENTRY, "Movies opened from the Libraries page")?.let { wrong += it }

        // Back from a show's page (a season) to the Shows library.
        Pages.longShow()
        Remote.back()
        Pages.waitOn(Pages.SHOWS)
        intoPage()
        lit(Pages.SHOWS_ENTRY, "the Shows library after Back from a season")?.let { wrong += it }

        // Back from a collection to the Collections page.
        Pages.collections()
        val card = focus()
        Remote.ok()
        waitUntil(PAGE_MS) { !Pages.onPage(Pages.COLLECTIONS) }
        Remote.back()
        Pages.waitOn(Pages.COLLECTIONS)
        intoPage()
        lit(TopBar.COLLECTIONS, "the Collections page after Back from $card")?.let { wrong += it }

        // Back from a film opened on Home.
        check(Bar.home()) { "Home did not come back through the top bar; ${Screen.summary()}" }
        intoPage()
        Remote.ok()
        waitUntil(PAGE_MS) { !Home.shown() }
        Remote.back()
        waitUntil(PAGE_MS) { Home.shown() }
        intoPage()
        lit(TopBar.HOME, "Home after Back from a film")?.let { wrong += it }

        check(wrong.isEmpty()) { wrong.joinToString("; ") }
    }

    /** The focus off the bar and on the page (Down once or twice), so only the lit entry is marked. */
    private fun intoPage() {
        repeat(2) {
            if (!Bar.focused()) return
            Keys.go { Keys.down() }
        }
    }

    /** Null when [expected] is the one lit entry; else what the viewer sees, in a few words. */
    private fun lit(expected: String, where: String): String? {
        Thread.sleep(SETTLE_MS)
        val reading = TopBarLook.read()
        return when (reading.lit) {
            expected -> null
            null -> "on $where the top bar lit no entry ($reading)"
            else -> "on $where the top bar lit '${reading.lit}', not '$expected'"
        }
    }

    private companion object {
        const val PAGE_MS = 20_000L
        const val SETTLE_MS = 600L

        /** Each entry and its page's heading (null: the page has none to wait for). */
        val PAGES = listOf(
            TopBar.HOME to null,
            TopBar.LIBRARIES to Pages.LIBRARIES,
            Pages.MOVIES_ENTRY to Pages.MOVIES,
            Pages.SHOWS_ENTRY to Pages.SHOWS,
            TopBar.COLLECTIONS to Pages.COLLECTIONS,
            TopBar.FAVORITES to null,
            TopBar.SEARCH to null,
            TopBar.SETTINGS to null,
        )
    }
}
