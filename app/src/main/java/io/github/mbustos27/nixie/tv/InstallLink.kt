// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv

/**
 * tv-1.0.1: the short link that installs Nixie for TV on another TV, with its Downloader code, as CHANGELOG.md's Install
 * section gives them. Settings › System › About shows them beside the source link.
 */
object InstallLink {
    const val URL = "is.gd/nixietv"
    const val DOWNLOADER = "Downloader · 6673217"

    /** Only the build that installs from the link shows it: a store's build ([store] "amazon" or "play") comes from its store. */
    fun shownIn(store: String): Boolean = store.isEmpty()
}
