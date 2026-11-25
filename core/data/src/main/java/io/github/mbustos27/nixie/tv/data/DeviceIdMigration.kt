// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.updateUser

/**
 * Nixie: the migration to device ids per sign-in (`DeviceIds` in core/jellyfin). A profile
 * signed in before has no id of its own and a token the server tied to the install's id: it
 * keeps that id, written into the profile, so its sign-in goes on working. Signed-out profiles
 * and profiles with an id are left as they are.
 */
internal fun AccountState.withDeviceIdPinned(
    serverId: String,
    userId: String,
    installId: String,
): AccountState = updateUser(serverId, userId) { user ->
    if (user.deviceId == null && user.accessToken != null) user.copy(deviceId = installId) else user
}
