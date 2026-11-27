// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import io.github.glacier_jellyfin.androidtv.core.data.settings.MaxBitrate
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.DeviceCapabilities
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.VideoCodecSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.1 review: BandwidthProbe.SECOND_MAX_MS only counts if the repository's link test hands it to the second download.
 * Through the real PlaybackRepository's test against [FakeLink], whose link slows down after the first download.
 */
class BandwidthProbeWiringTest {

    @Test
    fun `the second download stops 4 s after its first byte on a link that slowed down`() {
        // 2 Mbit/s for the first block, then 200 kbit/s: the second block (512 KiB) would take 21 s, and the download's
        // own limit (8 s) would end it at 8.
        FakeLink(bitsPerSecond = 2_000_000, laterBitsPerSecond = 200_000).use { link ->
            val repository = FakePlaybackServer(FakeTitle(1920, 1080), link.address)
                .repository(device, 1920 to 1080, 1920 to 1080, maxBitrate = MaxBitrate.Auto)
            val started = System.nanoTime()
            val budget = runBlocking { repository.probeBudget() }
            val tookMs = (System.nanoTime() - started) / 1_000_000

            assertEquals("two downloads", 2, link.tests.get())
            assertTrue("the test took $tookMs ms", tookMs < 6_500)
            // The better reading (the first) counts.
            assertTrue("budget $budget", budget!! in 1_500_000..1_800_000)
        }
    }

    private companion object {
        val device = DeviceCapabilities(
            h264 = VideoCodecSupport(1920, 1080, maxLevel = 41, tenBit = false),
            hevc = null,
            av1 = null,
            vp9 = null,
            otherVideoCodecs = emptySet(),
            audioCodecs = setOf("aac"),
            maxAudioChannels = 2,
            hdr = emptySet(),
        )
    }
}
