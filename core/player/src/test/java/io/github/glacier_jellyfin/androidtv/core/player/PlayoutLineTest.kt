package io.github.glacier_jellyfin.androidtv.core.player

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nixie (tv-1.0-fixes, the owner's TV log of 2026-11-25): every playback logged "playout start 1795504692386 ms from
 * now (system time)", about 57 years: Media3's playout start is wall-clock time, and the line took the time since boot
 * from it.
 */
class PlayoutLineTest {

    @Test
    fun `a sound that started 300 ms ago reads 300 ms ago, not years`() {
        val started = System.currentTimeMillis() - 300

        val line = GlacierPlayback.playoutLine(started)

        val ms = Regex("""playout started (-?\d+) ms ago""").find(line)?.groupValues?.get(1)?.toLong()
        assertTrue(line, ms != null && ms in 300L..60_000L)
    }
}
