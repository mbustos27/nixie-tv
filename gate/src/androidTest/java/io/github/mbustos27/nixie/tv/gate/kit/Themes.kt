// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.kit

/**
 * The run's theme, chosen the way a viewer does: Settings > Appearance > Theme. Fresh app data wears Drive-in, the
 * default, so the sign-in screens are always Drive-in's in a release build; everything after the sign-in wears the
 * run's theme.
 */
object Themes {
    const val DRIVE_IN = "DriveIn"
    private val labels = mapOf(DRIVE_IN to "Drive-in", "Cinematic" to "Cinematic", "Editorial" to "Editorial")

    /** The theme the kit last chose in this app data; Drive-in after fresh data. */
    private var worn: String? = null

    fun forgetState() {
        worn = null
    }

    /** Wears [theme] (DriveIn, Cinematic or Editorial) from Home; ends on Home. */
    fun wear(theme: String) {
        if (theme == DRIVE_IN && worn == null) return
        if (theme == worn) return
        val label = labels[theme] ?: fail("no theme called $theme (DriveIn, Cinematic or Editorial)")
        Settings.open()
        Settings.category(Settings.APPEARANCE)
        Settings.row(Settings.THEME)
        Settings.choose(label)
        check(
            Settings.focusedRowShows(label),
        ) { "Settings > Theme did not take $label; it shows '${Screen.focusedText()}'" }
        worn = theme
        check(Home.reach()) { "Home did not come back after choosing the theme $label" }
    }
}
