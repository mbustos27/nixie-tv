// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import androidx.lifecycle.SavedStateHandle
import java.util.UUID

/**
 * TV-S8: the player lets go in the background. A box held a paused player all night in standby; now the
 * player screen releases it when the app is stopped (Home, standby, the screensaver) and opens the same title again,
 * paused, when it comes back. [StandbyOpen] is the open to repeat; the screen's saved state keeps the title and the
 * position too, so a screen restored after the app's process went away comes back the same way.
 */
data class StandbyOpen(
    /** Null: where the open being repeated starts (its own rule: the resume point, or the start). */
    val startMs: Long?,
    val audio: Int?,
    val subtitle: Int?,
    val fromLastTitle: Boolean,
    val snapStart: Boolean,
)

object Standby {
    private const val ITEM_KEY = "nixie.standby.item"
    private const val POSITION_KEY = "nixie.standby.positionMs"

    /** Keeps [itemId] at [positionMs] in the screen's saved state. */
    fun save(
        handle: SavedStateHandle,
        itemId: UUID,
        positionMs: Long,
    ) {
        handle[ITEM_KEY] = itemId.toString()
        handle[POSITION_KEY] = positionMs
    }

    /** The title and position [save] kept, removed from the saved state; null when there is none. */
    fun take(handle: SavedStateHandle): Pair<UUID, Long>? {
        val item = handle.remove<String>(ITEM_KEY)
        val position = handle.remove<Long>(POSITION_KEY)
        if (item == null || position == null) return null
        return runCatching { UUID.fromString(item) }.getOrNull()?.let { it to position }
    }
}
