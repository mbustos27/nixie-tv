// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/*
 * Nixie: the focus on Home's own rows. A card is drawn by a lazy row, so its node may come a few frames
 * after the screen or the state that asks for it; these keep asking until it is there.
 */

/** Asks [requester] for the focus now and on each of the next [frames] frames, until a node takes it. */
suspend fun requestFocusWhenReady(requester: () -> FocusRequester?, frames: Int = FOCUS_FRAMES): Boolean {
    repeat(frames) { frame ->
        if (frame > 0) withFrameNanos { }
        val target = requester() ?: return@repeat
        if (runCatching { target.requestFocus() }.getOrDefault(false)) return true
    }
    return false
}

/**
 * A card that swaps one composable for another in its place (the Undo card and the card it puts back) loses
 * the focus with the node that goes: [give] names the card that takes it over.
 */
class NixieFocusHandoff {
    var pending by mutableStateOf<String?>(null)
        private set

    fun give(key: String) {
        pending = key
    }

    internal fun done() {
        pending = null
    }
}

@Composable
fun rememberFocusHandoff(requester: (String) -> FocusRequester?): NixieFocusHandoff {
    val handoff = remember { NixieFocusHandoff() }
    LaunchedEffect(handoff.pending) {
        val key = handoff.pending ?: return@LaunchedEffect
        requestFocusWhenReady({ requester(key) })
        handoff.done()
    }
    return handoff
}

/**
 * The screen's returns to the front (ON_RESUME) after the first, and the element it was left from. Back from a
 * page opened a moment ago finds Home still composed, its focus gone with that page (or moved by it), and no
 * new composition to give it back.
 */
class NixieReturns {
    var count by mutableIntStateOf(0)
        internal set

    /** The focus key when the screen left the front, kept from what happens while it is away. */
    var leftFrom: String? = null
        internal set
}

@Composable
fun rememberReturns(lastFocus: () -> String?): NixieReturns {
    val owner = LocalLifecycleOwner.current
    val returns = remember { NixieReturns() }
    DisposableEffect(owner) {
        // A lifecycle already resumed replays ON_RESUME to a new observer: that one is no return.
        var skip = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> returns.leftFrom = lastFocus()
                Lifecycle.Event.ON_RESUME -> if (skip) skip = false else returns.count++
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return returns
}

private const val FOCUS_FRAMES = 30
