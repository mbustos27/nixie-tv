// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.jellyfin

import java.util.UUID
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.model.DeviceInfo

/**
 * Nixie: a device id per sign-in.
 *
 * Jellyfin keeps one session, and on sign-in one token, per device id. Glacier sent the
 * install's id (Android's ANDROID_ID) for every profile, so two profiles on one TV shared a
 * session on the server, and a second sign-in could end the first one's. Nixie gives each sign-in
 * attempt its own id: a Quick Connect attempt fixes it before Initiate and keeps it to the end,
 * since the server binds the code, and the token it hands out, to the id that asked. The profile
 * then sends that id with every request ([deviceFor]).
 *
 * Profiles signed in before this keep the install's id, so their tokens stay valid.
 */
object DeviceIds {
    /** A new id for one sign-in attempt. */
    fun fresh(): String = UUID.randomUUID().toString().replace("-", "")
}

/** The device a profile's requests go out as: its own id, or the install's when it has none (a sign-in from before). */
fun Jellyfin.deviceFor(id: String?): DeviceInfo = installDevice.let { if (id == null) it else it.copy(id = id) }

/** The install's own device id, as Glacier sent it for every profile. */
val Jellyfin.installDeviceId: String get() = installDevice.id

private val Jellyfin.installDevice: DeviceInfo
    get() = checkNotNull(deviceInfo) { "The SDK was set up without a device" }
