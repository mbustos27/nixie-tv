// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import io.github.glacier_jellyfin.androidtv.BuildConfig
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.mbustos27.nixie.tv.designsystem.GlassTier
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import io.github.mbustos27.nixie.tv.designsystem.NixieVisuals
import io.github.mbustos27.nixie.tv.designsystem.VisualCapabilities
import io.github.mbustos27.nixie.tv.theme.cinematic.CinematicTheme
import io.github.mbustos27.nixie.tv.theme.drivein.DriveInTheme
import io.github.mbustos27.nixie.tv.theme.editorial.EditorialTheme

/**
 * The app's theme registry: every theme module's [io.github.mbustos27.nixie.tv.designsystem.NixieThemeSpec] is
 * registered here, and the chosen theme is kept on the device. Drive-in is the default.
 *
 * Debug builds switch themes without a picker:
 * `adb shell am start -n io.github.mbustos27.nixie.tv.debug/io.github.glacier_jellyfin.androidtv.MainActivity
 * --es nixie.theme Cinematic` (DriveIn, Cinematic, Editorial or Glacier). The choice is kept until the next one.
 * Settings > Appearance offers the same choice ([pickable]); the switch is live and keeps the focus.
 */
object NixieThemes {
    const val EXTRA = "nixie.theme"
    const val GLASS_EXTRA = "nixie.glass"
    private const val PREFS = "nixie"
    private const val KEY = "theme"
    private const val TAG = "NixieThemes"

    /** From the application's onCreate, in every process, before anything is drawn. */
    fun install(context: Context) {
        NixieTheme.register(DriveInTheme)
        NixieTheme.register(CinematicTheme)
        NixieTheme.register(EditorialTheme)
        NixieTheme.use(stored(context) ?: NixieThemeId.DriveIn)
        NixieVisuals.use(VisualCapabilities(detectTier(context)))
        Log.i(TAG, "Glass tier ${NixieVisuals.current.tier}")
    }

    /** The box's glass tier from its memory: 2 GB boxes (1.9 GB usable) and the 2 GB Chromecast get fake glass. */
    private fun detectTier(context: Context): GlassTier {
        val activities = context.getSystemService(ActivityManager::class.java) ?: return GlassTier.Fake
        val memory = ActivityManager.MemoryInfo().also(activities::getMemoryInfo)
        return NixieVisuals.tierFor(memory.totalMem, activities.isLowRamDevice, Build.VERSION.SDK_INT)
    }

    /**
     * The themes Settings > Appearance offers, Drive-in (the default) first. Glacier's original look is for
     * comparing in debug builds only.
     */
    fun pickable(debug: Boolean = BuildConfig.DEBUG): List<NixieThemeId> =
        listOf(NixieThemeId.DriveIn, NixieThemeId.Cinematic, NixieThemeId.Editorial) +
            listOfNotNull(NixieThemeId.Glacier.takeIf { debug })

    /** A theme's name in the picker. */
    @StringRes
    fun labelOf(id: NixieThemeId): Int = when (id) {
        NixieThemeId.DriveIn -> R.string.nixie_theme_drivein
        NixieThemeId.Cinematic -> R.string.nixie_theme_cinematic
        NixieThemeId.Editorial -> R.string.nixie_theme_editorial
        NixieThemeId.Glacier -> R.string.nixie_theme_glacier
    }

    /** Wears [id] now and from the next start on. */
    fun choose(context: Context, id: NixieThemeId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, id.name).apply()
        NixieTheme.use(id)
        Log.i(TAG, "Theme ${id.name}")
    }

    /**
     * Debug builds: a launch with the [EXTRA] extra switches the theme; with [GLASS_EXTRA] (Live, Fake or Flat) the
     * glass tier, until the next start, to see a big box's drawing on a small one and back.
     */
    fun fromIntent(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        intent?.getStringExtra(GLASS_EXTRA)?.let { glass ->
            GlassTier.entries.firstOrNull { it.name.equals(glass, ignoreCase = true) }
                ?.let { NixieVisuals.use(VisualCapabilities(it)) }
        }
        val name = intent?.getStringExtra(EXTRA) ?: return
        val id = NixieThemeId.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (id == null) Log.w(TAG, "No theme called $name") else choose(context, id)
    }

    private fun stored(context: Context): NixieThemeId? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { name -> NixieThemeId.entries.firstOrNull { it.name == name } }
}
