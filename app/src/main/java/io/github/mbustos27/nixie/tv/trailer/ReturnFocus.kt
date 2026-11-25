// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.trailer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mbustos27.nixie.tv.home.requestFocusWhenReady
import java.util.concurrent.atomic.AtomicInteger

/**
 * TV-S3: a button that opens another screen (the Trailer button) takes the focus again when Back returns to its page.
 * Put [requester] on the button and call [opened] in its click. The mark is saved with the page, so it holds whether the
 * page stays composed meanwhile or is composed again on the way back.
 *
 * Review: when the screen hands over to another one ([handedOver]: the trailer's "Play movie" swaps in the player), Back
 * from that one is no return from the button's screen, so the mark goes; it also goes when the button is not there
 * within the wait (the trailers gone, say), so it never takes the focus later.
 */
class ReturnFocus internal constructor(private val mark: MutableIntState) {
    val requester = FocusRequester()

    fun opened() {
        mark.intValue = handovers.get()
    }

    companion object {
        private val handovers = AtomicInteger(0)

        /** The screen a [ReturnFocus] button opened was replaced by another one (the trailer by the movie player). */
        fun handedOver() {
            handovers.incrementAndGet()
        }

        internal fun handoversSoFar(): Int = handovers.get()
    }
}

@Composable
fun rememberReturnFocus(): ReturnFocus {
    // The hand-overs counted when the button was pressed; NOT_OPENED while nothing waits.
    val mark = rememberSaveable { mutableIntStateOf(NOT_OPENED) }
    val focus = remember(mark) { ReturnFocus(mark) }
    // Counted from this composition on: a page composed again hears the lifecycle's ON_RESUME replayed, and a page
    // that stayed composed hears it when it shows again. The click itself happens while resumed and counts nothing.
    var resumes by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumes++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumes) {
        val opened = mark.intValue
        if (resumes == 0 || opened == NOT_OPENED) return@LaunchedEffect
        if (opened == ReturnFocus.handoversSoFar()) requestFocusWhenReady({ focus.requester })
        mark.intValue = NOT_OPENED
    }
    return focus
}

private const val NOT_OPENED = -1
