// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.data.playback.StreamBudget
import io.github.glacier_jellyfin.androidtv.core.data.settings.MaxBitrate
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.DeviceCapabilities
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.HdrType
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.VideoCodecSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.1 Blocker: in "Auto" nothing played on a box whose link test read 440 kbit/s. Nixie asked the server for a stream
 * that fits; the server (a shared one where the profile may not transcode) refused it with NoCompatibleStream, and the
 * refusal was the end of it, though the first, unlimited answer allowed the file. Through the real [PlaybackRepository]
 * against a server that decides like Jellyfin's ([FakePlaybackServer]) and the server's bandwidth test on 127.0.0.1
 * ([FakeLink]).
 */
class RefusedSmallerStreamTest {

    /** The owner's title: 5.7 Mbit/s, a file the box plays as it is. */
    private val film = FakeTitle(
        width = 1920,
        height = 1080,
        videoCodec = "h264",
        videoProfile = "High",
        level = 41,
        bitDepth = 8,
        range = "SDR",
        audioCodec = "aac",
        channels = 2,
        videoBitrate = 4_700_000,
    )

    @Test
    fun `a server that refuses transcodes plays the file in Auto on a slow link, and says so`() {
        FakeLink(bitsPerSecond = SLOW_LINK).use { link ->
            val server = FakePlaybackServer(film, link.address, NO_TRANSCODES)
            val repository = server.auto()
            val source = open(repository)

            assertTrue("the link test read ${repository.budget()}", repository.budget()!! < film.bitrate)
            assertEquals(PlaybackMethod.DirectPlay, source.method)
            assertTrue("the player is told the server refused a smaller stream", source.smallerRefused)
            assertFalse("the file itself has no way back to take", source.fileFitsDevice)
            assertEquals(film.bitrate, source.streamBitrate)
            // It asked for a stream that fits, and that was refused.
            assertEquals(Choice.DirectPlay, server.choices.first())
            assertTrue("choices ${server.choices}", server.choices.drop(1).all { it is Choice.Refused })
            assertTrue(server.choices.size >= 2)
        }
    }

    @Test
    fun `the owner's numbers - 5_7 Mbit per s against 440 kbit per s - play the file`() {
        FakeLink(notFound = true).use { link ->
            val server = FakePlaybackServer(film, link.address, NO_TRANSCODES)
            val repository = server.auto()
            // A stall left the budget at 440 kbit/s (70 % of 630 kbit/s), and the link test fails: that budget counts.
            assertEquals(441_000, repository.lowerBudget(630_000))
            val source = open(repository)

            assertEquals(PlaybackMethod.DirectPlay, source.method)
            assertTrue(source.smallerRefused)
            assertTrue(server.asked.any { it.maxStreamingBitrate == 441_000 })
        }
    }

    @Test
    fun `a slow link on a server that transcodes still gets the smaller stream`() {
        FakeLink(bitsPerSecond = SLOW_LINK).use { link ->
            val server = FakePlaybackServer(film, link.address)
            val repository = server.auto()
            val source = open(repository)
            val budget = repository.budget()!!

            assertTrue("the link test read $budget", budget < film.bitrate)
            assertEquals(PlaybackMethod.Transcode, source.method)
            assertFalse(source.smallerRefused)
            assertTrue(server.asked.any { it.maxStreamingBitrate == budget })
            assertTrue("stream ${source.streamBitrate} on a budget of $budget", source.streamBitrate!! <= budget)
            assertTrue(source.fileFitsDevice)
            assertTrue(server.choices.none { it is Choice.Refused })
        }
    }

    @Test
    fun `a refused audio-only conversion and a refused smaller stream leave the file`() {
        // 2 Mbit/s against 1.82: the picture alone would fit, so the audio-only conversion is asked first.
        val small = film.copy(videoBitrate = 1_000_000)
        FakeLink(notFound = true).use { link ->
            val server = FakePlaybackServer(small, link.address, NO_TRANSCODES)
            val repository = server.auto()
            repository.lowerBudget(2_600_000)
            val source = open(repository)

            assertEquals(PlaybackMethod.DirectPlay, source.method)
            assertTrue(source.smallerRefused)
            assertFalse(source.videoCopied)
            assertEquals(3, server.choices.size)
            assertTrue("choices ${server.choices}", server.choices.drop(1).all { it is Choice.Refused })
        }
    }

