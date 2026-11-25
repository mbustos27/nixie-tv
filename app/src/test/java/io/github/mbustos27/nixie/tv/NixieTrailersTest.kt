// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv

import android.app.Application
import io.github.glacier_jellyfin.androidtv.trailer.trailerPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The trailer page claims Nixie's own origin and embeds youtube-nocookie; Glacier's website never appears. Under
 * Robolectric because the page is built with Android's org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NixieTrailersTest {

    @Test
    fun theOriginIsNixiesAppId() {
        assertEquals("https://io.github.mbustos27.nixie.tv", NixieTrailers.ORIGIN)
        assertTrue(NixieTrailers.BASE_URL.startsWith(NixieTrailers.ORIGIN + "/"))
        assertEquals("https://www.youtube-nocookie.com", NixieTrailers.EMBED_HOST)
        for (value in listOf(NixieTrailers.ORIGIN, NixieTrailers.BASE_URL)) {
            assertFalse(value, value.contains("glacier", ignoreCase = true))
        }
    }

    @Test
    fun thePageEmbedsNocookieWithNixiesOriginAndNoOther() {
        val html = trailerPage("dQw4w9WgXcQ", "")
        assertTrue("origin", html.contains("origin: '${NixieTrailers.ORIGIN}'"))
        assertTrue("host", html.contains("host: '${NixieTrailers.EMBED_HOST}'"))
        assertTrue("rel=0 and autoplay", html.contains("autoplay: 1") && html.contains("rel: 0"))
        assertFalse("Glacier's website", html.contains("glacier-jellyfin.github.io"))
    }
}
