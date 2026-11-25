// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.library

import io.github.glacier_jellyfin.androidtv.core.data.media.NameOrder
import io.github.glacier_jellyfin.androidtv.core.data.media.railLetter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The A–Z rail files a title under the letter the server sorts it by: the server drops "&", "¡" and the like before
 * the first letter of its sort name, so a jump to M lands on "&Me Archive", and the rail must say M there too (it said
 * '#', and the title moved once the index loaded). Sort names as Jellyfin 12.4 makes them.
 */
class RailLetterTest {

    private fun letter(name: String, sortName: String) = railLetter(NameOrder.title(name, sortName))

    @Test
    fun `a symbol the server drops before the first letter files the title under that letter`() {
        assertEquals('M', railLetter(NameOrder.title("&Me Archive", "me archive")))
        assertEquals('T', letter("¡Three Archive", "three archive"))
    }

    @Test
    fun `a symbol or a blank the server keeps first still files the title under the hash`() {
        assertEquals('#', letter("#Alive Black", "#alive black"))
        assertEquals('#', letter("[REC] Archive", "[rec] archive"))
        assertEquals('#', letter("\$9.99 Black", "\$0000000009 0000000099 black"))
        assertEquals('#', letter("...Dots Album", "   dots album"))
        assertEquals('#', letter("'71 Blue", "0000000071 blue"))
    }

    @Test
    fun `the title is the name from its first letter on, and the rest of the rule holds`() {
        assertEquals("Me Archive", NameOrder.title("&Me Archive", "me archive"))
        assertEquals("Test", NameOrder.title("The Test", "test"))
        assertEquals("Star Wars 4", NameOrder.title("A New Hope", "Star Wars 4"))
    }
}
