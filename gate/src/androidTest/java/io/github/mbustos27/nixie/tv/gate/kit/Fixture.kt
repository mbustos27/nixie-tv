// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.kit

import org.json.JSONObject

/** A test server as the emulator reaches it: [address] is http://10.0.2.2:<port>. */
data class GateServer(
    val key: String,
    val name: String,
    val address: String,
) {
    /** What is typed into "Add server": the address without its scheme. */
    val typed: String get() = address.removePrefix("http://")
}

/** A test user on [server]. The password stays in the kit: never in a message, a log or a screenshot's name. */
class GateUser(
    val key: String,
    val name: String,
    val server: GateServer,
    internal val password: String,
) {
    override fun toString() = "$name on ${server.name}"
}

/**
 * The fixtures of this run, from the file the runner pushes to the device (tools/gate/Run-Gate.ps1 writes it from the
 * throwaway servers' credential files and removes it afterwards): the main throwaway Jellyfin with its users and the
 * gate's second server with its own.
 */
object Fixture {
    const val FILE = "/data/local/tmp/nixie-gate/fixture.json"

    private val json: JSONObject by lazy {
        val text = Gate.shell("cat $FILE")
        runCatching { JSONObject(text) }.getOrNull()
            ?: fail("no gate fixture at $FILE: run the gate through tools/gate/Run-Gate.ps1")
    }

    private val servers: Map<String, GateServer> by lazy {
        val all = json.getJSONObject("servers")
        all.keys().asSequence().associateWith { key ->
            val s = all.getJSONObject(key)
            GateServer(key, s.getString("name"), s.getString("address"))
        }
    }

    private val users: Map<String, GateUser> by lazy {
        val all = json.getJSONObject("users")
        all.keys().asSequence().associateWith { key ->
            val u = all.getJSONObject(key)
            GateUser(key, u.getString("name"), server(u.getString("server")), u.getString("password"))
        }
    }

    fun server(key: String): GateServer = servers[key] ?: fail("the gate fixture has no server '$key'")

    fun user(key: String): GateUser = users[key] ?: fail("the gate fixture has no user '$key'")

    /** The main throwaway Jellyfin (about 25,000 films, the series fixtures). */
    val main: GateServer get() = server("main")

    /** The gate's second server (a small throwaway Jellyfin of its own). */
    val second: GateServer get() = server("second")

    /** tvbasics on [main]: the user the checks browse as. */
    val basics: GateUser get() = user("basics")

    /** tvtest on [main]: the second user, for switching users and profiles. */
    val other: GateUser get() = user("other")

    /** gate2 on [second]. */
    val secondUser: GateUser get() = user("secondUser")

    /**
     * 1.0.1: the TV's fake Jellyfin (testing/fakeserver/tvserver.py) where the test user may not transcode, with a slow
     * link test; the runner starts it only for the checks that use it.
     */
    val refusing: GateServer get() = server("refusing")

    /** The fake's test user on [refusing], not its admin. */
    val refusingUser: GateUser get() = user("refusingUser")
}
