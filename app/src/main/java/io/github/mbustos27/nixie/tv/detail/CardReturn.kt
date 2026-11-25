// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mbustos27.nixie.tv.home.requestFocusWhenReady
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * tv-gate-b (the basics gate, rule 12: "Back is instant", the same focus): Back to a page puts the focus on the card
 * that opened the next page, where it was. Navigation composes a page again on the way back. The library grid, search
 * and Home each brought their card back their own way; a title's page (its episodes and cast), a collection's page
 * and the Libraries page did not: the focus went to the page's first button or card, or to the focus restorer's guess
 * (the cast card before the one opened), and the episodes' row went back to the first unwatched episode. One rule for
 * all of them: [rememberCardReturn] keeps the opened card's key with the page's saved state, and the card marked with
 * [cardReturn] takes the focus again, once, when its page is back.
 *
 * tv-fix-b (the review of the merged TV fixes): the return waited for its card for as long as it took, so a card that
 * came late (the page loaded again after the app was in the background) took the focus after the user had moved; and
 * any OK remembered its card, one that opened nothing too. Now a key the user presses on the page ([cardReturnPage])
 * drops a return still waiting, as does [RETURN_MS] without its card; and an OK's card is kept only if the page is
 * left right after it (the OK opened the next page): one still shown [OPENS_MS] later forgets it.
 */

/** A page's memory of the card that opened the next page. */
@Stable
class CardReturn internal constructor(
    private val saved: MutableState<String?>,
    returning: Boolean,
    private val scope: CoroutineScope,
    /** The page's lifecycle: below resumed, another page is on top. */
    private val lifecycle: Lifecycle,
) {
    /** The key of the card to focus now that Back returned to the page; null when nothing waits. */
    var pending: String? by mutableStateOf(if (returning) saved.value else null)
        private set

    /** OK on the card [key]: if it opens the next page, Back to this page focuses it again. */
    fun opened(key: String) {
        saved.value = key
        scope.launch {
            val left = withTimeoutOrNull(OPENS_MS) {
                lifecycle.currentStateFlow.first { !it.isAtLeast(Lifecycle.State.RESUMED) }
            }
            // The page stayed on: the OK opened nothing, so its card is not one Back comes back to.
            if (left == null && saved.value == key) saved.value = null
        }
    }

    /** A key the user pressed on the page: a return still waiting for its card is dropped, and so is an OK's card. */
    internal fun userKey() {
        pending = null
        saved.value = null
    }

    internal fun done() {
        pending = null
        saved.value = null
    }
}

/** The page's [CardReturn], kept with the page's saved state (its back stack entry) while another page is on top. */
@Composable
fun rememberCardReturn(): CardReturn {
    val saved = rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val memory = remember { CardReturn(saved, returning = saved.value != null, scope, lifecycle) }
    // A return whose card has not come by then (the page loading again) is dropped: the focus stays where it is.
    LaunchedEffect(memory) {
        if (memory.pending == null) return@LaunchedEffect
        delay(RETURN_MS)
        if (memory.pending != null) memory.done()
    }
    return memory
}

/** On the page's root: a key the user presses on the page drops a return still waiting (see [CardReturn.userKey]). */
fun Modifier.cardReturnPage(memory: CardReturn): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown) memory.userKey()
    false
}

/**
 * The card [key] of [memory]'s page, put on the card (or on a box around it): OK on it remembers it, and when Back
 * returns to the page it takes the focus again, after the page's own first focus (its Play button, its first card).
 */
@Composable
fun Modifier.cardReturn(memory: CardReturn?, key: String): Modifier {
    if (memory == null) return this
    val requester = remember { FocusRequester() }
    if (memory.pending == key) {
        LaunchedEffect(key) {
            repeat(SETTLE_FRAMES) { withFrameNanos { } }
            requestFocusWhenReady({ requester })
            memory.done()
        }
    }
    return onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyUp && event.key in OkKeys) memory.opened(key)
        false
    }.focusRequester(requester)
}

/** The page's own first focus runs a frame after it is drawn: the card's comes after it. */
private const val SETTLE_FRAMES = 2

/** How long a return waits for its card: past it, the page has moved on. */
internal const val RETURN_MS = 2_000L

/** How soon an OK's next page takes over from the page: a page still shown after it, the OK opened nothing. */
internal const val OPENS_MS = 1_000L

private val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
