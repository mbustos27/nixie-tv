// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.editorial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.dp
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 2's slots: every one filled, the looks' rules, and the contrast of text where Editorial puts it over a
 * picture (an item page's print, an episode still's foot, the OSD's shades), measured on drawn pixels with a pure
 * white picture, the worst case for light text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi")
class EditorialPhase2Test {
    @Before
    fun wearEditorial() {
        NixieTheme.register(EditorialTheme)
        NixieTheme.use(NixieThemeId.Editorial)
    }

    @After
    fun backToGlacier() = NixieTheme.use(NixieThemeId.Glacier)

    private val lightTexts = mapOf(
        "text" to EditorialPalette.Text,
        "secondary text" to EditorialPalette.TextSecondary,
        "figures" to EditorialPalette.Numeral,
        "kickers" to EditorialPalette.Kicker,
    )

    private fun assertReads(where: String, brightestGround: Double) {
        val failing = lightTexts.mapValues { (_, c) -> Contrast.ratio(Contrast.luminance(c), brightestGround) }
            .filterValues { it < 4.5 }
        println("$where: brightest ground $brightestGround")
        assertTrue("$where below 4.5:1: $failing", failing.isEmpty())
    }

    @Test
    fun everyPhase2SlotIsFilled() {
        val s = EditorialSlots
        listOf(
            "browse.libraryHeader" to s.browse.libraryHeader,
            "browse.chip" to s.browse.chip,
            "browse.letterRail" to s.browse.letterRail,
            "browse.posterArt" to s.browse.posterArt,
            "item.backdrop" to s.item.backdrop,
            "item.crumb" to s.item.crumb,
            "item.title" to s.item.title,
            "item.facts" to s.item.facts,
            "item.playArea" to s.item.playArea,
            "item.episodeStill" to s.item.episodeStill,
            "player.osdScrim" to s.player.osdScrim,
            "player.seekBar" to s.player.seekBar,
            "player.times" to s.player.times,
            "player.skipButton" to s.player.skipButton,
            "player.upNextCountdown" to s.player.upNextCountdown,
            "player.panelChrome" to s.player.panelChrome,
        ).forEach { (name, slot) -> assertNotNull("$name is Glacier's", slot) }
    }

    @Test
    fun chipsAndLettersChangeTheirFillWithFocusAndReadAtFourAndAHalf() {
        val chip = EditorialChip
        assertNotEquals(chip(false, false).background, chip(true, false).background)
        assertTrue("the chosen chip is underlined", chip(false, true).underline.isSpecified)
        assertTrue("and stays so with focus", chip(true, true).underline.isSpecified)
        assertTrue(!chip(false, false).underline.isSpecified)
        val letter = EditorialLetterRail
        assertNotEquals(letter(false, true, true).background, letter(true, true, true).background)
        val pairs = mapOf(
            "chip at rest" to Contrast.ratio(chip(false, false).foreground, EditorialPalette.Ink),
            "chosen chip" to Contrast.ratio(chip(false, true).foreground, EditorialPalette.Ink),
            "focused chip" to Contrast.ratio(chip(true, false).foreground, chip(true, false).background),
            "letter" to Contrast.ratio(letter(false, false, true).foreground, EditorialPalette.Ink),
            "letter in view" to Contrast.ratio(
                letter(false, true, true).foreground,
                letter(false, true, true).background,
            ),
            "focused letter" to Contrast.ratio(
                letter(true, false, true).foreground,
                letter(true, false, true).background,
            ),
            "Skip at rest over white" to
                Contrast.ratio(EditorialPalette.Numeral, EditorialPalette.Ink.copy(alpha = 0.9f), under = Color.White),
            "Skip focused" to Contrast.ratio(EditorialPalette.Ink, EditorialPalette.Paper),
        )
        val failing = pairs.filterValues { it < 4.5 }
        assertTrue("below 4.5:1: $failing", failing.isEmpty())
    }

    /** An item page's text stands anywhere on the print: the brightest pixel under it still gives 4.5:1. */
    @Test
    fun itemPagePrintKeepsTextReadableOverAWhitePicture() {
        val backdrop = EditorialSlots.item.backdrop!!
        fun render(art: Art) = Shots.render {
            Frame {
                Box(Modifier.fillMaxWidth().height(760.dp)) {
                    backdrop("test://backdrop", 760) { _, m -> TestArt(art, m) }
                }
            }
        }
        val white = render(Art.White)
        Shots.save(white, "editorial-item-print-white")
        Shots.save(render(Art.Sunset), "editorial-item-print-sunset")
        // Wherever the page puts text: from below the top bar to the picture's foot, edge to edge.
        val brightest = Contrast.brightest(white, 80, 120, 1840, 700)
        assertReads("item print", brightest)
        // Not vacuous: the picture shows through the wash (an ink-only page would be at the ink's luminance).
        assertTrue("the picture shows ($brightest)", brightest > 3 * Contrast.luminance(EditorialPalette.Ink))
    }

    /** An episode still's foot, where its number and length sit, holds them at 4.5:1 over a white still. */
    @Test
    fun episodeStillFootKeepsItsFiguresReadable() {
        val shot = Shots.render {
            Frame {
                Box(Modifier.offset(100.dp, 100.dp).size(340.dp, 192.dp)) {
                    EditorialEpisodeStill("test://still", null, null, null, false) { _, m -> TestArt(Art.White, m) }
                }
                Box(Modifier.offset(500.dp, 100.dp).size(340.dp, 192.dp)) {
                    EditorialEpisodeStill("test://still", 3, 42, 0.35f, false) { _, m -> TestArt(Art.Sunset, m) }
                }
                Box(Modifier.offset(900.dp, 100.dp).size(340.dp, 192.dp)) {
                    EditorialEpisodeStill("test://still", 12, 44, null, true) { _, m -> TestArt(Art.Lighthouse, m) }
                }
            }
        }
        Shots.save(shot, "editorial-episode-stills")
        // The figures' band: from 70% of the height to the foot.
        assertReads("episode still foot", Contrast.brightest(shot, 100, 100 + 134, 440, 100 + 190))
    }

    /** The OSD's text stands where its shades are deep: the top's upper half (below the rule), the foot's lower 45%. */
    @Test
    fun osdShadesHoldTheirTextOverAWhitePicture() {
        val shot = Shots.render {
            Frame {
                Box(Modifier.size(1920.dp, 1080.dp)) {
                    TestArt(Art.White, Modifier.size(1920.dp, 1080.dp))
                    Column {
                        Canvas(Modifier.size(1920.dp, 280.dp)) { drawOsdScrim(top = true) }
                        Box(Modifier.height(240.dp))
                        Canvas(Modifier.size(1920.dp, 560.dp)) { drawOsdScrim(top = false) }
                    }
                }
            }
        }
        Shots.save(shot, "editorial-osd-shades-white")
        assertReads("OSD top", Contrast.brightest(shot, 0, 8, 1920, 140))
        assertReads("OSD foot", Contrast.brightest(shot, 0, 520 + (560 * 0.55f).toInt(), 1920, 1080))
    }
}
