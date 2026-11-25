// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import android.os.Process
import android.os.SystemClock
import io.github.glacier_jellyfin.androidtv.core.log.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Nixie (tv-home-start): the app's way to Home, step by step. Each step's duration goes to the detailed log
 * (DESIGN-RULES 10: durations only, never a title or a name), so the next check on the box gives numbers without
 * adb. And the moments Home is drawn and settled for a profile, which the background work waits for (DESIGN-RULES 1:
 * Watch Next, the launcher's channels).
 */
@Singleton
class StartLine internal constructor(private val clock: () -> Long, private val processStart: () -> Long) {
    @Inject
    constructor() : this(SystemClock::elapsedRealtime, Process::getStartElapsedRealtime)

    private val drawn = MutableStateFlow<String?>(null)
    private val settled = MutableStateFlow<String?>(null)

    @Volatile
    private var firstHome = true

    /** Milliseconds since this process started. */
    fun sinceStart(): Long = clock() - processStart()

    /** Now, on the clock [step] durations are measured with. */
    fun now(): Long = clock()

    /** A step of the way that took [ms]; logged with the time since the app started. */
    fun step(name: String, ms: Long) {
        Log.d(TAG, "$name: $ms ms (${sinceStart()} ms since the app started)")
    }

    /** Home of [profile] is on screen with its first row, [ms] after it began to load (each Home once). */
    fun homeDrawn(profile: String, ms: Long) {
        drawn.value = profile
        val first = firstHome
        firstHome = false
        val since = if (first) " (${sinceStart()} ms since the app started)" else ""
        Log.d(TAG, "Home's first row on screen: $ms ms$since")
    }

    /** Every row of [profile]'s Home is in and on screen, [ms] after it began to load. */
    fun homeSettled(profile: String, ms: Long) {
        settled.value = profile
        Log.d(TAG, "Home's rows all in and on screen: $ms ms")
    }

    /** Waits until [profile]'s Home settled, at most [capMs] (the background work goes ahead then anyway). */
    suspend fun awaitHomeSettled(profile: String, capMs: Long) {
        withTimeoutOrNull(capMs) { settled.first { it == profile } }
    }

    /** Waits until some profile's Home settled, at most [capMs]. */
    suspend fun awaitAnyHomeSettled(capMs: Long) {
        withTimeoutOrNull(capMs) { settled.first { it != null } }
    }

    companion object {
        const val TAG = "Start"

        /**
         * A moment of a cold start, in ms since the process started: usable before injection (Application.onCreate,
         * the activity), so the split of a cold start is in the detailed log.
         */
        fun mark(name: String) {
            val since = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
            Log.d(TAG, "$name at $since ms since the app started")
        }

        /** Background work waits at most this long for Home (a profile picker, a server away). */
        const val BACKGROUND_CAP_MS = 30_000L
    }
}
