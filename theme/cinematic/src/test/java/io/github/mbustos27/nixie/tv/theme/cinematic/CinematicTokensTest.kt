// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.cinematic

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.dp
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CinematicTokensTest {
    private val tokens = CinematicTokens

    @Test
    fun `is the Cinematic theme and switches in`() {
        assertEquals(NixieThemeId.Cinematic, CinematicTheme.id)
        NixieTheme.register(CinematicTheme)
        NixieTheme.use(NixieThemeId.Cinematic)
        try {
            assertSame(CinematicTheme, NixieTheme.current)
            assertTrue(NixieThemeId.Cinematic in NixieTheme.available)
        } finally {
            NixieTheme.use(NixieThemeId.Glacier)
        }
    }

    @Test
    fun `one radius of 8 and no oblong pills`() {
        assertEquals(8.dp, tokens.radius)
        assertEquals(RoundedCornerShape(8.dp), tokens.pill)
    }

    @Test
    fun `every colour and every slot is the theme's own`() {
        listOf(
            tokens.void, tokens.deep, tokens.glassFill, tokens.glassBorder, tokens.glassFill2, tokens.glassBorder2,
            tokens.ice, tokens.mist, tokens.accentMain, tokens.accentDeep, tokens.ground,
        ).forEach { assertTrue(it.isSpecified) }
        val slots = CinematicTheme.slots
        assertNotNull(slots.pageGround)
        assertNotNull(slots.topBarChrome)
        assertNotNull(slots.topBarEntry)
        assertNotNull(slots.rowHeader)
        assertNotNull(slots.heroBackdrop)
        assertNotNull(slots.quickConnectCode)
        assertNotNull(slots.setupBackdrop)
        assertNotNull(slots.heroPositionMarks)
        assertNotNull(slots.heroKicker)
        assertNotNull(slots.stillCard)
        // Phase 2: every browse, item and player slot.
        with(slots.browse) {
            listOf(libraryHeader, chip, letterRail, posterArt).forEach { assertNotNull(it) }
        }
        with(slots.item) {
            listOf(backdrop, crumb, title, facts, playArea, episodeStill).forEach { assertNotNull(it) }
        }
        with(slots.player) {
            listOf(osdScrim, seekBar, times, skipButton, upNextCountdown, panelChrome).forEach { assertNotNull(it) }
        }
    }

    @Test
    fun `chips and letters read in every state`() {
        val page = tokens.void
        for (focused in listOf(false, true)) {
            for (active in listOf(false, true)) {
                val chip = cinematicChip(focused, active)
                val ground = chip.background over page
                assertAtLeast(TEXT_CONTRAST, contrast(chip.foreground, ground), "chip focused=$focused active=$active")
                if (active) assertTrue(chip.underline.isSpecified)
                // A letter with titles (one without is disabled, with no contrast floor).
                val letter = cinematicLetter(focused, current = active, available = true)
                val behind = letter.background over page
                assertAtLeast(TEXT_CONTRAST, contrast(letter.foreground, behind), "letter f=$focused c=$active")
            }
        }
        // Focus is a plate and a rim, not a colour alone.
        assertNotEquals(cinematicChip(false, false).border, cinematicChip(true, false).border)
    }

    @Test
    fun `plates keep small text readable over a white still`() {
        val badge = CinematicPalette.BadgePlate over Color.White
        assertAtLeast(TEXT_CONTRAST, contrast(tokens.ice, badge), "badge and runtime plates")
        val skip = CinematicPalette.SkipPlate over Color.White
        assertAtLeast(TEXT_CONTRAST, contrast(tokens.ice, skip), "skip plate")
        assertAtLeast(TEXT_CONTRAST, contrast(tokens.void, tokens.accentMain), "skip focused")
        val panel = CinematicPalette.PanelFill over Color.White
        for (text in listOf(tokens.ice, tokens.mist, CinematicPalette.Peach, tokens.accentMain)) {
            assertAtLeast(TEXT_CONTRAST, contrast(text, panel), "panel text $text")
        }
    }

    @Test
    fun `skip counts seconds, then minutes`() {
        assertEquals("0", secondsText(-3))
        assertEquals("42", secondsText(42))
        assertEquals("1:00", secondsText(60))
        assertEquals("1:15", secondsText(75))
    }

    @Test
    fun `focus zooms by a tenth`() {
        assertEquals(1.1f, tokens.focusZoom, 0.0001f)
    }

    @Test
    fun `the pager shows at most seven stills, the current one among them`() {
        for (count in 2..12) {
            for (index in 0 until count) {
                val shown = pagerWindow(count, index)
                assertTrue("count $count, index $index: $shown", index in shown)
                assertEquals("count $count, index $index", minOf(count, PAGER_WINDOW), shown.count())
                assertTrue("count $count, index $index: $shown", shown.first >= 0 && shown.last < count)
            }
        }
    }

    @Test
    fun `text reads at 4_5 to 1 on every ground`() {
        val grounds = listOf(
            tokens.void,
            tokens.deep,
            tokens.ground,
            CinematicPalette.FrameTop,
            CinematicPalette.FrameFoot,
        )
        for (ground in grounds) {
            assertAtLeast(TEXT_CONTRAST, contrast(tokens.ice, ground), "ice on $ground")
            assertAtLeast(TEXT_CONTRAST, contrast(tokens.mist, ground), "mist on $ground")
            assertAtLeast(TEXT_CONTRAST, contrast(tokens.accentMain, ground), "pink on $ground")
        }
        // Glacier's glass (buttons, fields) over the page.
        val glass = tokens.glassFill2 over tokens.void
        assertAtLeast(TEXT_CONTRAST, contrast(tokens.ice, glass), "ice on glass")
        assertAtLeast(TEXT_CONTRAST, contrast(tokens.mist, glass), "mist on glass")
    }

    @Test
    fun `text on the accents reads at 4_5 to 1`() {
        // Glacier draws Void on both accents: a focused control (pink) and a primary one at rest (the deep shade).
        assertAtLeast(TEXT_CONTRAST, contrast(tokens.void, tokens.accentMain), "night on pink")
        assertAtLeast(TEXT_CONTRAST, contrast(tokens.void, tokens.accentDeep), "night on the deep shade")
    }

    @Test
    fun `the island keeps its text readable over a white still`() {
        // The middle of the island, where the entries' text sits: tint, then the faint sheen, over white.
        val island = CinematicPalette.IslandSheenMid over (CinematicPalette.IslandTint over Color.White)
        assertAtLeast(TEXT_CONTRAST, contrast(tokens.mist, island), "inactive entry")
        assertAtLeast(TEXT_CONTRAST, contrast(tokens.ice, island), "active entry")
        // Pink is never text on the island: the page's underline and the update dot, non-text marks (3:1).
        assertAtLeast(3.0, contrast(tokens.accentMain, island), "pink underline and update dot")
    }

    @Test
    fun `the island's entries`() {
        val idle = islandEntry(focused = false, active = false)
        val page = islandEntry(focused = false, active = true)
        val focused = islandEntry(focused = true, active = false)
        val focusedPage = islandEntry(focused = true, active = true)
        // The page you are on: an underline, never a tinted pill.
        assertEquals(Color.Transparent, page.background)
        assertEquals(tokens.accentMain, page.underline)
        assertTrue(!idle.underline.isSpecified)
        // Focus is a plate and a rim, not a colour alone; its text reads on the plate.
        assertNotEquals(idle.border, focused.border)
        assertNotEquals(idle.background, focused.background)
        assertAtLeast(TEXT_CONTRAST, contrast(focused.foreground, focused.background), "focused entry")
        assertAtLeast(3.0, contrast(focusedPage.underline, focusedPage.background), "underline on the plate")
    }

    @Test
    fun `the focus frame is visible from the couch`() {
        val focus = requireNotNull(tokens.focus)
        assertTrue(focus.innerWidth >= 3.dp)
        assertTrue(focus.glowWidth >= 12.dp)
        assertEquals(tokens.accentMain, focus.outer)
        // A non-text indicator: 3 to 1 against the ground and against the pink glow around it.
        assertAtLeast(3.0, contrast(focus.inner, tokens.void), "rim on night")
        val glow = CinematicPalette.Pink.copy(alpha = 0.55f) over tokens.void
        assertAtLeast(3.0, contrast(focus.inner, glow), "rim in glow")
    }

    private fun assertAtLeast(
        min: Double,
        actual: Double,
        what: String,
    ) = assertTrue("$what: ${"%.2f".format(actual)} < $min", actual >= min)
}
