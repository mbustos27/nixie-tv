// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Nixie: favourite libraries in the top bar (desktop Nixie 1.6). A profile stars up to [MAX] of
 * its server's libraries in Settings › Home; the top bar shows them, in the server's order, in
 * place of its Movies and Shows entries. With none starred the bar keeps its entries by kind,
 * so a server's "[HUB]" libraries never fill it. The stars are kept per server and profile, in
 * that profile's settings.
 */
@Singleton
class TopBarLibraries @Inject constructor(home: HomeRepository, settings: SettingsRepository) {

    /** The starred libraries the server has, as the top bar shows them. */
    val favourites: StateFlow<List<Library>> = combine(home.libraries, settings.settings) { libraries, profile ->
        favourites(libraries, profile.nixieHome.topBarLibraries)
    }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, emptyList())

    companion object {
        /** At most this many libraries in the top bar. */
        const val MAX = 3

        /** The starred ([ids]) among [libraries], in the server's order, at most [MAX]. */
        fun favourites(libraries: List<Library>, ids: List<String>): List<Library> =
            libraries.filter { it.id.toString() in ids }.take(MAX)

        /** [ids] with [id] starred or not; a fourth star is refused (the ids come back unchanged). */
        fun starred(
            ids: List<String>,
            id: String,
            star: Boolean,
        ): List<String> = when {
            !star -> ids - id
            id in ids || ids.size >= MAX -> ids
            else -> ids + id
        }
    }
}
