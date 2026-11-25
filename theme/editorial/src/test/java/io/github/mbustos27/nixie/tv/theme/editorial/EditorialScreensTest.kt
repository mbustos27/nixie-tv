// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.editorial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import io.github.glacier_jellyfin.androidtv.core.designsystem.ActionButton
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sign-in, the top bar and Home in Editorial at 1080p, drawn from the stand-ins of Glacier's screens, with the
 * contrast of the hero's text over its picture measured on the drawn pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi")
class EditorialScreensTest {
    @Before
    fun wearEditorial() {
        NixieTheme.register(EditorialTheme)
        NixieTheme.use(NixieThemeId.Editorial)
    }

    @After
    fun backToGlacier() = NixieTheme.use(NixieThemeId.Glacier)

    @Test
    fun signInWithQuickConnect() {
        Shots.save(Shots.render { Frame { QuickConnectStandIn(code = "482913") } }, "editorial-signin")
        Shots.save(Shots.render { Frame { QuickConnectStandIn(code = null) } }, "editorial-signin-waiting")
    }

    @Test
    fun topBar() {
        val shot = Shots.render {
            Frame {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.align(Alignment.TopCenter).padding(top = 34.dp)) {
                        // Moving along the bar from Home, then back on Home itself.
                        TopBarStandIn(active = "Home", focused = "Movies")
                        TopBarStandIn(active = "Home", focused = "Home", modifier = Modifier.padding(top = 60.dp))
                        TopBarStandIn(active = "Home", focused = "", modifier = Modifier.padding(top = 60.dp))
                    }
                }
            }
        }
        Shots.save(shot, "editorial-topbar")
    }

    @Test
    fun home() {
        Shots.save(Shots.render { Frame { HomeStandIn(Art.Sunset) } }, "editorial-home")
    }

    /**
     * The hero's text stands on ink at 4.5:1 or better whatever the picture: drawn with a pure white picture and no
     * text, every pixel where Glacier's title, facts, synopsis and buttons sit (80 from the edge, 900 wide, up from
     * 74 above the hero's foot to below the top bar) is dark enough for both text colours.
     */
    @Test
    fun heroTextKeepsItsContrastOverAnyPicture() {
        val shot = Shots.render { Frame { HeroStandIn(Art.White, withInfo = false) } }
        Shots.save(shot, "editorial-hero-white-contrast")

        // Not vacuous: the white picture is on screen, in the plate.
        val plateLeft = Shots.WIDTH - EditorialHero.PLATE_END - EditorialHero.PLATE_WIDTH
        val plateMiddle = Contrast.luminance(
            shot.getPixel(plateLeft + EditorialHero.PLATE_WIDTH / 2, EditorialHero.PLATE_TOP + 100),
        )
        assertTrue("the picture shows in the plate ($plateMiddle)", plateMiddle > 0.9)
        // No fade over the plate's foot (heroFade is empty): the white reaches its bottom edge undimmed.
        val plateFoot = Contrast.luminance(
            shot.getPixel(
                plateLeft + EditorialHero.PLATE_WIDTH / 2,
                EditorialHero.PLATE_TOP + EditorialHero.PLATE_HEIGHT - 12,
            ),
        )
        assertTrue("the plate's foot is not faded ($plateFoot)", plateFoot > 0.9)

        val behindText = Contrast.brightest(shot, 80, 110, 80 + 900, SPOTLIGHT_HEIGHT - 74)
        val text = Contrast.ratio(Contrast.luminance(EditorialTokens.ice), behindText)
        val secondary = Contrast.ratio(Contrast.luminance(EditorialTokens.mist), behindText)
        println("Hero over white: text $text:1, secondary $secondary:1")
        assertTrue("text over the hero $text:1", text >= 4.5)
        assertTrue("secondary text over the hero $secondary:1", secondary >= 4.5)
    }

    /**
     * Focus shows from the couch: a focused button gets the paper rule with the pink band outside it; the same
     * button at rest has no paper anywhere around it. Read down a column through each button's start.
     */
    @Test
    fun focusDrawsThePaperRuleAndThePinkBand() {
        val focus = FocusRequester()
        var hasFocus = false
        val shot = Shots.render(focus, isFocused = { hasFocus }) {
            Frame {
                Column(
                    Modifier.padding(start = 200.dp, top = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(138.dp),
                ) {
                    ActionButton(
                        onClick = {},
                        label = "Play",
                        primary = true,
                        modifier = Modifier.focusRequester(focus).onFocusChanged { hasFocus = it.isFocused },
                    )
                    ActionButton(onClick = {}, label = "Play", primary = true)
                }
            }
        }
        Shots.save(shot, "editorial-focus")
        fun isPaper(argb: Int) = (argb shr 16 and 0xFF) > 230 && (argb shr 8 and 0xFF) > 220 && (argb and 0xFF) > 215
        fun isPink(argb: Int) = (argb shr 16 and 0xFF) > 110 && (argb shr 16 and 0xFF) > 2 * (argb shr 8 and 0xFF)

        val focused = (170 until 232).map { shot.getPixel(240, it) }
        val paperAt = focused.indexOfFirst(::isPaper)
        assertTrue("a paper rule on the focused button", paperAt >= 0)
        assertTrue("a pink band outside it", focused.take(paperAt).any(::isPink))

        val rest = (370 until 432).map { shot.getPixel(240, it) }
        assertTrue("no paper around the button at rest", rest.none(::isPaper))
    }

    /** The bar's tabs read at 4.5:1 even over a white picture, as on a page whose art runs under the bar. */
    @Test
    fun topBarTabsKeepTheirContrastOverAWhitePicture() {
        val shot = Shots.render {
            Frame {
                Box(Modifier.fillMaxSize()) {
                    TestArt(Art.White, Modifier.fillMaxSize())
                    TopBarStandIn(
                        active = "Home",
                        focused = "",
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp),
                    )
                }
            }
        }
        Shots.save(shot, "editorial-topbar-white-contrast")
        // The strip's ground between two tabs, away from text: the bar's own top edge row inside its padding.
        val stripGround = Contrast.luminance(shot.getPixel(Shots.WIDTH / 2, 34 + 3))
        val tab = Contrast.ratio(Contrast.luminance(EditorialTokens.mist), stripGround)
        println("Bar over white: secondary $tab:1")
        assertTrue("a tab over a white picture $tab:1", tab >= 4.5)
    }
}
