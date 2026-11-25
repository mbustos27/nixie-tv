// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import io.github.glacier_jellyfin.androidtv.core.designsystem.Accent
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.mbustos27.nixie.tv.designsystem.NavEntryLook
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import io.github.mbustos27.nixie.tv.designsystem.NixieTokens
import io.github.mbustos27.nixie.tv.theme.cinematic.CinematicTheme
import io.github.mbustos27.nixie.tv.theme.drivein.DriveInTheme
import io.github.mbustos27.nixie.tv.theme.editorial.EditorialTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nixie's floor of 4.5:1 for text, for every Nixie theme, on the colours Glacier's components put text on: the page
 * and raised sheets, and the accent (Glacier writes the page colour on the accent: primary and focused buttons, the
 * focused top-bar entry). A theme with its own accent is checked on it; one without, on every accent a profile can
 * pick. The colours a theme's slots give the top bar's entries, the chips and the A–Z letters are checked too.
 * Glacier's own look, and a theme still wearing it, are upstream's and not held to it.
 */
class ThemeContrastTest {

    private val problems = mutableListOf<String>()

    @After
    fun takeOff() = NixieTheme.use(NixieThemeId.Glacier)

    @Test
    fun textReadsOnEveryThemesSurfaces() {
        listOf(DriveInTheme, CinematicTheme, EditorialTheme).forEach(NixieTheme::register)
        for (id in listOf(NixieThemeId.DriveIn, NixieThemeId.Cinematic, NixieThemeId.Editorial)) {
            NixieTheme.use(id)
            // A theme module not filled in yet wears Glacier's look, which is upstream's.
            if (NixieTheme.tokens == NixieTokens()) continue
            surfaces(id)
            accents(id)
            controls(id)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun surfaces(id: NixieThemeId) {
        val void = GlacierColors.Void
        check(id, "text on the page", GlacierColors.Ice, void)
        check(id, "secondary text on the page", GlacierColors.Mist, void)
        check(id, "text on a raised sheet", GlacierColors.Ice, GlacierColors.Deep)
        check(id, "text on glass", GlacierColors.Ice, GlacierColors.GlassFill2.compositeOver(void))
    }

    private fun accents(id: NixieThemeId) {
        val own = NixieTheme.tokens.ownAccent
        for (accent in if (own) listOf(Accent.entries.first()) else Accent.entries) {
            val name = if (own) "the theme's accent" else accent.name
            check(id, "button text on $name", GlacierColors.Void, accent.main)
            check(id, "primary button text on $name's deep shade", GlacierColors.Void, accent.deep)
        }
    }

    /** Label on fill, over the page where the fill is clear; letters without titles are inactive and exempt. */
    private fun controls(id: NixieThemeId) {
        val slots = NixieTheme.slots
        for (focused in listOf(false, true)) {
            for (on in listOf(false, true)) {
                slots.topBarEntry?.let { look(id, "top-bar entry ($focused, $on)", it(focused, on)) }
                slots.browse.chip?.let { look(id, "chip ($focused, $on)", it(focused, on)) }
                slots.browse.letterRail?.let { look(id, "letter ($focused, $on)", it(focused, on, true)) }
            }
        }
    }

    private fun look(
        id: NixieThemeId,
        what: String,
        look: NavEntryLook,
    ) = check(id, what, look.foreground, look.background.compositeOver(GlacierColors.Void))

    private fun check(
        id: NixieThemeId,
        what: String,
        text: Color,
        on: Color,
    ) {
        val (hi, lo) = listOf(text.luminance(), on.luminance()).sortedDescending()
        val ratio = (hi + 0.05f) / (lo + 0.05f)
        if (ratio < FLOOR) problems += "$id: $what %.2f:1".format(ratio)
    }

    private companion object {
        const val FLOOR = 4.5f
    }
}
