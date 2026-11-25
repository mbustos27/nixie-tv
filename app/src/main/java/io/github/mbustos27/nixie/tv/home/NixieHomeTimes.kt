// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import io.github.mbustos27.nixie.tv.data.StartLine

/**
 * Nixie (tv-home-start): when Home began to load and when it was on screen, for [StartLine] (the detailed log's
 * durations, and the moment the background work waits for). [profile]: the signed-in profile's key, if any;
 * [firstDrawn] runs once, when Home is first on screen with a row.
 */
class NixieHomeTimes(
    private val startLine: StartLine,
    private val profile: () -> String?,
    private val firstDrawn: () -> Unit,
) {
    private var began = 0L
    private var drawn = false

    /** A load of Home's rows begins. */
    fun begin() {
        began = startLine.now()
    }

    /** Milliseconds since the load began. */
    fun sinceBegan(): Long = startLine.now() - began

    /** Home is on screen with its first row (HomeScreen, a frame after its content came). */
    fun onScreen() {
        val key = profile() ?: return
        if (drawn) return
        drawn = true
        startLine.homeDrawn(key, sinceBegan())
        firstDrawn()
    }

    /** Every row is in and on screen (HomeScreen, a frame after the last row came). */
    fun allOnScreen() {
        val key = profile() ?: return
        startLine.homeSettled(key, sinceBegan())
    }
}
