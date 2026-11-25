// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.b

import io.github.mbustos27.nixie.tv.gate.kit.GateUser
import io.github.mbustos27.nixie.tv.gate.kit.check
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the throwaway test server holds, read as the checks' user with a device id of its own (never the app's): the
 * oracle for "the row's last item" (the last film by title, the last collection, Continue watching's titles). Read
 * only; the session is signed out again. The password never leaves this file.
 */
object ServerFacts {
    private const val DEVICE = "nixie-gate-b-facts"
    private const val TIMEOUT_MS = 20_000

    /** Continue watching's titles for [user], in the server's order (videos, as the app asks; at most [limit]). */
    fun resumeTitles(user: GateUser, limit: Int): List<String> = session(user) { token, id ->
        names(get(user, "/UserItems/Resume", token, mapOf("userId" to id, "mediaTypes" to "Video", "limit" to "$limit")))
    }

    /** Up next's shows for [user], in the server's order (at most [limit]). */
    fun nextUpShows(user: GateUser, limit: Int): List<String> = session(user) { token, id ->
        val items = get(user, "/Shows/NextUp", token, mapOf("userId" to id, "limit" to "$limit")).getJSONArray("Items")
        (0 until items.length()).map { items.getJSONObject(it).optString("SeriesName") }
    }

    /** The last title by name in [user]'s library called [library] (items of [type], as the library page lists them). */
    fun lastByName(user: GateUser, library: String, type: String): String = session(user) { token, id ->
        val views = get(user, "/UserViews", token, mapOf("userId" to id)).getJSONArray("Items")
        val view = (0 until views.length()).map { views.getJSONObject(it) }.firstOrNull { it.getString("Name") == library }
        check(view != null) { "the test server has no library '$library' for ${user.name}" }
        names(
            get(
                user, "/Items", token,
                mapOf(
                    "userId" to id, "parentId" to view!!.getString("Id"), "includeItemTypes" to type,
                    "recursive" to "true", "sortBy" to "SortName", "sortOrder" to "Descending", "limit" to "1",
                ),
            ),
        ).single()
    }

    /** The last collection (box set) by name on the server, as the Collections page lists them A to Z. */
    fun lastCollection(user: GateUser): String = session(user) { token, id ->
        names(
            get(
                user, "/Items", token,
                mapOf(
                    "userId" to id, "includeItemTypes" to "BoxSet", "recursive" to "true", "sortBy" to "SortName",
                    "sortOrder" to "Descending", "limit" to "1",
                ),
            ),
        ).single()
    }

    private fun names(page: JSONObject): List<String> {
        val items: JSONArray = page.getJSONArray("Items")
        return (0 until items.length()).map { items.getJSONObject(it).getString("Name") }
    }

    private fun <T> session(user: GateUser, read: (token: String, userId: String) -> T): T {
        val body = JSONObject().put("Username", user.name).put("Pw", user.password).toString()
        val (status, answer) = request("POST", "${user.server.address}/Users/AuthenticateByName", null, body)
        check(status in 200..299) { "the test server did not sign ${user.name} in to read the expected rows (HTTP $status)" }
        val json = JSONObject(answer)
        val token = json.getString("AccessToken")
        try {
            return read(token, json.getJSONObject("User").getString("Id"))
        } finally {
            runCatching { request("POST", "${user.server.address}/Sessions/Logout", token, null) }
        }
    }

    private fun get(user: GateUser, path: String, token: String, query: Map<String, String>): JSONObject {
        val q = query.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val (status, answer) = request("GET", "${user.server.address}$path?$q", token, null)
        check(status in 200..299) { "the test server answered HTTP $status to $path" }
        return JSONObject(answer)
    }

    private fun request(method: String, url: String, token: String?, body: String?): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            val auth = StringBuilder("MediaBrowser Client=\"Nixie gate\", Device=\"gate\", DeviceId=\"$DEVICE\", Version=\"1\"")
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
            return status to stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }
}
