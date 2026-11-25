// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import android.app.Application
import io.github.glacier_jellyfin.androidtv.channels.HomeLaunch
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.mockk.mockk
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TV-S12: which launcher cards the player screen keeps, and the way back's line (design rule 10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerReturnTest {

    private var now = 10_000L
    private val lines = mutableListOf<String>()
    private val way = PlayerReturn(clock = { now }, log = { lines += it })
    private val film = UUID.nameUUIDFromBytes("film".toByteArray())
    private val other = UUID.nameUUIDFromBytes("other".toByteArray())
    private val session = Session(
        server = StoredServer("server-1", "Test", "http://127.0.0.1:1", version = null, lastUsedAt = 0),
        user = StoredUser("server-1", "user-1", "tester", primaryImageTag = null, accessToken = "t", lastUsedAt = 0),
        api = mockk(relaxed = true),
        installDeviceId = "device",
    )

    private fun card(
        item: UUID = film,
        play: Boolean = true,
        user: String = "user-1",
    ) = HomeLaunch("server-1", user, item.toString(), play)

    @Test
    fun `only a card that plays the held title, in the same profile, is kept`() {
        var held: UUID? = film
        val screen = PlayerReturn.Holder { held }
        assertFalse("no player screen", way.keeps(card(), session))
        way.attach(screen)

        assertTrue(way.keeps(card(), session))
        assertFalse("another title", way.keeps(card(other), session))
        assertFalse("its page, not play", way.keeps(card(play = false), session))
        assertFalse("another profile", way.keeps(card(user = "user-2"), session))
        assertFalse("nobody signed in", way.keeps(card(), null))
        held = null
        assertFalse("closing or failed", way.keeps(card(), session))
    }

    @Test
    fun `a newer screen stays attached when the older one goes`() {
        val old = PlayerReturn.Holder { other }
        val new = PlayerReturn.Holder { film }
        way.attach(old)
        way.attach(new)
        way.detach(old)
        assertTrue(way.keeps(card(), session))
        way.detach(new)
        assertFalse(way.keeps(card(), session))
    }

    @Test
    fun `the way back's line gives its duration and counts, once, and nothing outside a way back`() {
        way.asked()
        way.prepared()
        way.firstFrame()
        assertEquals(emptyList<String>(), lines)

        way.back()
        way.asked()
        // Sent away and back again before the first frame: what was asked stays counted.
        now += 2_000
        way.back()
        way.asked()
        way.prepared()
        now += 1_840
        way.firstFrame()
        way.firstFrame()

        assertEquals(
            listOf("Way back to the player: first frame 1.84 s, 2 opens asked of the server, 1 player prepared"),
            lines,
        )
    }

    @Test
    fun `a screen closed on the way has no line`() {
        way.back()
        way.dropped()
        way.firstFrame()
        assertEquals(emptyList<String>(), lines)
    }
}
