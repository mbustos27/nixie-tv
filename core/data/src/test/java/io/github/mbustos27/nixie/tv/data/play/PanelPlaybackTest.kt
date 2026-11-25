// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.DeviceCapabilities
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.DeviceProfiles
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.HdrType
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.VideoCodecSupport
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TV-S7: what a 4K title becomes on a 4K TV whose UI is drawn at 1080p (a 4K Google TV box), through the real
 * [io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository] against a server that decides like
 * Jellyfin's. Glacier took the UI's 1920x1080 for the display: a 4K title the server converted anyway was asked again
 * capped at 1920x1080, so a picture the server would have copied was converted down to 1080p.
 */
class PanelPlaybackTest {

    private val film4k = FakeTitle(width = 3840, height = 2160)

    /** A 4K HEVC Main 10 HDR10 film with a DTS-HD MA track, which "HD audio: the server converts" sends to the server. */
    private val film4kDtsHd = film4k.copy(audioCodec = "dts", audioProfile = "DTS-HD MA")

    @Test
    fun `on a 4K panel a 4K HEVC film whose audio is converted keeps its picture`() {
        val server = FakePlaybackServer(film4kDtsHd)
        val source = open(server, onn, mode = UHD, ui = FULL_HD, hdAudio = true)

        assertEquals(PlaybackMethod.Transcode, source.method)
        assertEquals(Choice.CopyVideo(listOf("AudioProfileNotSupported")), server.choices.last())
        assertTrue("never asked at 1080p: ${server.hevcLimits()}", server.hevcLimits().all { it.first >= 3840 })
        assertFalse("VideoResolutionNotSupported" in source.url)
        assertEquals(2160, source.pictureHeight)
    }

    @Test
    fun `a 1080p display still caps a picture the server converts at 1080p`() {
        // HDR10 on a display without HDR: the server converts the picture whatever the display.
        val server = FakePlaybackServer(film4k)
        val source = open(server, onn.copy(hdr = emptySet()), mode = FULL_HD, ui = FULL_HD)

        assertEquals(PlaybackMethod.Transcode, source.method)
        val last = server.choices.last() as Choice.EncodeVideo
        assertEquals(1920 to 1080, last.maxWidth to last.maxHeight)
        assertEquals(1920 to 1080, server.hevcLimits().last())
        assertEquals(1080, source.pictureHeight)
    }

    @Test
    fun `a 1080p display keeps a 4K picture the device decodes when only the audio is converted`() {
        // Re-encoding the picture at 1080p would be far more work for the server than copying it; the decoder scales it.
        val server = FakePlaybackServer(film4kDtsHd)
        val source = open(server, onn, mode = FULL_HD, ui = FULL_HD, hdAudio = true)

        assertEquals(PlaybackMethod.Transcode, source.method)
        assertEquals(Choice.CopyVideo(listOf("AudioProfileNotSupported")), server.choices.last())
        assertTrue("never asked at 1080p: ${server.hevcLimits()}", server.hevcLimits().all { it.first >= 3840 })
        assertEquals(2160, source.pictureHeight)
    }

    @Test
    fun `a 4K HEVC Main 10 film the device decodes plays directly, asked once`() {
        for (mode in listOf(UHD, FULL_HD)) {
            val server = FakePlaybackServer(film4k)
            val source = open(server, onn, mode = mode, ui = FULL_HD)

            assertEquals(PlaybackMethod.DirectPlay, source.method)
            assertEquals(listOf<Choice>(Choice.DirectPlay), server.choices.toList())
            assertEquals(4096 to 4096, server.hevcLimits().single())
        }
    }

    @Test
    fun `Dolby Vision without a Dolby Vision display is converted at the panel's size, its ranges as before`() {
        // Profile 5 has no HDR10 base layer: on the 4K box (its TV shows HDR10, HLG and HDR10+, no Dolby Vision) the
        // server converts the picture whatever the panel. On the 4K panel it does so at the film's size, not at 1080p.
        val dv5 = film4k.copy(range = "DOVI", dolbyVisionProfile = 5)
        val server = FakePlaybackServer(dv5)
        val source = open(server, onn, mode = UHD, ui = FULL_HD)

        assertEquals(PlaybackMethod.Transcode, source.method)
        assertEquals(listOf("VideoRangeTypeNotSupported"), (server.choices.last() as Choice.EncodeVideo).reasons)
        assertTrue(server.hevcLimits().all { it.first >= 3840 })
        assertEquals(2160, source.pictureHeight)
        // The ranges offered are the display's, as Glacier's profile has them, whatever the panel's size.
        val ranges = DeviceProfiles.build(onn, 120_000_000).rangeTypes()
        for (asked in server.asked.toList()) assertEquals(ranges, asked.deviceProfile!!.rangeTypes())
        assertFalse("DOVI" in ranges)
        assertTrue("HDR10" in ranges && "HLG" in ranges)
    }

