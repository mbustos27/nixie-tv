// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import android.app.Application
import android.graphics.drawable.ColorDrawable
import coil3.asImage
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** TV-S1 (DESIGN-RULES 10): the first picture of the library's first rows is told once, with where it came from. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NixieImageWatchTest {

    private val first = UUID.nameUUIDFromBytes("film-1".toByteArray())
    private val other = UUID.nameUUIDFromBytes("film-99".toByteArray())

    @After
    fun tearDown() = NixieImageWatch.stop()

    private fun loaded(url: String, source: DataSource = DataSource.NETWORK) {
        val request = ImageRequest.Builder(RuntimeEnvironment.getApplication()).data(url).build()
        NixieImageWatch.onSuccess(request, SuccessResult(ColorDrawable().asImage(), request, source))
    }

    private fun poster(id: UUID, dashes: Boolean) =
        "http://tv.test/Items/${if (dashes) id.toString() else id.toString().replace("-", "")}/Images/Primary?tag=t"

    @Test
    fun `the first picture of the watched titles is told once, from where it came`() {
        val seen = mutableListOf<String>()
        NixieImageWatch.watch(listOf(first)) { seen += it }
        loaded(poster(other, dashes = false))
        loaded(poster(first, dashes = false), DataSource.DISK)
        loaded(poster(first, dashes = true))
        assertEquals(listOf("disk"), seen)
    }

    @Test
    fun `an address with dashes counts, and a stopped watch tells nothing`() {
        val seen = mutableListOf<String>()
        NixieImageWatch.watch(listOf(first)) { seen += it }
        loaded(poster(first, dashes = true), DataSource.MEMORY_CACHE)
        NixieImageWatch.watch(listOf(first)) { seen += "again $it" }
        NixieImageWatch.stop()
        loaded(poster(first, dashes = true))
        assertEquals(listOf("memory_cache"), seen)
    }
}
