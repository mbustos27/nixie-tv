// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.settings.HomeRow
import io.github.glacier_jellyfin.androidtv.core.data.settings.HomeSettings

/**
 * Nixie: Home's libraries in the server's order (desktop Nixie: `OrderedViews`). The server hands
 * out the libraries in the order the profile set in Jellyfin; Glacier keeps a local order once
 * a row was moved in Settings › Home, which then no longer follows the server. When the server's
 * order changes, the "New in" rows (in the places the rows have) and the "My media" cards take
 * it again, each keeping whether it is shown. Moves made on this TV stand until the next change.
 */
object ServerOrder {

    /**
     * The settings to write for [libraryIds], the server's order now; null when nothing changes.
     * Nothing is written while the profile has no order of its own (nothing to follow, and a
     * profile new on this TV keeps its settings unwritten until the restore offer is answered);
     * the first order seen once it has one is only noted, so a move just made stands.
     */
    fun update(
        home: HomeSettings,
        nixie: NixieHomeSettings,
        libraryIds: List<String>,
    ): Pair<HomeSettings, NixieHomeSettings>? {
        if (libraryIds == nixie.serverOrder) return null
        val ownOrder = home.libraryCards.isNotEmpty() ||
            home.rows.any { it.row == HomeRow.Latest && it.library != null }
        if (!ownOrder) return null
        val noted = nixie.copy(serverOrder = libraryIds)
        return if (nixie.serverOrder.isEmpty()) home to noted else home.following(libraryIds) to noted
    }

    fun HomeSettings.following(libraryIds: List<String>): HomeSettings {
        val rank = libraryIds.withIndex().associate { (index, id) -> id to index }
        fun rankOf(id: String?) = rank[id] ?: Int.MAX_VALUE
        val places = rows.indices.filter { rows[it].row == HomeRow.Latest && rows[it].library != null }
        val latest = places.map { rows[it] }.sortedBy { rankOf(it.library) }
        val ordered = rows.toMutableList().apply { places.forEachIndexed { i, place -> this[place] = latest[i] } }
        return copy(rows = ordered, libraryCards = libraryCards.sortedBy { rankOf(it.id) })
    }
}
