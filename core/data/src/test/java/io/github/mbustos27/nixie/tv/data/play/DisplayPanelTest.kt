// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TV-S7: the panel is the display mode's size, never the UI's 1080p on a 4K TV. */
class DisplayPanelTest {

    @Test
    fun `a 4K mode under a 1080p UI is a 4K panel`() {
        assertEquals(3840 to 2160, panelSize(3840 to 2160, 1920 to 1080))
    }

    @Test
    fun `a 1080p display is a 1080p panel`() {
        assertEquals(1920 to 1080, panelSize(1920 to 1080, 1920 to 1080))
    }

    @Test
    fun `sizes are landscape whichever way they are reported`() {
        assertEquals(3840 to 2160, panelSize(2160 to 3840, 1080 to 1920))
    }

    @Test
    fun `without a mode, or with a smaller one, the UI's size counts as before`() {
        assertEquals(1920 to 1080, panelSize(null, 1920 to 1080))
        assertEquals(1920 to 1080, panelSize(0 to 0, 1080 to 1920))
        assertEquals(1920 to 1080, panelSize(1280 to 720, 1920 to 1080))
    }

    @Test
    fun `only the audio's or the container's reasons leave the picture copied`() {
        assertTrue(pictureOnlyCopied("AudioCodecNotSupported"))
        assertTrue(pictureOnlyCopied("AudioProfileNotSupported,ContainerNotSupported"))
        assertFalse(pictureOnlyCopied("AudioCodecNotSupported,VideoRangeTypeNotSupported"))
        assertFalse(pictureOnlyCopied("ContainerBitrateExceedsLimit"))
        assertFalse(pictureOnlyCopied("SubtitleCodecNotSupported"))
        assertFalse(pictureOnlyCopied(null))
        assertFalse(pictureOnlyCopied(""))
    }

    @Test
    fun `describe names the UI only where it differs`() {
        assertEquals("3840x2160 (UI 1920x1080)", DisplayPanel({ 3840 to 2160 }, { 1920 to 1080 }).describe())
        assertEquals("1920x1080", DisplayPanel({ 1920 to 1080 }, { 1080 to 1920 }).describe())
    }
}
