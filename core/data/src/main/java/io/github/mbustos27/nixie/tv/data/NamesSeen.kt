// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import kotlinx.serialization.Serializable

/**
 * Nixie (tv-1.0-privacy): every server and profile name this TV has had, also after they were removed, kept with the
 * accounts (encrypted, AccountRepository). The log's lines are older than the accounts: a removed server's name and
 * address, or a removed profile's name, stay in what was written while they were here, and the shared log's anonymiser
 * must still find them.
 */
@Serializable
data class NamesSeen(val servers: List<SeenServer> = emptyList(), val users: List<String> = emptyList())

@Serializable
data class SeenServer(val address: String, val name: String)

/** At most this many of each kind, the newest kept. */
internal const val MAX_NAMES_SEEN = 200

/** This state with the names it and [before] hold added to the names seen; the same state when none is new. */
fun AccountState.nixieWithNamesSeen(before: AccountState): AccountState {
    val servers = (nixieSeen.servers + (before.servers + servers).map { SeenServer(it.address, it.name) })
        .distinct()
        .takeLast(MAX_NAMES_SEEN)
    val users = (nixieSeen.users + (before.users + users).map { it.name })
        .filter { it.isNotBlank() }
        .distinct()
        .takeLast(MAX_NAMES_SEEN)
    val seen = NamesSeen(servers, users)
    return if (seen == nixieSeen) this else copy(nixieSeen = seen)
}
