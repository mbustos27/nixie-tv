// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.diagnostics.Anonymizer
import io.github.mbustos27.nixie.tv.data.NamesSeen
import io.github.mbustos27.nixie.tv.data.SeenServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tv-1.0-privacy (the privacy review of the shared log, meant for a public issue): what the anonymiser still let
 * through. Made-up names and addresses only (documentation ranges, example domains).
 */
class LogPrivacyTest {

    private fun anonymizer() = Anonymizer(
        servers = listOf("https://jf.example.org" to "Home Media"),
        users = listOf("Ana"),
    )

    private fun assertGone(out: String, vararg values: String) {
        for (value in values) assertFalse("\"$value\" left in:\n$out", out.contains(value, ignoreCase = true))
    }

    @Test
    fun `OkHttp's certificate message keeps no pin, distinguished name or alternative name`() {
        val log = """
            W/Http: javax.net.ssl.SSLPeerUnverifiedException: Hostname media.example.com not verified:
                certificate: sha256/q1W2e3R4t5Y6u7I8o9P0aSdFgHjKlZxCvBnM12345678=
                DN: CN=media.example.com,OU=Lake House Lab,O=Jane Doe\, Esq.,L=Harrowgate,C=GB
                subjectAltNames: [*.harbourlights.example.net, mynas, 203.0.113.44]
        """.trimIndent()

        val out = anonymizer().apply(log)

        assertGone(out, "q1W2e3R4", "Lake House", "Jane", "Esq", "Harrowgate", "harbourlights", "mynas", "203.0.113.44")
        assertGone(out, "media.example.com")
        assertTrue(out, "certificate: sha256/<hidden>" in out)
        assertTrue(out, "subjectAltNames: [*.host-1, host-2, address-1]" in out)
        assertTrue(out, "Hostname host-3 not verified" in out)
        assertTrue(out, "DN: CN=<hidden>,OU=<hidden>,O=<hidden>,L=<hidden>,C=<hidden>" in out)
    }

    @Test
    fun `a timed-out search keeps the parameters' names, not what was typed`() {
        val log = """
            W/Search: Search failed
            java.net.SocketTimeoutException: timeout for https://jf.example.org/Items?searchTerm=lighthouse%20keeper&IncludeItemTypes=Movie&Limit=24
            W/Search: Seerr search failed
            io.ktor.client.plugins.HttpRequestTimeoutException: Request timeout has expired [url=https://seerr.example.org/api/v1/search?query=harbour+lights&page=1, request_timeout=10000 ms]
            I/IntroOnline: GET https://api.example.com/v4/media?tmdb_id=990001&season=1&episode=2
        """.trimIndent()

        val out = anonymizer().apply(log)

        assertGone(out, "lighthouse", "keeper", "harbour", "990001")
        // tv-1.0-privacy re-review: technical parameters keep their values.
        assertTrue(out, "https://server-1/Items?searchTerm=…&IncludeItemTypes=Movie&Limit=24" in out)
        assertTrue(out, "https://host-1/api/v1/search?query=…&page=1, request_timeout=10000 ms]" in out)
        assertTrue(out, "?tmdb_id=…&season=…&episode=…" in out)
    }

    @Test
    fun `names under any suffix, names with a port, e-mail addresses, passwords and MAC addresses go`() {
        val log = """
            I/Discovery: answered from host=nas.fritz.box and https://media.example.pl/
            W/Http: java.net.ConnectException: nas:8096 refused; then den-pc:8920
            I/Account: signed in as jane.doe@example.org, someone@nas
            D/Player: http://jane:pa/ss@nas:8096/web
            I/Net: wlan aa:bb:cc:dd:ee:01, ethernet AA-BB-CC-DD-EE-02
        """.trimIndent()

        val out = anonymizer().apply(log)

        assertGone(out, "fritz", "example.pl", "nas", "den-pc", "jane", "someone", "pa/ss", "aa:bb", "AA-BB")
        assertTrue(out, "answered from host=host-3 and https://host-1/" in out)
        assertTrue(out, "host-2:8096 refused; then host-4:8920" in out)
        assertTrue(out, "signed in as email-1, email-2" in out)
        assertTrue(out, "http://host-2:8096/web" in out)
        assertTrue(out, "wlan mac-1, ethernet mac-2" in out)
    }

    @Test
    fun `versions and code stay, and an address is one number however it is written`() {
        val log = """
            I/App: Server: Jellyfin 10.10.3.0, Version="10.10.3.0", version: 10.10.3.0
            I/Kotlin: Bad::add, Cafe::face
            W/Net: to ::ffff:203.0.113.9, then 203.0.113.9, then 2001:db8:0:0:0:0:0:14 and 2001:db8::14
            	at io.github.Foo.bar(Unknown Source:19)
            	at io.github.Foo.baz(Main.kt:47)
            D/EventLogger:     [X] Track:12, id=1:2, at 21:04:35
        """.trimIndent()

        val out = anonymizer().apply(log)

        assertTrue(out, "Jellyfin 10.10.3.0, Version=\"10.10.3.0\", version: 10.10.3.0" in out)
        assertTrue(out, "Bad::add, Cafe::face" in out)
        assertTrue(out, "to address-1, then address-1, then address-2 and address-2" in out)
        assertTrue(out, "(Unknown Source:19)" in out && "(Main.kt:47)" in out && "Track:12, id=1:2, at 21:04:35" in out)
    }

    @Test
    fun `names of servers and profiles removed before, and short names, go from older lines`() {
        val state = AccountState(
            servers = listOf(StoredServer("s1", "Home Media", "https://jf.example.org", null, 0)),
            users = listOf(StoredUser("s1", "u1", "Ana", null, "token", lastUsedAt = 0)),
            nixieSeen = NamesSeen(
                servers = listOf(SeenServer("http://lakehouse.example.net:8096", "Lakehouse")),
                users = listOf("Marguerite", "Bo"),
            ),
        )
        // Short names in name fields only (tv-1.0-privacy re-review).
        val old = "Signed in as Marguerite on Lakehouse (http://lakehouse.example.net:8096); user=Bo, profile: Ana; Bob"

        val out = Anonymizer.of(state).apply(old)

        assertGone(out, "Marguerite", "Lakehouse", "lakehouse.example.net", "Ana")
        assertEquals(
            "Signed in as user-2 on server-2-name (http://server-2:8096); user=user-3, profile: user-1; Bob",
            out,
        )
    }
}
