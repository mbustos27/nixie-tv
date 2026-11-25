// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.cinematic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Text over Cinematic's art, measured on the rendered pixels: each slot is drawn alone over the worst case (a pure
 * white still under the hero and the island), and the brightest pixel where text sits must leave the theme's text
 * colours at 4.5:1 or more. The results are written next to the shots (contrast.txt).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi")
class CinematicContrastTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun wear() {
        NixieTheme.register(CinematicTheme)
        NixieTheme.use(NixieThemeId.Cinematic)
    }

    @After
    fun takeOff() = NixieTheme.use(NixieThemeId.Glacier)

    @Test
    fun `hero text over a white still`() {
        val pixels = render(1920, 680) {
            Box(Modifier.size(1920.dp, 680.dp)) {
                CinematicSlots.heroBackdrop!!.invoke(this, null) { _, m -> Box(m.background(Color.White)) }
            }
        }
        // Spotlight's info: 80 from the start, 74 from the foot, 900 wide, up to the chip at the top of its column.
        check("hero, the info column", pixels, x = 80 until 980, y = 40 until 606, Words)
        // The rating and the "continue watching" chip are pink, at the start of the column.
        check("hero, pink facts", pixels, x = 80 until 420, y = 40 until 606, listOf(CinematicTokens.accentMain))
        // The kicker, in peach capitals, at the top of the column while resuming.
        check("hero, peach kicker", pixels, x = 80 until 420, y = 40 until 260, listOf(CinematicPalette.Peach))
    }

    @Test
    fun `still card text over a white still, focused`() {
        // The words left empty: only what sits under them is measured; they keep their lines' heights.
        val pixels = render(316, 178) {
            Box(Modifier.size(316.dp, 178.dp)) {
                StillCard(null, "", "", progress = 0.4f, focused = true) { _, m -> Box(m.background(Color.White)) }
            }
        }
        // The title and its line, between the sides' 16 dp, above the progress.
        check("still card, the words", pixels, x = 16 until 300, y = 100 until 158, Words)
    }

    @Test
    fun `item page text over a white still`() {
        val pixels = render(1920, 760) {
            Box(Modifier.size(1920.dp, 760.dp)) {
                CinematicItem.backdrop!!.invoke(this, null, 760) { _, m -> Box(m.background(Color.White)) }
            }
        }
        // DetailScreen's header from 130 down: the crumb, an 84 sp title up to 1180 wide, then the facts,
        // the overview (900 wide) and the Play area, all within the 700 dp header region.
        check("item, the title", pixels, x = 80 until 1260, y = 130 until 360, listOf(CinematicTokens.ice))
        check("item, facts and overview", pixels, x = 80 until 980, y = 130 until 700, Words)
        check(
            "item, peach crumb, pink rating",
            pixels,
            x = 80 until 420,
            y = 130 until 400,
            listOf(CinematicPalette.Peach, CinematicTokens.accentMain),
        )
    }

    @Test
    fun `player top text over a white picture`() {
        val top = render(1920, 280) { Canvas(Modifier.size(1920.dp, 280.dp)) { drawOsdScrim(top = true) } }
        // The title block (80, 60) and the clock (top right, 60): crumb, a 46 sp title, a line under it.
        check("player top, title and clock", top, x = 80 until 1840, y = 60 until 200, Words)
    }

    @Test
    fun `player foot text over a white picture`() {
        val foot = render(1920, 560) { Canvas(Modifier.size(1920.dp, 560.dp)) { drawOsdScrim(top = false) } }
        // The bottom block, 72 from the foot: buttons with labels, 30 above them the seek bar and its times.
        check("player foot, times and buttons", foot, x = 80 until 1840, y = 300 until 488, Words)
        check("player foot, peach chapter", foot, x = 80 until 1840, y = 300 until 488, listOf(CinematicPalette.Peach))
    }

    @Test
    fun `island text over a white still`() {
        val pixels = render(1200, 68) {
            Box(Modifier.size(1200.dp, 68.dp).background(Color.White)) {
                Box(Modifier.fillMaxSize().island())
            }
        }
        // The entries' text sits in the middle third of the bar.
        check("island", pixels, x = 20 until 1180, y = 22 until 46, Words)
    }

    @Test
    fun `sign-in text over the dusk still`() {
        val pixels = render(1920, 1080) { Canvas(Modifier.size(1920.dp, 1080.dp)) { drawDusk() } }
        // SetupLayout's two columns (130 to 1656 with the controls' padding), where their content can reach.
        val columns = 130 until 1656
        check("dusk still, the columns", pixels, columns, 150 until 880, Words)
        // Pink text on the setup screens is only a focused server's name, 26 sp in the display face: large text, 3:1.
        check("dusk still, large pink", pixels, columns, 150 until 880, listOf(CinematicTokens.accentMain), min = 3.0)
    }

    @Test
    fun `page text on the stage`() {
        val pixels = render(1920, 1080) { Canvas(Modifier.size(1920.dp, 1080.dp)) { drawStage() } }
        check(
            "stage",
            pixels,
            x = 0 until 1920,
            y = 0 until 1080,
            listOf(CinematicTokens.ice, CinematicTokens.mist, CinematicTokens.accentMain),
        )
    }

    private fun render(
        width: Int,
        height: Int,
        content: @Composable () -> Unit,
    ): PixelMap {
        compose.setContent { GlacierTheme { content() } }
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage()
        assertTrue("rendered ${image.width} x ${image.height}", image.width >= width && image.height >= height)
        return image.toPixelMap()
    }

    /** The brightest pixel of the region against each text colour. */
    private fun check(
        what: String,
        pixels: PixelMap,
        x: IntRange,
        y: IntRange,
        texts: List<Color>,
        min: Double = TEXT_CONTRAST,
    ) {
        var brightest = 0.0
        var at = 0 to 0
        for (py in y step 2) {
            for (px in x step 2) {
                val l = luminance(pixels[px, py])
                if (l > brightest) {
                    brightest = l
                    at = px to py
                }
            }
        }
        texts.forEach { text ->
            val ratio = contrast(luminance(text), brightest)
            results += "%-28s %-10s %5.2f:1 (brightest %s at %d,%d, L %.3f)".format(
                what,
                text.hex(),
                ratio,
                pixels[at.first, at.second].hex(),
                at.first,
                at.second,
                brightest,
            )
            assertTrue("$what: ${text.hex()} at ${"%.2f".format(ratio)}:1 over $at", ratio >= min)
        }
    }

    private fun Color.hex() = "#%06X".format(
        ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt(),
    )

    companion object {
        private val Words = listOf(CinematicTokens.ice, CinematicTokens.mist)

        private val results = mutableListOf<String>()

        @JvmStatic
        @AfterClass
        fun write() {
            shotFile("contrast.txt").writeText(results.sorted().joinToString("\n", postfix = "\n"))
        }
    }
}
