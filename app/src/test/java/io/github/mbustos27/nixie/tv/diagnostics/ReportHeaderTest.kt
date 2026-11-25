// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.DeviceCapabilities
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.MediaCapabilityDetector
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.diagnostics.DeviceReport
import io.github.glacier_jellyfin.androidtv.diagnostics.Diagnostics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import org.robolectric.shadows.ShadowBuild

/**
 * tv-1.0-privacy (the privacy review): the shared report's device summary skipped the anonymiser and named the audio
 * outputs as their owners named them (`Bluetooth A2DP "Jane's AirPods"`). Made-up names and addresses only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReportHeaderTest {

    @Test
    fun `an audio output is named by its kind, never by the name its owner gave it`() {
        val airpods = mockk<AudioDeviceInfo> {
            every { type } returns AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            every { productName } returns "Jane's AirPods"
            every { encodings } returns intArrayOf(AudioFormat.ENCODING_PCM_16BIT)
            every { channelCounts } returns intArrayOf(2)
        }
        val manager = mockk<AudioManager> { every { getDevices(any()) } returns arrayOf(airpods) }
        val context = mockk<Context> { every { getSystemService(AudioManager::class.java) } returns manager }

        val lines = DeviceReport.audioOutputs(context)

        assertEquals(listOf("  BLUETOOTH_A2DP (a Bluetooth audio device): PCM_16BIT, channels 2"), lines)
        assertFalse(lines.joinToString(), lines.any { "Jane" in it || "AirPods" in it })
    }

    @Test
    fun `the device summary goes through the anonymiser with the log`() {
        val app = RuntimeEnvironment.getApplication()
        ShadowBuild.setModel("Box at 203.0.113.70, host=den.example.org")
        val accounts = mockk<AccountRepository> {
            coEvery { current() } returns AccountState(
                servers = listOf(StoredServer("s1", "Home Media", "https://jf.example.org", "10.11.0", lastUsedAt = 0)),
            )
        }
        val detector = mockk<MediaCapabilityDetector> {
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
        }
        val sessions = mockk<SessionManager> { every { session } returns MutableStateFlow(null) }
        Log.install(app)
        Log.clear()
        Log.i("ReportHeaderTest", "connected to 203.0.113.70")

        val report = runBlocking { Diagnostics(app, sessions, accounts, detector).fullReport() }

        assertTrue(report, report.startsWith("Nixie for TV "))
        assertFalse(report, "203.0.113.70" in report || "den.example.org" in report)
        // One anonymiser for both: the address keeps its number from the summary into the log.
        assertTrue(report, "Box at address-1, host=host-1" in report && "connected to address-1" in report)
    }
}