    @Test
    fun `a refused stream for the display keeps the full-size one the server makes`() {
        // HDR10 on a display without HDR: the server converts the picture. It refuses to make it at the display's size.
        val film4k = FakeTitle(width = 3840, height = 2160)
        val server = FakePlaybackServer(
            film4k,
            refuses = { _, choice -> choice is Choice.EncodeVideo && (choice.maxWidth ?: Int.MAX_VALUE) < 3840 },
        )
        val repository = server.repository(device.copy(hdr = emptySet()), FULL_HD, FULL_HD)
        val source = open(repository)

        assertEquals(PlaybackMethod.Transcode, source.method)
        assertTrue("choices ${server.choices}", server.choices.last() is Choice.Refused)
        assertTrue(source.url, "MaxWidth=1920" !in source.url)
        assertFalse("the file does not play, the server's own transcode does", source.smallerRefused)
    }

    @Test
    fun `a refusal is remembered - the next titles and a restart ask for no smaller stream`() {
        FakeLink(notFound = true).use { link ->
            val server = FakePlaybackServer(film, link.address, NO_TRANSCODES)
            val repository = server.auto().slow()
            open(repository)
            assertEquals(2, server.asked.size)

            val again = asksOf(server) { open(repository) }
            assertEquals("the second title asks once, unlimited", listOf(AUTO), again.first)
            assertEquals(PlaybackMethod.DirectPlay, again.second.method)
            assertTrue("the player still says so", again.second.smallerRefused)

            // Kept in the app's settings, so a restart remembers it too.
            val restarted = asksOf(server) { open(server.auto().slow()) }
            assertEquals(listOf(AUTO), restarted.first)
            assertTrue(restarted.second.smallerRefused)
        }
    }

    @Test
    fun `a refusal counts for a day - after that the server is asked again`() {
        FakeLink(notFound = true).use { link ->
            val server = FakePlaybackServer(film, link.address, NO_TRANSCODES)
            val repository = server.auto().slow()
            open(repository)
            val dayLater = System.currentTimeMillis() + SmallerStreamRefusals.TTL_MS + 60_000
            repository.refusals.clock = { dayLater }

            val asks = asksOf(server) { open(repository) }
            assertEquals(2, asks.first.size)
            assertTrue("asked smaller again: ${asks.first}", asks.first.last()!! < film.bitrate)
            assertTrue(asks.second.smallerRefused)
        }
    }

    @Test
    fun `signing out forgets a refusal`() {
        FakeLink(notFound = true).use { link ->
            val server = FakePlaybackServer(film, link.address, NO_TRANSCODES)
            open(server.auto().slow())
            runBlocking { server.sessions.signOut() }

            // Signed in again (the same device id, so only the sign-out can have forgotten it): asked again.
            val asks = asksOf(server) { open(server.auto().slow()) }
            assertEquals(2, asks.first.size)
            assertTrue(asks.second.smallerRefused)
        }
    }

    @Test
    fun `another sign-in of the profile does not take over a refusal`() {
        FakeLink(notFound = true).use { link ->
            val server = FakePlaybackServer(film, link.address, NO_TRANSCODES)
            open(server.auto().slow())

            val asks = asksOf(server) { open(server.auto(deviceId = "d2").slow()) }
            assertEquals(2, asks.first.size)
        }
    }

    @Test
    fun `a link test whose answer is slow to start but fast after it holds nothing back`() {
        // The server waits 1.5 s before each test answers, then the link carries 50 Mbit/s: the wait is not the link.
        FakeLink(bitsPerSecond = 50_000_000, firstByteMs = 1_500).use { link ->
            val server = FakePlaybackServer(film, link.address)
            val repository = server.auto()
            val source = open(repository)

            assertTrue("the link test read ${repository.budget()}", repository.budget()!! > film.bitrate)
            assertEquals(PlaybackMethod.DirectPlay, source.method)
            assertEquals("asked only unlimited", listOf(AUTO), server.asked.map { it.maxStreamingBitrate })
            assertEquals("two downloads, the better one counting", 2, link.tests.get())
        }
    }

