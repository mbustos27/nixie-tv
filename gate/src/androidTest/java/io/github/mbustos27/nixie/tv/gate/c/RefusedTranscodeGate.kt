// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.c

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mbustos27.nixie.tv.gate.kit.Fixture
import io.github.mbustos27.nixie.tv.gate.kit.Gate
import io.github.mbustos27.nixie.tv.gate.kit.GateRule
import io.github.mbustos27.nixie.tv.gate.kit.Nixie
import io.github.mbustos27.nixie.tv.gate.kit.Remote
import io.github.mbustos27.nixie.tv.gate.kit.Screen
import io.github.mbustos27.nixie.tv.gate.kit.SignIn
import io.github.mbustos27.nixie.tv.gate.kit.Themes
import io.github.mbustos27.nixie.tv.gate.kit.check
import io.github.mbustos27.nixie.tv.gate.kit.fail
import io.github.mbustos27.nixie.tv.gate.kit.waitUntil
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Playback in "Auto" on a server where the user may not transcode (the TV's fake Jellyfin with --refuse-transcodes),
 * over a link its test reads as 400 kbit/s, too slow for the title: Nixie asks for a smaller stream, the server refuses
 * it, and the original plays, with the notice that says so. The title starts the way the TV's home screen starts one
 * from its Continue watching row.
 * [1.0.1 Blocker, the owner's box: "Server refused playback: NoCompatibleStream", nothing played in Auto.]
 */
@RunWith(AndroidJUnit4::class)
class RefusedTranscodeGate {
    @get:Rule val gate = GateRule()

    @After fun accountsChanged() = Nixie.forgetState()

    @Test fun auto_onAServerThatRefusesTranscodes_playsTheOriginal() {
        val server = Fixture.refusing
        val fake = RefusingServer(server.address)
        Nixie.clearData()
        Nixie.launch()
        SignIn.addServer(server)
        SignIn.withPassword(Fixture.refusingUser)
        SignIn.landHome()
        Themes.wear(Gate.theme)

        val ids = fake.ids()
        val movie = ids.getString("movie_id")
        val refusedBefore = fake.count("playbackinfo-refused")
        val streamsBefore = fake.count("stream")
        // Closed, then opened with the title, as the home screen's row does (HomeLaunch, "play").
        Nixie.forceStop()
        Gate.shell(
            "am start -a $OPEN_TITLE -n ${Gate.pkg}/${Nixie.ACTIVITY} --es server ${ids.getString("server_id")} " +
                "--es user ${ids.getString("user_id")} --es item $movie --ez play true",
        )

        check(waitUntil(NOTICE_MS) { Screen.shows(NOTICE) || Screen.shows(FAILED) }) {
            "no notice and no failure ${NOTICE_MS / 1000} s after starting the title in Auto: ${Screen.summary()}"
        }
        check(!Screen.shows(FAILED)) { "playback failed in Auto on a server that refuses transcodes: ${Screen.summary()}" }
        check(fake.count("playbackinfo-refused") > refusedBefore) {
            "the notice showed, but the server was never asked for a smaller stream (the link test read it as fast enough)"
        }
        check(waitUntil(PLAY_MS) { fake.playing(movie) && fake.count("stream") > streamsBefore }) {
            "the original never started: no stream request and no 'Playing' report within ${PLAY_MS / 1000} s; " +
                Screen.summary()
        }
        check(!Screen.shows(FAILED)) { "playback failed after it started: ${Screen.summary()}" }
        Remote.back()
    }

    /** The fake's own harness API (127.0.0.1 on the PC, which the emulator reaches at 10.0.2.2): no sign-in needed. */
    private class RefusingServer(private val address: String) {
        fun ids(): JSONObject = get("/__harness/tv")

        fun count(route: String): Int = get("/__harness/summary").optJSONObject("by_route")?.optInt(route, 0) ?: 0

        /** The server took a "Playing" report for [itemId] (the player started), with no failed stop after it. */
        fun playing(itemId: String): Boolean {
            val reports = get("/__harness/summary").optJSONArray("reports") ?: return false
            val id = itemId.replace("-", "").lowercase()
            val mine = (0 until reports.length()).map { reports.getJSONObject(it) }
                .filter { it.optString("item").replace("-", "").lowercase() == id }
            return mine.any { it.optString("kind") == "Playing" } && mine.none { it.optBoolean("failed", false) }
        }

        private fun get(path: String): JSONObject {
            val connection = (URL(address + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            return try {
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } catch (e: java.io.IOException) {
                fail("the refusing test server at $address did not answer $path (${e.javaClass.simpleName})")
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val OPEN_TITLE = "io.github.glacier_jellyfin.androidtv.OPEN_TITLE"

        /** The notice, without its apostrophe ("won’t"). */
        const val NOTICE = "make a smaller stream"
        const val FAILED = "Playback failed"

        /** Start-up, the link test at 400 kbit/s (about 7 s), the refusal and the notice. */
        const val NOTICE_MS = 60_000L
        const val PLAY_MS = 45_000L
        const val TIMEOUT_MS = 10_000
    }
}
