// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Nixie: tidy collection and library names, the cases desktop Nixie 1.6 checks. */
class TidyNamesTest {

    @Test
    fun `decorations go`() {
        mapOf(
            "[HUB] Marvel" to "Marvel",
            "[HUB] Trending Movies" to "Trending Movies",
            "[hub] Kids Movies" to "Kids Movies",
            "{HUB} Marvel" to "Marvel",
            "[HUB][4K] Marvel" to "Marvel",
            "[HUB] [4K] Marvel" to "Marvel",
            "[HUB] - Marvel" to "Marvel",
            "[HUB]: Marvel" to "Marvel",
            "[BOX SET] Bond" to "Bond",
            "[Kometa] Oscars" to "Oscars",
            "Marvel (4K)" to "Marvel",
            "Marvel (4K HDR DV)" to "Marvel",
            "Marvel (1080p)" to "Marvel",
            "Bond (Box Set)" to "Bond",
            "Marvel [HUB]" to "Marvel",
            "🎅 Christmas" to "Christmas",
            "Christmas 🎄" to "Christmas",
            "🎃👻 Halloween" to "Halloween",
            "❤\uFE0F Romance" to "Romance",
            "🇫🇷 French films" to "French films",
            "👨\u200D👩\u200D👧 Family night" to "Family night",
            "!010_Bond" to "Bond",
            "001_Bond" to "Bond",
            "01. Marvel" to "Marvel",
            "01 - Marvel" to "Marvel",
            "!!Marvel" to "Marvel",
            "+++++++_X-Men" to "X-Men",
            "~Misc" to "Misc",
            "^actors" to "actors",
            "_Kids" to "Kids",
            "! Favorites" to "Favorites",
            "!- Favorites" to "Favorites",
            "zz Old stuff" to "Old stuff",
            "zz_Old stuff" to "Old stuff",
            "Mar\u200Bvel" to "Marvel",
            "\uFEFF  Marvel   Heroes " to "Marvel Heroes",
            "[HUB] 🎅 Christmas (4K)" to "Christmas",
        ).forEach { (server, shown) -> assertEquals(server, shown, TidyNames.clean(server, fromTmdb = false)) }
    }

    @Test
    fun `titles that only look decorated stay`() {
        listOf(
            "[REC]", "[•REC]", "[REC]²", "#Alive", "!Women Art Revolution", "+1", "*batteries not included",
            "(500) Days of Summer", "M*A*S*H", "'71", "...And Justice for All", "Alien / Predator",
        )
            .forEach { assertEquals(it, TidyNames.clean(it, fromTmdb = false)) }
    }

    @Test
    fun `a TMDb box set loses its Collection, nothing readable left keeps the server's name`() {
        assertEquals("Bond", TidyNames.collection("Bond Collection", fromTmdb = true))
        assertEquals("Bond Collection", TidyNames.collection("Bond Collection", fromTmdb = false))
        assertEquals("The Collection", TidyNames.collection("The Collection", fromTmdb = true))
        assertEquals("[HUB]", TidyNames.clean("[HUB]", fromTmdb = false))
    }

    @Test
    fun `two libraries that would read the same keep the server's names`() {
        assertEquals(
            listOf("Trending Movies", "Kids (4K)", "Kids"),
            TidyNames.libraries(listOf("[HUB] Trending Movies", "Kids (4K)", "Kids")),
        )
    }
}
