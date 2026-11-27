// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * A server refused a smaller stream for a profile ([at], epoch ms), signed in as the device id [signIn]: each sign-in
 * gets its own device id, so a new sign-in does not inherit it.
 */
@Serializable
data class RefusedSmaller(val at: Long, val signIn: String)

/**
 * The profiles whose server refused a smaller stream ("NoCompatibleStream": a shared server where the profile may not
 * transcode, or one that cannot make a stream that small). Their next titles go straight to the file instead of asking
 * again. Kept in memory and in the app's own settings file ([read], [write]; never the settings' copy on the server), by
 * server and user ([key]).
 *
 * It is forgotten at sign-out ([forget]; a sign-in with another device id does not see it either) and after [TTL_MS],
 * so a server whose rules changed is asked again.
 */
class SmallerStreamRefusals(
    private val read: suspend () -> Map<String, RefusedSmaller>,
    private val write:
    suspend ((Map<String, RefusedSmaller>) -> Map<String, RefusedSmaller>) -> Unit,
) {
    /** Now, in epoch ms; tests move it on. */
    internal var clock: () -> Long = System::currentTimeMillis

    private val known = ConcurrentHashMap<String, RefusedSmaller>()
    private val loading = Mutex()

    @Volatile
    private var loaded = false

    /** True when [serverId] refused a smaller stream for [userId], signed in as [signIn], within [TTL_MS]. */
    suspend fun refused(
        serverId: String,
        userId: String,
        signIn: String,
    ): Boolean {
        load()
        val key = key(serverId, userId)
        val entry = known[key] ?: return false
        if (entry.signIn == signIn && clock() - entry.at in 0 until TTL_MS) return true
        // Out of date or another sign-in's: asked again from now on.
        if (known.remove(key, entry)) write { it - key }
        return false
    }

    suspend fun remember(
        serverId: String,
        userId: String,
        signIn: String,
    ) {
        load()
        val key = key(serverId, userId)
        val entry = RefusedSmaller(clock(), signIn)
        known[key] = entry
        val now = entry.at
        // Old entries of other profiles go with the write.
        write { saved -> saved.filterValues { now - it.at in 0 until TTL_MS } + (key to entry) }
    }

    suspend fun forget(serverId: String, userId: String) {
        val key = key(serverId, userId)
        known.remove(key)
        write { if (key in it) it - key else it }
    }

    private suspend fun load() {
        if (loaded) return
        loading.withLock {
            if (loaded) return
            // What this process learned already wins over the file.
            read().forEach { (key, entry) -> known.putIfAbsent(key, entry) }
            loaded = true
        }
    }

    companion object {
        /** How long a refusal counts: a day. */
        const val TTL_MS = 24 * 60 * 60 * 1000L

        fun key(serverId: String, userId: String) = "$serverId/$userId"
    }
}
