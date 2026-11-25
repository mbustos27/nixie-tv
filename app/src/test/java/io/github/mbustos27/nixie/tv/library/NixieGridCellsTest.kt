// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import androidx.compose.ui.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TV-S13: after an A–Z jump the rows start at the letter's first title, so the titles loaded before it never move a
 * card; scrolled back to the list's very start, its first row is short, its last title taking the rest of the row.
 */
class NixieGridCellsTest {

    @Test
    fun `only the last title of a short first row takes the rest of it`() {
        // M at 1,200 in rows of 7: the list's first row holds 3 titles.
        assertNotNull(NixieGridCells.span(offset = 0, rowPhase = 3, index = 2))
        assertNull(NixieGridCells.span(offset = 0, rowPhase = 3, index = 1))
        assertNull(NixieGridCells.span(offset = 0, rowPhase = 3, index = 3))
        // Not yet at the list's start, or rows from the top: every title one cell.
        assertNull(NixieGridCells.span(offset = 1_130, rowPhase = 3, index = 2))
        assertNull(NixieGridCells.span(offset = 0, rowPhase = 0, index = 0))
        assertNotEquals(Modifier, Modifier.nixieRowEnd(ends = true))
        assertEquals(Modifier, Modifier.nixieRowEnd(ends = false))
    }
}
