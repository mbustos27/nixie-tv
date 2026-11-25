// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NixieVisualsTest {

    @Test
    fun theBoxesGetTheirTiers() {
        val gb = 1_000_000_000L
        assertEquals("a 4K Google TV box, 1.9 GB", GlassTier.Fake, NixieVisuals.tierFor(1_900_000_000L, false, 34))
        assertEquals("the 2 GB Chromecast", GlassTier.Fake, NixieVisuals.tierFor(2 * gb, false, 31))
        assertEquals("the Google TV Streamer, 4 GB", GlassTier.Live, NixieVisuals.tierFor(4 * gb, false, 34))
        assertEquals("no Modifier.blur before Android 12", GlassTier.Fake, NixieVisuals.tierFor(4 * gb, false, 30))
        assertEquals("a low-RAM device", GlassTier.Flat, NixieVisuals.tierFor(4 * gb, true, 34))
    }

    @Test
    fun onlyTheLiveTierBlursOrCastsShadows() {
        val live = VisualCapabilities(GlassTier.Live)
        assertTrue(live.liveBlur && live.shadows && live.ambientMotion)
        assertNull(live.blurredImageWidth)
        for (tier in listOf(GlassTier.Fake, GlassTier.Flat)) {
            val c = VisualCapabilities(tier)
            assertFalse("$tier blurs", c.liveBlur)
            assertFalse("$tier shadows", c.shadows)
            assertEquals(VisualCapabilities.PRE_BLURRED_WIDTH, c.blurredImageWidth)
        }
        assertTrue(VisualCapabilities(GlassTier.Fake).ambientMotion)
        assertFalse(VisualCapabilities(GlassTier.Flat).ambientMotion)
    }

    @Test
    fun preBlurredPicturesAreAskedSmall() {
        assertEquals(
            "http://h/Items/1/Images/Primary?tag=t&quality=90&maxWidth=96",
            NixieVisuals.preBlurred(
                "http://h/Items/1/Images/Primary?fillWidth=1920&tag=t&fillHeight=1080&quality=90",
                96,
            ),
        )
        assertEquals("http://h/a.jpg?maxWidth=64", NixieVisuals.preBlurred("http://h/a.jpg", 64))
        assertNull(NixieVisuals.preBlurred(null, 64))
    }
}
