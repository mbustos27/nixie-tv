// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.play

import android.content.Context
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.core.view.DisplayCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The size a transcode is made for (TV-S7). Android TV draws its UI at 1920x1080 on a 4K panel, so the UI's size
 * (`Resources.getSystem().displayMetrics`, what Glacier read) says 1080p on a 3840x2160 TV, and a 4K title the server
 * converted anyway was asked again at 1080p. The display's mode (`Display.Mode`, through [DisplayCompat] for the boxes
 * that report the UI's size there too) is what the panel shows.
 */
@Singleton
class DisplayPanel internal constructor(
    /** The current display mode's physical width and height; null when there is no display. */
    private val modeSize: () -> Pair<Int, Int>?,
    /** The UI's width and height in pixels. */
    private val uiSize: () -> Pair<Int, Int>,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        modeSize = {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.let {
                val mode = DisplayCompat.getMode(context, it)
                mode.physicalWidth to mode.physicalHeight
            }
        },
        uiSize = { Resources.getSystem().displayMetrics.let { it.widthPixels to it.heightPixels } },
    )

    /** Width and height in pixels, landscape. */
    fun size(): Pair<Int, Int> = panelSize(modeSize(), uiSize())

    /** For the log: the panel and the UI, "3840x2160 (UI 1920x1080)". */
    fun describe(): String {
        val (width, height) = size()
        val ui = landscape(uiSize())
        return if (ui == width to height) "${width}x$height" else "${width}x$height (UI ${ui.first}x${ui.second})"
    }
}

/**
 * The panel's size, landscape: the display mode's. The UI's only where the mode says nothing or less, so no display
 * is ever taken for smaller than Glacier took it.
 */
internal fun panelSize(mode: Pair<Int, Int>?, ui: Pair<Int, Int>): Pair<Int, Int> {
    val fromUi = landscape(ui)
    val fromMode = mode?.let(::landscape)?.takeIf { it.second > 0 } ?: return fromUi
    return if (fromMode.first.toLong() * fromMode.second >= fromUi.first.toLong() * fromUi.second) fromMode else fromUi
}

/** Reasons for a transcode that leave the picture to be copied: the audio's, or the container's (a repack). */
private val COPIED_PICTURE_REASONS = setOf(
    "ContainerNotSupported", "UnknownAudioStreamInfo", "AudioCodecNotSupported", "AudioBitrateNotSupported",
    "AudioChannelsNotSupported", "AudioProfileNotSupported", "AudioSampleRateNotSupported", "AudioBitDepthNotSupported",
    "AudioIsExternal", "SecondaryAudioNotSupported",
)

/**
 * True when the server converts only the audio (or repacks) and copies the picture as it is: [reasons] is the
 * comma-separated `TranscodeReasons` of its answer. The device decodes that picture (the server weighed it against the
 * decoders' limits), so a display smaller than it is no reason to have the server re-encode it: the decoder scales it
 * down, and the server's work stays a copy.
 */
fun pictureOnlyCopied(reasons: String?): Boolean {
    val list = reasons?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    return list.isNotEmpty() && list.all { it in COPIED_PICTURE_REASONS }
}

private fun landscape(size: Pair<Int, Int>): Pair<Int, Int> =
    maxOf(size.first, size.second) to minOf(size.first, size.second)
