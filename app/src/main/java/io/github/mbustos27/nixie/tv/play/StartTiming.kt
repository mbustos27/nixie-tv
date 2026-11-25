// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import android.os.SystemClock
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.util.Locale

/**
 * Design rule 10: the detailed log records how long the starts users wait on take, so a check on the box gives numbers
 * without adb. One start of [what] (a trailer, a title) from the moment this is made: each [mark] notes a step once,
 * and [firstFrame] ends it with one line, "Trailer start (YouTube): details 0.41 s, page 2.10 s, first frame 9.80 s".
 * Durations and step names only: never a title, a name or an address. The line goes to logcat, and to the file log
 * while detailed logging is on.
 */
class StartTiming(
    private val what: String,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = { Log.d(TAG, it) },
) {
    private val startedAt = clock()
    private val marks = LinkedHashMap<String, Long>()
    private var ended = false

    /** What kind of start this turned out to be ("YouTube", "server file"), shown after [what]. */
    var kind: String? = null

    /** Notes [step] now, the first time only; nothing after [firstFrame]. */
    fun mark(step: String) {
        if (ended || step in marks) return
        marks[step] = clock() - startedAt
    }

    /** The start is over: its line goes to the log, once. */
    fun firstFrame() {
        if (ended) return
        mark(FIRST_FRAME)
        ended = true
        val name = kind?.let { "$what ($it)" } ?: what
        log("$name: " + marks.entries.joinToString { (step, ms) -> "$step ${seconds(ms)}" })
    }

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.2f s", ms / 1000.0)

    companion object {
        const val TAG = "StartTiming"
        const val FIRST_FRAME = "first frame"
    }
}
