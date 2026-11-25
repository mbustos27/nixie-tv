// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.watchnext

import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.mbustos27.nixie.tv.data.NixieHomeSettings
import java.time.LocalDateTime
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stream W: whose titles the TV's home screen shows, and which (WatchNextOwner, WatchNextRules). */
class WatchNextRulesTest {

    private val state = AccountState(
        servers = listOf(StoredServer("s1", "Home", "http://127.0.0.1:1", "12.4.0", 0)),
        users = listOf(user("alice", "token"), user("bob", "token")),
    )

    private fun user(id: String, token: String?) =
        StoredUser(serverId = "s1", userId = id, name = id, primaryImageTag = null, accessToken = token, lastUsedAt = 0)

    @Test
    fun theOwnerIsTheProfileOpenedLast_whileSignedIn() {
        assertEquals("alice", WatchNextOwner.of(state, "s1/alice")?.userId)
        assertEquals("bob", WatchNextOwner.of(state, "s1/bob")?.userId)
        // Nobody opened a profile yet: nothing, not the most recent signed-in one.
        assertNull(WatchNextOwner.of(state, null))
    }

    @Test
    fun noOwnerAfterASignOutOrARemoval_neverAnotherProfile() {
        val signedOut = state.copy(users = listOf(user("alice", null), user("bob", "token")))
        assertNull(WatchNextOwner.of(signedOut, "s1/alice"))
        val removed = state.copy(users = listOf(user("bob", "token")))
        assertNull(WatchNextOwner.of(removed, "s1/alice"))
        val serverGone = state.copy(servers = emptyList())
        assertNull(WatchNextOwner.of(serverGone, "s1/alice"))
        // Another server's profile of the same id is someone else.
        assertNull(WatchNextOwner.of(state, "s2/alice"))
    }

    private val now = LocalDateTime.of(2026, 11, 21, 12, 0)

    private fun dto(
        n: Int,
        series: Int? = null,
        daysAgo: Long = 1,
        position: Long = 1_000,
    ) = BaseItemDto(
        id = UUID(0, n.toLong()),
        type = if (series == null) BaseItemKind.MOVIE else BaseItemKind.EPISODE,
        name = "item$n",
        seriesId = series?.let { UUID(1, it.toLong()) },
        userData = UserItemDataDto(
            playbackPositionTicks = position,
            playCount = 0,
            isFavorite = false,
            played = false,
            key = "k$n",
            itemId = UUID(0, n.toLong()),
            lastPlayedDate = now.minusDays(daysAgo),
        ),
    )

    @Test
    fun theRowIsHomesContinueWatchingThenUpNext_eachShowOnce_staleLeftOut() {
        val resume = listOf(dto(1), dto(2, series = 7), dto(3, series = 7), dto(4, daysAgo = 120))
        val nextUp = listOf(dto(5, series = 7, position = 0), dto(6, series = 8, position = 0))
        val settings = ProfileSettings(nixieHome = NixieHomeSettings(hideStaleDays = 90))

        val titles = WatchNextRules.titles(resume, nextUp, settings, now).map { it.name }

        assertEquals(listOf("item1", "item2", "item6"), titles)
    }

    @Test
    fun switchedOff_theRowIsEmpty() {
        val settings = ProfileSettings(nixieWatchNext = NixieWatchNextSettings(showOnHomeScreen = false))
        assertTrue(WatchNextRules.titles(listOf(dto(1)), listOf(dto(5, series = 7)), settings, now).isEmpty())
        // On by default.
        assertTrue(ProfileSettings().nixieWatchNext.showOnHomeScreen)
    }
}
