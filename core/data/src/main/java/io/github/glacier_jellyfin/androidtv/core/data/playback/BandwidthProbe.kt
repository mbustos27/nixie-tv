package io.github.glacier_jellyfin.androidtv.core.data.playback

/**
 * The sizes and the arithmetic of the bandwidth test, which downloads blocks of the server's test file. The first block is
 * small, so a link of 0.5 Mbit/s answers within a few seconds; the second is sized by the first result, so it runs long
 * enough on a fast link to be worth something and not much longer than a few seconds on a slow one.
 *
 * The server's test answers with the next power of two of the size asked for (200,000 bytes come as 262,144, 5,000,000 as
 * 8,388,608), so the sizes here are powers of two: a block that is asked for is the block that arrives.
 *
 * Nixie (tv-1.0.1): each download is timed from its first byte (see [reading]), and there are always two downloads, the
 * better one counting. A box on home Wi-Fi logged 440 kbit/s for streams: its first block came in about 2 s, mostly the
 * wait for the server's answer, and a first block that slow was the whole test. One such reading made every title ask
 * for a transcode the server refused.
 */
internal object BandwidthProbe {
    /** The first block: 1 Mbit (128 KiB), a second on a link of 1 Mbit/s and 2.6 s on one of 0.4. */
    const val FIRST_BYTES = 131_072

    /** The smallest second block (256 KiB): a bigger sample than the first, which is mostly the connection speeding up. */
    const val SECOND_MIN_BYTES = 262_144

    /** The biggest block (4 MiB). */
    const val BIG_BYTES = 4_194_304

    /** A download is stopped after this long; what moved until then counts. */
    const val TIMEOUT_MS = 8_000L

    /** The second download stops after this long from its first byte: on a slow link that many seconds are reading enough. */
    const val SECOND_MAX_MS = 4_000L

    /** The second block should take about this long, in seconds. */
    const val TARGET_SECONDS = 3

    /**
     * A reading from the first byte needs at least this much after it, else the whole download is timed: a block that came
     * in one or two reads says nothing about the speed after them.
     */
    const val MIN_STEADY_BYTES = 16_384L

    private const val BITS_PER_BYTE = 8
    private const val NANOS_PER_SECOND = 1e9

    /**
     * The second block for a first result of [firstBps]: about [TARGET_SECONDS] of transfer (rounded down to a power of two),
     * between [SECOND_MIN_BYTES] and the big one. A fast link's first result is low (the connection was still speeding up), so its block comes out bigger than
     * it needs, which is what lets the second download reach the real speed.
     */
    fun secondBlock(firstBps: Double): Int = Integer.highestOneBit(
        (firstBps * TARGET_SECONDS / BITS_PER_BYTE).coerceIn(SECOND_MIN_BYTES.toDouble(), BIG_BYTES.toDouble()).toInt(),
    )

    /**
     * A first reading at or below this speed with [SOLID_STEADY_BYTES] behind it is a slow link, measured: 128 KiB in 2 s,
     * the old rule's line, now from the first byte.
     */
    const val SLOW_BPS = FIRST_BYTES * BITS_PER_BYTE / 2.0

    /** What a slow first reading needs after its first byte to stand without a second download (64 KiB). */
    const val SOLID_STEADY_BYTES = 65_536L

    /**
     * One download: its speed ([bitsPerSecond], see [reading]) and what came after its first read ([steadyBytes] in
     * [steadyNanos]).
     */
    data class Reading(
        val bitsPerSecond: Double,
        val steadyBytes: Long = 0,
        val steadyNanos: Long = 0,
    )

    /**
     * False when the [first] block already shows more than any stream may use ([StreamBudget.CEILING] after the
     * headroom), or a slow link with a solid sample ([SLOW_BPS], [SOLID_STEADY_BYTES]): a second download there costs
     * seconds of start-up and adds little. Any other low first reading is measured again before it may hold a stream
     * back, since it is mostly the connection warming up or the wait for the server's answer.
     */
    fun needsSecond(first: Reading): Boolean = when {
        first.bitsPerSecond * StreamBudget.HEADROOM_PERCENT / 100 >= StreamBudget.CEILING -> false
        first.steadyBytes >= SOLID_STEADY_BYTES && first.bitsPerSecond <= SLOW_BPS -> false
        else -> true
    }

    /** The speed of [bytes] moved in [nanos], in bit/s. */
    fun bitsPerSecond(bytes: Long, nanos: Long): Double {
        val elapsed = maxOf(nanos, 1L)
        return bytes * BITS_PER_BYTE * NANOS_PER_SECOND / elapsed
    }

    /**
     * One download's speed: the bytes after the first read ([steadyBytes]) over the time since it ([steadyNanos]), so the
     * connection's set-up and the server's wait before its first byte do not count as a slow link. When too little came
     * after the first read (see [MIN_STEADY_BYTES]), the whole download ([bytes] in [nanos], from the request) counts.
     */
    fun reading(
        bytes: Long,
        nanos: Long,
        steadyBytes: Long,
        steadyNanos: Long,
    ): Double = if (steadyBytes >= MIN_STEADY_BYTES && steadyNanos > 0) {
        bitsPerSecond(steadyBytes, steadyNanos)
    } else {
        bitsPerSecond(bytes, nanos)
    }
}
