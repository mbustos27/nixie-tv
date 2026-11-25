// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.api.UserItemDataDto

/** Nixie: the signed-in profile's resume points on its server, for [ContinueWatchingRemovals]. */
@Singleton
class ContinueWatchingServer @Inject constructor(private val sessions: SessionManager) : RemovalServer {

    override suspend fun snapshot(itemId: UUID): ResumeSnapshot = withContext(Dispatchers.IO) {
        api().userDataApi.getItemUserData(itemId = itemId).content.toSnapshot()
    }

    override suspend fun setPosition(itemId: UUID, positionTicks: Long) {
        withContext(Dispatchers.IO) {
            api().userDataApi.updateItemUserData(
                itemId = itemId,
                data = UpdateUserItemDataDto(playbackPositionTicks = positionTicks),
            )
        }
    }

    private fun api() = checkNotNull(sessions.session.value) { "No profile is signed in" }.api
}

internal fun UserItemDataDto.toSnapshot() = ResumeSnapshot(
    positionTicks = playbackPositionTicks,
    played = played,
    playCount = playCount,
    lastPlayed = lastPlayedDate,
)
