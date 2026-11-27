package io.github.glacier_jellyfin.androidtv.core.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandwidthProbeTest {

    @Test
    fun `the first block ends within seconds on a link of 400 kbit per second`() {
        val seconds = BandwidthProbe.FIRST_BYTES * 8 / 400_000.0
        assertTrue(seconds < BandwidthProbe.TIMEOUT_MS / 1000.0 / 1.5)
    }

    @Test
    fun `a slow first block is measured again, never the whole test`() {
        // 1.0.1: a first block of 2 s (524,288 bit/s) was the whole test, and read 440 kbit/s for streams on home Wi-Fi.
        assertEquals(true, BandwidthProbe.needsSecond(524_288.0))
        assertEquals(true, BandwidthProbe.needsSecond(400_000.0))
        assertEquals(true, BandwidthProbe.needsSecond(0.0))
        assertEquals(true, BandwidthProbe.needsSecond(50_000_000.0))
        // Only a first block that already shows more than any stream may use is enough.
        assertEquals(false, BandwidthProbe.needsSecond(200_000_000.0))
    }

    @Test
    fun `a slow link gets a second block of about three seconds, at least twice the first`() {
        assertEquals(BandwidthProbe.SECOND_MIN_BYTES, BandwidthProbe.secondBlock(400_000.0))
        assertEquals(BandwidthProbe.SECOND_MIN_BYTES, BandwidthProbe.secondBlock(0.0))
        assertEquals(262_144, BandwidthProbe.secondBlock(1_000_000.0))
        assertEquals(524_288, BandwidthProbe.secondBlock(2_000_000.0))
        assertTrue(BandwidthProbe.SECOND_MIN_BYTES >= 2 * BandwidthProbe.FIRST_BYTES)
    }

    @Test
    fun `the second download on a slow link ends within seconds of its first byte`() {
        // 256 KiB at 400 kbit/s would take 5.2 s; what moved in SECOND_MAX_MS is reading enough.
        assertTrue(BandwidthProbe.SECOND_MAX_MS < BandwidthProbe.TIMEOUT_MS)
        assertTrue(BandwidthProbe.SECOND_MAX_MS <= 4_000)
    }

    @Test
    fun `the wait for the server's first byte does not count as a slow link`() {
        // 128 KiB, the first 8 KiB after 2 s, the rest 20 ms later: 48 Mbit/s, not the 0.52 of the whole download.
        val reading = BandwidthProbe.reading(
            bytes = 131_072,
            nanos = 2_020_000_000,
            steadyBytes = 131_072 - 8_192,
            steadyNanos = 20_000_000,
        )
        assertEquals(49_152_000.0, reading, 1.0)
        assertTrue(BandwidthProbe.bitsPerSecond(131_072, 2_020_000_000) < 600_000)
    }

    @Test
    fun `a download with too little after its first read is timed as a whole`() {
        // One read of 8 KiB and almost nothing after it says nothing about the speed after it.
        val reading = BandwidthProbe.reading(bytes = 9_000, nanos = 1_000_000_000, steadyBytes = 808, steadyNanos = 1_000)
        assertEquals(72_000.0, reading, 0.5)
    }

    @Test
    fun `a fast first result gets a big second block, at most the big one`() {
        assertEquals(1_048_576, BandwidthProbe.secondBlock(5_000_000.0))
        assertEquals(2_097_152, BandwidthProbe.secondBlock(10_000_000.0))
        assertEquals(BandwidthProbe.BIG_BYTES, BandwidthProbe.secondBlock(100_000_000.0))
    }

    @Test
    fun `every block is a power of two, which the server sends as asked`() {
        listOf(0.1, 400_000.0, 782_000.0, 3_000_000.0, 50_000_000.0).forEach {
            val block = BandwidthProbe.secondBlock(it)
            assertEquals(block, Integer.highestOneBit(block))
        }
        assertEquals(BandwidthProbe.FIRST_BYTES, Integer.highestOneBit(BandwidthProbe.FIRST_BYTES))
        assertEquals(BandwidthProbe.SECOND_MIN_BYTES, Integer.highestOneBit(BandwidthProbe.SECOND_MIN_BYTES))
    }

    @Test
    fun `bytes that moved before a timeout give the speed they showed`() {
        // 200 kB in 4 s are 0.4 Mbit/s, not a guess from the block that was asked for.
        assertEquals(400_000.0, BandwidthProbe.bitsPerSecond(200_000, 4_000_000_000), 0.5)
        assertEquals(1_000_000.0, BandwidthProbe.bitsPerSecond(125_000, 1_000_000_000), 0.5)
    }
}
