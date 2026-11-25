// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.kit

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * The phone's part of Quick Connect, against the throwaway test servers only: signs in as the user with its own
 * device id (never the app's), approves the code shown on the TV, and signs that session out again.
 */
object Jellyfin {
    private const val APPROVER_DEVICE = "nixie-gate-approver"
    private const val TIMEOUT_MS = 20_000

    /** Approves [code] for [user]; fails with the server's answer if it does not take it. */
    fun approveQuickConnect(user: GateUser, code: String) {
        val token = signIn(user)
        try {
            val status = request("POST", "${user.server.address}/QuickConnect/Authorize?code=$code", token, null).first
            check(
                status in 200..299,
            ) { "the test server refused Quick Connect code $code for ${user.name} (HTTP $status)" }
        } finally {
            runCatching { request("POST", "${user.server.address}/Sessions/Logout", token, null) }
        }
    }

    /**
     * Puts a resume point (10 min, played now) back on the film [title] for [user]: the fixture's repair after a
     * check that removed it from Continue watching stopped before its Undo.
     */
    fun restoreResume(user: GateUser, title: String) {
        val token = signIn(user)
        try {
            val me = JSONObject(request("GET", "${user.server.address}/Users/Me", token, null).second).getString("Id")
            val query = java.net.URLEncoder.encode(title, "UTF-8")
            val found = JSONObject(
                request(
                    "GET",
                    "${user.server.address}/Items?userId=$me&searchTerm=$query&includeItemTypes=Movie&recursive=true&limit=10",
                    token,
                    null,
                ).second,
            ).getJSONArray("Items")
            val id = (0 until found.length()).map {
                found.getJSONObject(
                    it,
                )
            }.firstOrNull { it.getString("Name") == title }
                ?.getString("Id") ?: return
            val now = java.time.Instant.now().toString()
            val body = JSONObject().put(
                "PlaybackPositionTicks",
                RESUME_TICKS,
            ).put("LastPlayedDate", now).put("Played", false)
            request("POST", "${user.server.address}/UserItems/$id/UserData?userId=$me", token, body.toString())
        } finally {
            runCatching { request("POST", "${user.server.address}/Sessions/Logout", token, null) }
        }
    }

    private const val RESUME_TICKS = 6_000_000_000L

    private fun signIn(user: GateUser): String {
        val body = JSONObject().put("Username", user.name).put("Pw", user.password).toString()
        val (status, answer) = request("POST", "${user.server.address}/Users/AuthenticateByName", null, body)
        check(status in 200..299) { "the test server did not sign ${user.name} in for the approval (HTTP $status)" }
        return JSONObject(answer).getString("AccessToken")
    }

    private fun request(
        method: String,
        url: String,
        token: String?,
        body: String?,
    ): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            val auth = StringBuilder(
                "MediaBrowser Client=\"Nixie gate\", Device=\"gate\", DeviceId=\"$APPROVER_DEVICE\", Version=\"1\"",
            )
            if (token != null) auth.append(", Token=\"").append(token).append('"')
            connection.setRequestProperty("Authorization", auth.toString())
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            } else if (method == "POST") {
                connection.setFixedLengthStreamingMode(0)
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return status to text
        } finally {
            connection.disconnect()
        }
    }
}
