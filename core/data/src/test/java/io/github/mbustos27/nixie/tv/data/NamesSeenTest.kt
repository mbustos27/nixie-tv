// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import androidx.datastore.core.DataStore
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * tv-1.0-privacy (the privacy review): names of servers and profiles removed earlier stayed in older lines of a shared
 * log, since the anonymiser knew only those still on the TV. The accounts keep every name they had. Made-up names.
 */
class NamesSeenTest {

    private class MemoryStore(start: AccountState) : DataStore<AccountState> {
        override val data = MutableStateFlow(start)

        override suspend fun updateData(transform: suspend (t: AccountState) -> AccountState): AccountState =
            transform(data.value).also { data.value = it }
    }

    private val lake = StoredServer("s1", "Lakehouse", "http://lakehouse.example.net:8096", null, 0)
    private val den = StoredServer("s2", "Den", "https://den.example.org", null, 0)
    private fun user(
        server: String,
        id: String,
        name: String,
    ) = StoredUser(server, id, name, null, "token", lastUsedAt = 0)

    @Test
    fun `a removed server and its profiles stay among the names seen, and a removed profile does too`() = runBlocking {
        val users = listOf(user("s1", "u1", "Marguerite"), user("s2", "u2", "Bo"))
        val store = MemoryStore(AccountState(servers = listOf(lake, den), users = users))
        val accounts = AccountRepository(store)

        accounts.removeServer("s1")
        accounts.removeUser("s2", "u2")

        val seen = store.data.value.nixieSeen
        assertEquals(listOf("Lakehouse", "Den"), seen.servers.map { it.name })
        assertEquals("http://lakehouse.example.net:8096", seen.servers.first().address)
        assertEquals(listOf("Marguerite", "Bo"), seen.users)
        assertEquals(emptyList<StoredUser>(), store.data.value.users)
    }

    // The re-review: they are kept only for the log; clearing it (Settings) forgets them.
    @Test
    fun `the names kept for the log can be forgotten`() = runBlocking {
        val seen = NamesSeen(listOf(SeenServer("http://lakehouse.example.net:8096", "Lakehouse")), listOf("Marguerite"))
        val store = MemoryStore(AccountState(servers = listOf(den), nixieSeen = seen))

        AccountRepository(store).nixieForgetNamesSeen()

        assertEquals(NamesSeen(), store.data.value.nixieSeen)
        assertEquals(listOf(den), store.data.value.servers)
    }
}
