// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The review (TV-S2): the posters fetched ahead of the grid. Those that fall out of the rows ahead while the remote is
 * held down are cancelled, the nearest go first, and leaving the screen cancels what is still coming.
 */
class NixiePrefetchPlanTest {

    private val started = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()
    private val plan = NixiePrefetchPlan(
        start = { art: AheadArt ->
            started += art.url
            art.url
        },
        cancel = { cancelled += it },
    )

    private fun ahead(vararg names: String) = names.map { AheadArt(it, 240, 360) }

    @Test
    fun `pictures no longer ahead are cancelled, the new ones start nearest first`() {
        plan.want(ahead("a", "b", "c"))
        plan.want(ahead("c", "d", "e"))
        assertEquals(listOf("a", "b"), cancelled)
        assertEquals(listOf("a", "b", "c", "d", "e"), started)
        assertEquals(listOf("c", "d", "e"), plan.pending)
    }

    @Test
    fun `a picture fetched is not fetched again`() {
        plan.want(ahead("a", "b"))
        plan.finished("a")
        plan.want(ahead("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), started)
        assertEquals(emptyList<String>(), cancelled)
    }

    @Test
    fun `leaving the screen cancels what is still coming`() {
        plan.want(ahead("a", "b", "c"))
        plan.finished("b")
        plan.cancelAll()
        assertEquals(listOf("a", "c"), cancelled)
        assertEquals(emptyList<String>(), plan.pending)
    }
}