    @Test
    fun `a link test that gets no bytes before its read times out holds the stream to the floor`() {
        // Review: 400 kbit/s, and 5 s before the server's first byte (the read gives up after 4): no reading came, which
        // held nothing back, so the 5.7 Mbit/s file stalled. The floor counts, as before 1.0.1.
        FakeLink(bitsPerSecond = 400_000, firstByteMs = 5_000).use { link ->
            val server = FakePlaybackServer(film, link.address)
            val repository = server.auto()
            val source = open(repository)

            assertEquals(StreamBudget.FLOOR, repository.budget())
            assertEquals(PlaybackMethod.Transcode, source.method)
            assertTrue(server.asked.any { it.maxStreamingBitrate == StreamBudget.FLOOR })
        }
    }

    @Test
    fun `a slow link measured solidly in one download doesn't wait for a second`() {
        // Review: on a 400 kbit/s link the second download added about 4 s to every start.
        FakeLink(bitsPerSecond = 400_000).use { link ->
            val server = FakePlaybackServer(film, link.address)
            val repository = server.auto()
            val source = open(repository)

            assertEquals("one download", 1, link.tests.get())
            val budget = repository.budget()!!
            assertTrue("the link test read $budget", budget in 300_000..360_000)
            assertEquals(PlaybackMethod.Transcode, source.method)
        }
    }

    @Test
    fun `a remembered refusal plays the file without waiting for the link test`() {
        // Review: the open waited for the link test although the answer could only be the original.
        FakeLink(bitsPerSecond = 2_000_000, firstByteMs = 3_000).use { link ->
            val server = FakePlaybackServer(film, link.address, NO_TRANSCODES)
            val repository = server.auto()
            runBlocking { repository.refusals.remember("s1", FakePlaybackServer.USER.toString(), "d") }

            val started = System.nanoTime()
            val source = open(repository)
            val tookMs = (System.nanoTime() - started) / 1_000_000

            assertTrue("the open took $tookMs ms, the link test takes 3 s or more", tookMs < 1_500)
            assertEquals(PlaybackMethod.DirectPlay, source.method)
            assertEquals(listOf(AUTO), server.asked.map { it.maxStreamingBitrate })
        }
    }

    @Test
    fun `a remembered refusal with a link known to be slow says so without waiting`() {
        FakeLink(bitsPerSecond = 2_000_000, firstByteMs = 3_000).use { link ->
            val server = FakePlaybackServer(film, link.address, NO_TRANSCODES)
            val repository = server.auto().slow()
            runBlocking { repository.refusals.remember("s1", FakePlaybackServer.USER.toString(), "d") }

            val started = System.nanoTime()
            val source = open(repository)
            val tookMs = (System.nanoTime() - started) / 1_000_000

            assertTrue("the open took $tookMs ms", tookMs < 1_500)
            assertTrue(source.smallerRefused)
            assertEquals(listOf(AUTO), server.asked.map { it.maxStreamingBitrate })
        }
    }

    @Test
    fun `a link test the server answers with an error holds nothing back`() {
        FakeLink(notFound = true).use { link ->
            val server = FakePlaybackServer(film, link.address)
            val repository = server.auto()
            val source = open(repository)

            assertEquals(null, repository.budget())
            assertEquals(PlaybackMethod.DirectPlay, source.method)
            assertEquals(listOf(AUTO), server.asked.map { it.maxStreamingBitrate })
        }
    }

    @Test
    fun `a file within the bitrate setting gets no notice, even after a refusal`() {
        // Review: with the cap above the file, the memory of an earlier refusal said "won't make a smaller stream".
        val remuxed = film.copy(container = "asf")
        val server = FakePlaybackServer(remuxed)
        val repository = server.repository(device, UHD, FULL_HD, maxBitrate = MaxBitrate.M10)
        runBlocking { repository.refusals.remember("s1", FakePlaybackServer.USER.toString(), "d") }

        val asks = asksOf(server) { open(repository) }
        assertEquals(PlaybackMethod.DirectStream, asks.second.method)
        assertFalse("the file is within the cap: nothing smaller was wanted", asks.second.smallerRefused)
        assertEquals(listOf(AUTO, 10_000_000), asks.first)
    }

