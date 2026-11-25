// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How much glass a box can afford (the plan's tiers): [Live] blurs one or two static regions; [Fake] draws glass as a
 * tint and a lit edge over a pre-blurred picture (a tiny image scaled up, a blur that costs nothing), never a live
 * blur; [Flat] uses flat scrims and stops ambient motion. Every theme degrades the same way through
 * [VisualCapabilities].
 */
enum class GlassTier { Live, Fake, Flat }

/** What the box can draw, decided once at start (`NixieVisuals.detect` in the app). */
@Immutable
data class VisualCapabilities(val tier: GlassTier) {
    /** A live blur (`Modifier.blur`, a backdrop capture) is allowed. */
    val liveBlur: Boolean get() = tier == GlassTier.Live

    /** Soft drop shadows (each one an offscreen blur) are allowed. */
    val shadows: Boolean get() = tier == GlassTier.Live

    /** Slow decorative motion (the backdrops' zoom, twinkles) runs. */
    val ambientMotion: Boolean get() = tier != GlassTier.Flat

    /** Pixel width to ask for a picture that is shown blurred: null asks the full size (only for a live blur). */
    val blurredImageWidth: Int? get() = if (tier == GlassTier.Live) null else PRE_BLURRED_WIDTH

    companion object {
        const val PRE_BLURRED_WIDTH = 96
    }
}

/** The capabilities in use: snapshot state, so a change (a debug switch) redraws what read it. */
object NixieVisuals {
    var current: VisualCapabilities by mutableStateOf(VisualCapabilities(GlassTier.Live))
        private set

    fun use(capabilities: VisualCapabilities) {
        current = capabilities
    }

    /**
     * The tier for a box: a low-RAM device is [GlassTier.Flat]; under [FAKE_BELOW_BYTES] of memory (the 2 GB
     * Chromecast, a 4K Google TV box with 1.9 GB) or before Android 12 (no `Modifier.blur`) it is [GlassTier.Fake];
     * anything bigger (the Google TV Streamer's 4 GB) is [GlassTier.Live].
     */
    fun tierFor(
        totalMemoryBytes: Long,
        lowRamDevice: Boolean,
        sdk: Int,
    ): GlassTier = when {
        lowRamDevice -> GlassTier.Flat
        totalMemoryBytes < FAKE_BELOW_BYTES || sdk < ANDROID_12 -> GlassTier.Fake
        else -> GlassTier.Live
    }

    /**
     * [url] asked at [width] pixels wide, for a picture that is only ever seen blurred: Jellyfin scales it on the
     * server, and the box scales the tiny image back up. Earlier size parameters are replaced; null stays null.
     */
    fun preBlurred(url: String?, width: Int): String? {
        if (url == null) return null
        val base = url.substringBefore('?')
        val kept = url.substringAfter('?', "").split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=').lowercase() !in SIZE_PARAMETERS }
        return base + "?" + (kept + "maxWidth=$width").joinToString("&")
    }

    private const val FAKE_BELOW_BYTES = 2_600_000_000L
    private const val ANDROID_12 = 31
    private val SIZE_PARAMETERS = setOf("maxwidth", "maxheight", "fillwidth", "fillheight", "width", "height")
}
