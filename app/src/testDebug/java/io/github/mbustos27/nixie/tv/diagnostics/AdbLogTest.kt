// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import android.app.Application
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.DeviceCapabilities
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.MediaCapabilityDetector
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.diagnostics.Diagnostics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Stream D (tv-log-pull): the file the debug build writes for `tools/pull-tv-log.ps1` is the anonymised report
 * Download log serves. The log goes through the app's real file log (tokens redacted as it is written) and the real
 * Diagnostics (the device summary on top, server addresses and names and user names replaced), with the profiles of
 * AnonymizerTest's fixtures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdbLogTest {

    private val app = RuntimeEnvironment.getApplication()

    private val accounts = mockk<AccountRepository> {
        coEvery { current() } returns AccountState(
            servers = listOf(
                StoredServer("s1", "Home Media", "https://jf.example.org", "10.11.0", lastUsedAt = 0),
                StoredServer("s2", "Jellyfin", "http://192.168.1.20:8096", "10.11.0", lastUsedAt = 0),
            ),
            users = listOf("Jens", "jens", "Al", "example").mapIndexed { index, name ->
                StoredUser("s1", "u$index", name, primaryImageTag = null, accessToken = "token$index", lastUsedAt = 0)
            },
        )
    }

    private val diagnostics = Diagnostics(
        app,
        mockk<SessionManager> { every { session } returns MutableStateFlow(null) },
        accounts,
        mockk<MediaCapabilityDetector> {
            every { capabilities } returns DeviceCapabilities(
                h264 = null,
                hevc = null,
                av1 = null,
                vp9 = null,
                otherVideoCodecs = emptySet(),
                audioCodecs = setOf("aac"),
                maxAudioChannels = 2,
                hdr = emptySet(),
            )
            every { iec61937() } returns false
        },
    )

    @Test
    fun `the file for adb is the anonymised report, with nothing that names the server, the user or a token`() {
        Log.install(app)
        Log.clear()
        Log.i(TAG, "GET https://JF.example.org/Users/1f2e?api_key=$TOKEN failed; fallback http://192.168.1.20:8096")
        Log.i(TAG, "Signed in as jens on Home Media; Jensen stays")
        Log.w(TAG, "Authorization: MediaBrowser Client=\"Nixie\", Token=\"$TOKEN\"")

        val file = runBlocking { AdbLogReceiver.write(app.filesDir, diagnostics) }

        assertEquals(File(app.filesDir, "diagnostics/nixie-tv-log.txt"), file)
        assertEquals(listOf("nixie-tv-log.txt"), file.parentFile?.list()?.toList())
        val text = file.readText()
        // The report Download log serves: the device summary first, then the log.
        assertTrue(text, text.startsWith("Nixie for TV "))
        // tv-1.0-fixes: other addresses and host names too (NetworkScrub).
        val anonymised = "Anonymised: server addresses and names, user names, other addresses and host names, " +
            "access tokens"
        assertTrue(text, anonymised in text)
        // tv-1.0-privacy: a query string keeps only its parameters' names (the token was hidden as it was written).
        val request = "GET https://server-1/Users/1f2e?api_key=… failed; fallback http://server-2:8096"
        assertTrue(text, request in text)
        assertTrue(text, "Signed in as user-1 on server-1-name; Jensen stays" in text)
        for (secret in listOf("example.org", "192.168.1.20", "Home Media", TOKEN)) {
            assertFalse("$secret in\n$text", text.contains(secret, ignoreCase = true))
        }
        assertFalse(text, Regex("\\bjens\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
    }

    private companion object {
        const val TAG = "AdbLogTest"
        const val TOKEN = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
    }
}
