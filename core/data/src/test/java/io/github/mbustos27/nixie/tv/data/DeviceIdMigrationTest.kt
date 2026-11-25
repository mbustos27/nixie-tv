// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.withSignIn
import io.github.glacier_jellyfin.androidtv.core.jellyfin.SignedInUser
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Nixie: device ids per sign-in, and the profiles signed in before them. */
class DeviceIdMigrationTest {

    private val anna = UUID.fromString("00000000-0000-0000-0000-00000000000a")

    private fun signIn(token: String, deviceId: String?) = SignedInUser(
        serverId = "s1",
        userId = anna,
        name = "Anna",
        primaryImageTag = null,
        accessToken = token,
        deviceId = deviceId,
    )

    @Test
    fun `a profile saved before device ids reads back with none and keeps its token`() {
        val saved = """{"users":[{"serverId":"s1","userId":"$anna","name":"Anna","primaryImageTag":null,
            "accessToken":"old-token","lastUsedAt":1}]}"""
        val user = Json { ignoreUnknownKeys = true }.decodeFromString<AccountState>(saved).users.single()
        assertNull(user.deviceId)
        assertEquals("old-token", user.accessToken)
    }

    @Test
    fun `opening a profile from before writes the install's id into it, once`() {
        val saved = AccountState().withSignIn(signIn("old-token", deviceId = null), now = 1)
        val pinned = saved.withDeviceIdPinned("s1", anna.toString(), installId = "install")
        assertEquals("install", pinned.users.single().deviceId)
        assertEquals("old-token", pinned.users.single().accessToken)
        // Pinned once: another install id later (a changed ANDROID_ID) does not move it.
        assertEquals("install", pinned.withDeviceIdPinned("s1", anna.toString(), "other").users.single().deviceId)
    }

    @Test
    fun `a signed-out profile gets no id, its next sign-in brings one`() {
        val signedOut = AccountState().withSignIn(signIn("t", deviceId = null), now = 1).let { state ->
            state.copy(users = state.users.map { it.copy(accessToken = null) })
        }
        assertNull(signedOut.withDeviceIdPinned("s1", anna.toString(), "install").users.single().deviceId)
    }

    @Test
    fun `a sign-in keeps the id its token was issued to, and a new sign-in replaces it`() {
        val first = AccountState().withSignIn(signIn("t1", deviceId = "attempt-1"), now = 1)
        assertEquals("attempt-1", first.users.single().deviceId)
        val again = first.withSignIn(signIn("t2", deviceId = "attempt-2"), now = 2)
        assertEquals("attempt-2", again.users.single().deviceId)
        assertEquals("t2", again.users.single().accessToken)
    }
}