    @Test
    fun `a file above the bitrate setting keeps the original when the server refuses the cap`() {
        val remuxed = film.copy(container = "asf", videoBitrate = 14_000_000)
        val server = FakePlaybackServer(remuxed, refuses = refusesBelow(remuxed.bitrate))
        val repository = server.repository(device, UHD, FULL_HD, maxBitrate = MaxBitrate.M10)

        val first = asksOf(server) { open(repository) }
        assertEquals(PlaybackMethod.DirectStream, first.second.method)
        assertTrue(first.second.smallerRefused)
        assertEquals(listOf(AUTO, 10_000_000), first.first)
        val again = asksOf(server) { open(repository) }
        assertEquals("remembered: the cap isn't asked again", listOf(AUTO), again.first)
        assertTrue(again.second.smallerRefused)
    }

    @Test
    fun `a burned-in subtitle is not the file - no notice and nothing remembered`() {
        // The subtitle that shows is burned in: the picture is new whatever the link, so a refusal is no reason to say
        // "the original" or to skip the ask next time; the answer before the refusal stays, silently.
        val subtitled = film.copy(burnedInSubtitle = true)
        FakeLink(notFound = true).use { link ->
            val server = FakePlaybackServer(subtitled, link.address, refusesBelow(subtitled.bitrate))
            val repository = server.auto().slow()

            val first = asksOf(server) { open(repository) }
            assertEquals(listOf(AUTO, 441_000), first.first)
            assertFalse(first.second.smallerRefused)
            val again = asksOf(server) { open(repository) }
            assertEquals("nothing remembered: asked again", listOf(AUTO, 441_000), again.first)
        }
    }

    @Test
    fun `a 401 on the smaller ask still fails the open`() {
        // The fallback keeps an answer the server refused with an error code; a request it turned away is still an error.
        FakeLink(notFound = true).use { link ->
            val server = FakePlaybackServer(
                film,
                link.address,
                status = { if ((it.maxStreamingBitrate ?: AUTO) < AUTO) 401 else 200 },
            )
            val repository = server.auto().slow()

            assertThrows(Exception::class.java) { open(repository) }
            assertEquals(listOf(AUTO, 441_000), server.asked.map { it.maxStreamingBitrate })
            val again = asksOf(server) { runCatching { open(repository) } }
            assertEquals("nothing remembered", listOf(AUTO, 441_000), again.first)
        }
    }

    /** A server that makes the file as it is but refuses every smaller stream. */
    private fun refusesBelow(bitrate: Int): (org.jellyfin.sdk.model.api.PlaybackInfoDto, Choice) -> Boolean =
        { dto, _ -> (dto.maxStreamingBitrate ?: Int.MAX_VALUE) < bitrate }

    private fun FakePlaybackServer.auto(deviceId: String = "d") =
        repository(device, UHD, FULL_HD, maxBitrate = MaxBitrate.Auto, deviceId = deviceId)

    /** A stall left the budget at 441 kbit/s; the link test (a [FakeLink] answering 404) fails, so it counts. */
    private fun PlaybackRepository.slow() = apply { lowerBudget(630_000) }

    private fun open(repository: PlaybackRepository): PlaybackSource = runBlocking {
        repository.open(FakePlaybackServer.ITEM, 0, null, null)
    }

    /** The bitrates [server] was asked for while [block] ran, and what it gave. */
    private fun <T> asksOf(server: FakePlaybackServer, block: () -> T): Pair<List<Int?>, T> {
        val before = server.asked.size
        val result = block()
        return server.asked.toList().drop(before).map { it.maxStreamingBitrate } to result
    }

    private companion object {
        const val AUTO = 120_000_000

        /** A link of 2 Mbit/s: streams may use 1.7, less than the 5.7 the film takes. */
        const val SLOW_LINK = 2_000_000L

        val UHD = 3840 to 2160
        val FULL_HD = 1920 to 1080

        /** The 4K Google TV box of PanelPlaybackTest (testing/devices/google-tv-4k-box.json). */
        val device = DeviceCapabilities(
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