    @Test
    fun `Dolby Vision plays directly where the display shows it, on either panel`() {
        val dvDisplay = onn.copy(hdr = onn.hdr + HdrType.DolbyVision)
        val dv5 = film4k.copy(range = "DOVI", dolbyVisionProfile = 5)
        for (mode in listOf(UHD, FULL_HD)) {
            val server = FakePlaybackServer(dv5)
            assertEquals(PlaybackMethod.DirectPlay, open(server, dvDisplay, mode = mode, ui = FULL_HD).method)
        }
    }

    @Test
    fun `HDR10 plays directly only where the display shows HDR10`() {
        val sdrDisplay = onn.copy(hdr = emptySet())
        val server = FakePlaybackServer(film4k)
        val source = open(server, sdrDisplay, mode = UHD, ui = FULL_HD)

        assertEquals(PlaybackMethod.Transcode, source.method)
        assertEquals(listOf("VideoRangeTypeNotSupported"), (server.choices.last() as Choice.EncodeVideo).reasons)
        assertTrue(server.hevcLimits().all { it.first >= 3840 })
    }

    private fun open(
        server: FakePlaybackServer,
        capabilities: DeviceCapabilities,
        mode: Pair<Int, Int>,
        ui: Pair<Int, Int>,
        hdAudio: Boolean = false,
    ) = runBlocking {
        server.repository(capabilities, mode, ui, hdAudio).open(FakePlaybackServer.ITEM, 0, null, null)
    }

    /** The HEVC width and height limits of every device profile posted, in order. */
    private fun FakePlaybackServer.hevcLimits(): List<Pair<Int, Int>> = asked.toList().map { dto ->
        val conditions = dto.deviceProfile!!.codecProfiles
            .filter { it.type == CodecType.VIDEO && it.codec == "hevc" }
            .flatMap { it.conditions }
        fun limit(property: ProfileConditionValue) = conditions.first { it.property == property }.value!!.toInt()
        limit(ProfileConditionValue.WIDTH) to limit(ProfileConditionValue.HEIGHT)
    }

    private fun DeviceProfile.rangeTypes(): List<String> = codecProfiles
        .first {
            it.type == CodecType.VIDEO && it.codec == "hevc" &&
                it.conditions.any { c -> c.property == ProfileConditionValue.VIDEO_RANGE_TYPE }
        }
        .conditions.first { it.property == ProfileConditionValue.VIDEO_RANGE_TYPE }.value!!.split('|')

    private companion object {
        val UHD = 3840 to 2160
        val FULL_HD = 1920 to 1080

        /**
         * A 4K Google TV box as `testing/devices/google-tv-4k-box.json` measured it: Amlogic HEVC to 4096x4096 at High
         * tier 5.1 with Main 10, HDR10 and HDR10+; its TV shows HDR10, HLG and HDR10+ (no Dolby Vision); the Dolby
         * Vision decoder declares profiles 4, 5 and 8; DTS goes out as the core only.
         */
        val onn = DeviceCapabilities(
            h264 = VideoCodecSupport(4096, 4096, maxLevel = 52, tenBit = false),
            hevc = VideoCodecSupport(4096, 4096, maxLevel = 153, tenBit = true),
            av1 = VideoCodecSupport(3840, 3840, maxLevel = null, tenBit = true),
            vp9 = VideoCodecSupport(4096, 4096, maxLevel = null, tenBit = true),
            otherVideoCodecs = setOf("vp8", "mpeg2video", "vc1", "mpeg4"),
            audioCodecs = setOf("aac", "mp3", "mp2", "flac", "opus", "vorbis", "ac3", "eac3", "dts", "dca", "truehd"),
            maxAudioChannels = 8,
            passthroughAudio = setOf("ac3", "eac3", "truehd"),
            dtsHdPassthrough = false,
            hdr = setOf(HdrType.Hdr10, HdrType.Hdr10Plus, HdrType.Hlg),
            dolbyVisionProfiles = setOf(4, 5, 8),
        )
    }
}
