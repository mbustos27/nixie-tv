// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import io.github.glacier_jellyfin.androidtv.diagnostics.Anonymizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tv-1.0-privacy, the re-review of the shared log's anonymiser: what the first round's rules took that they should not
 * (a minified build's stack frames, ordinary words, technical values) and the passes it made. Made-up values only.
 */
class LogReReviewTest {

    @Test
    fun `a minified build's frames and exceptions stay, a host where a line names one goes`() {
        val log = """
            W/Player: Playback failed
            Caused by: t3.ai: Connection reset
            	at t3.c.ai(Unknown Source:12)
            	at io.github.Foo${'$'}bar${'$'}1.invoke(r8-map-id-ab12:3)
            	at d2.ab.cd${'$'}e.f(SourceFile:40)
            I/Media3: d2.ab.cd${'$'}e released
        """.trimIndent()
        val resolve = "W/Live: java.net.UnknownHostException: Unable to resolve host \"nas.example.ai\": No address"

        val anonymizer = Anonymizer(servers = emptyList(), users = emptyList())

        assertEquals(log, anonymizer.apply(log))
        assertEquals(
            "W/Live: java.net.UnknownHostException: Unable to resolve host \"host-1\": No address",
            anonymizer.apply(resolve),
        )
    }

    @Test
    fun `short, numeric and the app's own names leave timestamps, numbers and words alone`() {
        // A profile called "10", one called "TV", a server whose host is "jellyfin" and whose name is "Media".
        val anonymizer = Anonymizer(servers = listOf("http://jellyfin:8096" to "Media"), users = listOf("10", "TV"))
        val free = """
            2026-11-10 10:04:10.100 I/App: Started Nixie for TV 1.0.0 on GOOGLE_TV, Live TV in 10 ms
            I/Home: Jellyfin 10.11.0 answered; Media3 audio; Jellyfin's Media
        """.trimIndent()

        assertEquals(free, anonymizer.apply(free))
        assertEquals(
            "I/Account: user=user-2 signed in; profile: user-1; server-1/address-1; http://server-1:8096/System/Info",
            anonymizer.apply(
                "I/Account: user=TV signed in; profile: 10; jellyfin/203.0.113.7; http://jellyfin:8096/System/Info",
            ),
        )
    }

    @Test
    fun `a host called jellyfin before its address is a host, not a version`() {
        val out = Anonymizer(servers = emptyList(), users = emptyList())
            .apply("failed to connect to jellyfin/203.0.113.7 (port 8096); Jellyfin 10.10.3.0")

        assertEquals("failed to connect to host-1/address-1 (port 8096); Jellyfin 10.10.3.0", out)
    }

    @Test
    fun `technical values and public services stay, ids and tokens go`() {
        val log = """
            I/Player: https://jf.example.org/Videos/0a1b/master.m3u8?MediaSourceId=0a1b2c&VideoCodec=h264,hevc&AudioCodec=aac&TranscodeReasons=ContainerBitrateExceedsLimit&StartTimeTicks=6000000000&MaxStreamingBitrate=5100000&SegmentContainer=ts&api_key=0f1e2d&PlaySessionId=5f2a
            I/Update: GET https://api.github.com/repos/example/nixie/releases?per_page=5
            I/Trailer: https://www.youtube.com/watch?v=abcdEFGH123
        """.trimIndent()

        val out = Anonymizer(servers = listOf("https://jf.example.org" to "Den Box"), users = emptyList()).apply(log)

        assertTrue(
            out,
            "https://server-1/Videos/0a1b/master.m3u8?MediaSourceId=…&VideoCodec=h264,hevc&AudioCodec=aac&" +
                "TranscodeReasons=ContainerBitrateExceedsLimit&StartTimeTicks=6000000000&MaxStreamingBitrate=5100000&" +
                "SegmentContainer=ts&api_key=…&PlaySessionId=…" in out,
        )
        assertTrue(out, "https://api.github.com/repos/example/nixie/releases?per_page=…" in out)
        assertTrue(out, "https://www.youtube.com/watch?v=…" in out)
    }

    @Test
    fun `hundreds of names are a few passes over the log, not one each`() {
        val servers = List(200) { "https://box$it.example.org" to "Lake House $it" }
        val users = List(200) { "Marguerite$it" }

        val names = NamesScrub(servers.map { (address, name) -> Anonymizer.hostOf(address) to name }, users)

        assertTrue("${names.passes} passes", names.passes <= 4)
        assertEquals(
            "server-8 server-8-name user-200",
            names.apply("box7.example.org Lake House 7 Marguerite199"),
        )
    }

    // The last Minor: OkHttp's own DNS messages, and a host in JSON.
    @Test
    fun `OkHttp's DNS messages and a JSON host lose the host`() {
        val log = """
            W/Http: java.net.UnknownHostException: Dns.SYSTEM returned no addresses for media.example.pl
            W/Http: java.net.UnknownHostException: dns lookup of nas.fritz.box failed
            D/Discovery: {"host":"den.example.net","port":8096}
        """.trimIndent()

        val out = Anonymizer(servers = emptyList(), users = emptyList()).apply(log)

        assertEquals(
            """
            W/Http: java.net.UnknownHostException: Dns.SYSTEM returned no addresses for host-1
            W/Http: java.net.UnknownHostException: dns lookup of host-2 failed
            D/Discovery: {"host":"host-3","port":8096}
            """.trimIndent(),
            out,
        )
    }
}
