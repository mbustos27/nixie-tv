// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import io.github.glacier_jellyfin.androidtv.diagnostics.Anonymizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * tv-1.0-fixes (the owner's TV log, 2026-11-25): the anonymised log, meant for a public issue, kept the server's public
 * address in a timeout's stack trace. Every address and host name goes, wherever it stands; loopback and the
 * placeholders stay. Addresses from the documentation ranges only (RFC 5737, RFC 3849).
 */
class NetworkScrubTest {

    private fun anonymizer() = Anonymizer(
        servers = listOf("https://jf.example.org" to "Home Media", "http://[2001:db8::7]:8096" to "Lab"),
        users = listOf("ana"),
    )

    @Test
    fun `the timeout's message from the owner's log keeps neither address`() {
        val line = "Caused by: java.net.SocketTimeoutException: failed to connect to jf.example.org/203.0.113.7 " +
            "(port 443) from /198.51.100.20 (port 50908) after 6000ms"

        val out = anonymizer().apply(line)

        assertEquals(
            "Caused by: java.net.SocketTimeoutException: failed to connect to server-1/address-1 (port 443) " +
                "from /address-2 (port 50908) after 6000ms",
            out,
        )
    }

    @Test
    fun `IPv4 and IPv6 anywhere in a line, the same address under the same name`() {
        val log = """
            I/LogServer: Log downloaded by 198.51.100.20
            W/Home: Loading failed
            java.net.ConnectException: failed to connect to /2001:db8:0:0:0:0:0:14%wlan0 (port 8096) from /2001:db8::99 (port 4)
            	at libcore.io.IoBridge.connect(IoBridge.java:179)
            Caused by: android.system.ErrnoException: connect failed: ECONNREFUSED to [2001:db8:1::5]:8096 or ::ffff:203.0.113.9.
            I/LogServer: Refused GET from 198.51.100.20
        """.trimIndent()

        val out = anonymizer().apply(log)

        for (address in listOf("198.51.100.20", "2001:db8", "db8::99", "203.0.113.9", "1::5", "wlan0")) {
            assertFalse("$address left in:\n$out", address in out)
        }
        assertEquals(2, Regex("""by address-1\b|from address-1\b""").findAll(out).count())
        assertEquals(true, "connect to /address-2 (port 8096) from /address-3 (port 4)" in out)
        assertEquals(true, "ECONNREFUSED to [address-4]:8096 or address-5." in out)
        assertEquals(true, "at libcore.io.IoBridge.connect(IoBridge.java:179)" in out)
    }

    @Test
    fun `hosts the TV does not know, in URLs, messages and prose`() {
        val log = """
            D/Player: Redirected to https://cdn.example.net:8443/Videos/1/stream and http://user:secret@nas:8096/web
            W/Live: java.net.UnknownHostException: Unable to resolve host "media.example.com": No address associated with hostname
            W/Tls: javax.net.ssl.SSLPeerUnverifiedException: Hostname media.example.com not verified
            W/Http: java.net.ConnectException: Failed to connect to nas.local:8096
            D/OkHttp: Host: media.example.com
            I/Discovery: answered from host=jellyfin.home.arpa and server=cdn.example.net
        """.trimIndent()

        val out = anonymizer().apply(log)

        for (name in listOf("example.net", "example.com", "nas", "secret", "user:", "home.arpa")) {
            assertFalse("$name left in:\n$out", name in out)
        }
        assertEquals(true, "https://host-1:8443/Videos/1/stream and http://host-2:8096/web" in out)
        assertEquals(true, "resolve host \"host-3\"" in out)
        assertEquals(true, "Hostname host-3 not verified" in out)
        assertEquals(true, "Failed to connect to host-4:8096" in out)
        assertEquals(true, "Host: host-3" in out)
        assertEquals(true, "answered from host=host-5 and server=host-1" in out)
    }

    @Test
    fun `a known IPv6 server becomes its placeholder, and an address next to a known one is another`() {
        val anonymizer = Anonymizer(
            servers = listOf("http://[2001:db8::7]:8096" to "Lab", "http://203.0.113.1:8096" to "Den"),
            users = emptyList(),
        )

        val line = "http://[2001:db8::7]:8096/System/Info, http://203.0.113.1:8096, http://203.0.113.15:8096"
        val out = anonymizer.apply(line)

        assertEquals("http://[server-1]:8096/System/Info, http://server-2:8096, http://address-1:8096", out)
    }

    @Test
    fun `loopback, placeholders and the code's own names stay`() {
        val log = """
            I/Player: Seek points of https://server-1/Videos/02c30305-9dd0-0403-0700-000000000000/stream: known
            I/Proxy: http://127.0.0.1:8496 and http://localhost:8096 and http://[::1]:8096 and ::1
            I/Player: Video decoder: c2.amlogic.avc.decoder at 2026-11-22 21:04:35.328
            	at okhttp3.internal.connection.ConnectPlan.connectSocket(ConnectPlan.kt:284)
            	at io.github.glacier_jellyfin.androidtv.home.SpotlightKt.Spotlight(Spotlight.kt:145)
            I/App: Started io.github.mbustos27.nixie.tv, Jellyfin 12.4.0, AndroidXMedia3/1.11.1, libffmpegJNI.so
            I/Kotlin: Foo::bar, ::class, content://io.github.mbustos27.nixie.tv.debug.adblog/log
            I/Home: user-1 on server-1-name
        """.trimIndent()

        assertEquals(log, anonymizer().apply(log))
    }
}
