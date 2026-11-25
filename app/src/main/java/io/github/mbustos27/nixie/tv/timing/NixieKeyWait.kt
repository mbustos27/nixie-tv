// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.timing

import android.content.pm.ApplicationInfo
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import io.github.glacier_jellyfin.androidtv.core.log.Log

/**
 * TV-S13: a screen opened by an OK (the trailer's): made with the screen, it keeps that OK, and [done] (its first frame)
 * writes the whole wait from the key, split: how long the key waited for the main thread, when it came up, when the
 * screen opened and was made, and the main thread's long frames meanwhile. Durations only. Main thread only.
 */
class NixieKeyWait(
    private val what: String,
    private val log: (String) -> Unit,
    private val uptime: () -> Long = SystemClock::uptimeMillis,
    private val stalls: (Long, Long) -> NixieFrameWatch.Stalls = NixieFrameWatch::between,
    press: NixieKeyClock.Press? = NixieKeyClock.recent(),
) {
    private val madeAt = uptime()
    private var key = press

    /** The wait is over: its line, once (none without an OK). */
    fun done() {
        val press = key ?: return
        key = null
        val at = uptime()
        val down = press.downAt
        val steps = buildList {
            add("the key waited ${press.queuedMs} ms for the main thread")
            press.upAt?.let { add("came up at ${it - down} ms") }
            press.navigatedAt?.let { add("the screen opened at ${it - down} ms") }
            add("made at ${madeAt - down} ms")
        }
        log("$what from the key: ${at - down} ms (${steps.joinToString()}); main thread: ${stalls(down, at)}")
    }
}

/**
 * TV-S13: every key the screens get passes here first (the navigation host's own modifier) and is noted by
 * [NixieKeyClock]; nothing is consumed. The main-thread watch ([NixieFrameWatch]) runs in debug builds, the box
 * check's build, and while detailed logging is on.
 */
@Composable
fun Modifier.nixieKeyClock(): Modifier {
    val debuggable = (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    return onPreviewKeyEvent {
        NixieFrameWatch.enabled = debuggable || Log.verbose
        NixieKeyClock.seen(it.nativeKeyEvent)
        false
    }
}
