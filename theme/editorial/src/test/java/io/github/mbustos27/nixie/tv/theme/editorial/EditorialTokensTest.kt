// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.editorial

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.dp
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Editorial's tokens against the plan's rules: radius 8, no oblong pills, 4.5:1 text, a focus you can see. */
class EditorialTokensTest {
    private val t = EditorialTokens

    @After
    fun backToGlacier() = NixieTheme.use(NixieThemeId.Glacier)

    @Test
    fun registersAndIsWornAsEditorial() {
        NixieTheme.register(EditorialTheme)
        NixieTheme.use(NixieThemeId.Editorial)
        assertSame(EditorialTheme, NixieTheme.current)
        assertEquals(NixieThemeId.Editorial, EditorialTheme.id)
    }

    @Test
    fun oneRadiusOfEightAndNoOblongPills() {
        assertEquals(8.dp, t.radius)
        assertEquals(RoundedCornerShape(8.dp), t.pill)
    }

    @Test
    fun everyColourTheContractHasIsSet() {
        listOf(
            t.void, t.deep, t.glassFill, t.glassBorder, t.glassFill2, t.glassBorder2, t.ice, t.mist, t.accentMain,
            t.accentDeep, t.ground,
        ).forEach { assertTrue("$it unset", it.isSpecified) }
        assertTrue("its own accent", t.ownAccent)
    }

    @Test
    fun everySlotTheContractHasTodayIsFilled() {
        val s = EditorialSlots
        listOf(
            "pageGround" to s.pageGround,
            "topBarChrome" to s.topBarChrome,
            "topBarEntry" to s.topBarEntry,
            "rowHeader" to s.rowHeader,
            "rowHeaderAt" to s.rowHeaderAt,
            "heroBackdrop" to s.heroBackdrop,
            "heroFade" to s.heroFade,
            "heroKicker" to s.heroKicker,
            "quickConnectCode" to s.quickConnectCode,
            "setupBackdrop" to s.setupBackdrop,
        ).forEach { (name, slot) -> assertNotNull("$name is Glacier's", slot) }
        // Glacier's position marks and still cards stay: square-cornered marks under the theme's pill already.
    }

    @Test
    fun headingsAreSetInTheSerif() {
        assertEquals(EditorialType.Serif, t.displayFont)
        assertEquals(EditorialType.Serif, EditorialType.heading(34).fontFamily)
    }

    @Test
    fun sectionsAreNumberedAsAMagazinePrintsThem() {
        assertEquals("01", sectionNumber(1))
        assertEquals("09", sectionNumber(9))
        assertEquals("12", sectionNumber(12))
        assertEquals("104", sectionNumber(104))
    }

    @Test
    fun textReadsAtFourAndAHalfToOne() {
        val ink = t.void
        val pairs = mapOf(
            "text on the page" to Contrast.ratio(t.ice, ink),
            "secondary text on the page" to Contrast.ratio(t.mist, ink),
            "secondary text on the panel" to Contrast.ratio(t.mist, t.deep),
            "secondary text on the ground" to Contrast.ratio(t.mist, t.ground),
            "a field's text on its tint" to Contrast.ratio(t.mist, t.glassFill),
            "a button's text on its tint" to Contrast.ratio(t.ice, t.glassFill2),
            "a secondary button's text (pink at 12%)" to Contrast.ratio(t.ice, t.accentMain.copy(alpha = 0.12f)),
            "ink on pink (primary, focused)" to Contrast.ratio(t.void, t.accentMain),
            "ink on the deep shade (a primary button at rest)" to Contrast.ratio(t.void, t.accentDeep),
            "section numbers" to Contrast.ratio(EditorialPalette.Numeral, ink),
            "figures" to Contrast.ratio(EditorialPalette.Numeral, ink),
            "kickers" to Contrast.ratio(EditorialPalette.Kicker, ink),
            "a tab on the bar" to Contrast.ratio(t.mist, EditorialPalette.Bar),
            "a tab on the bar over a white picture" to
                Contrast.ratio(t.mist, EditorialPalette.Bar, under = Color.White),
            "the focused tab, ink on paper" to Contrast.ratio(EditorialPalette.Ink, EditorialPalette.Paper),
        )
        val failing = pairs.filterValues { it < 4.5 }
        assertTrue("below 4.5:1: $failing", failing.isEmpty())
    }

    @Test
    fun focusIsARuleYouCanSeeFromTheCouchNotOnlyAColour() {
        val focus = assertNotNullAnd(t.focus)
        // Non-text contrast (3:1) of the paper rule against the page and against a pink button.
        assertTrue(Contrast.ratio(focus.inner, t.void) >= 3.0)
        assertTrue(focus.innerWidth >= 3.dp)
        assertTrue("a band outside the rule", focus.glowWidth >= 6.dp)

        val entry = assertNotNullAnd(EditorialSlots.topBarEntry)
        val rest = entry(false, false)
        val focused = entry(true, false)
        val here = entry(false, true)
        // Focus fills the tab (paper) rather than recolouring its text.
        assertNotEquals(rest.background, focused.background)
        assertTrue(Contrast.ratio(focused.background, t.void) >= 3.0)
        // The page you are on is an underline, never a pill; it stays while the tab has focus.
        assertTrue(here.underline.isSpecified)
        assertEquals(Color.Transparent, here.background)
        assertTrue(entry(true, true).underline.isSpecified)
        assertTrue(Contrast.ratio(entry(true, true).underline, EditorialPalette.Paper) >= 3.0)
        assertTrue(!rest.underline.isSpecified)
    }

    private fun <T : Any> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
