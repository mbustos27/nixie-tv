package io.github.glacier_jellyfin.androidtv.core.jellyfin.playback

import android.media.AudioDeviceInfo

/**
 * Nixie (tv-1.0-fixes): one audio output as far as what it takes goes: its id, and the encodings and channel counts
 * it reports. The owner's TV log (2026-11-22): 1.3 s after a wake from standby the player's setup read "IEC 61937
 * off" on a box that had read "on" before. Right after a wake the HDMI sink may not have told its capabilities yet;
 * an answer kept by the outputs' ids alone stayed with that output when it later reported them under the same id.
 */
internal data class OutputState(
    val id: Int,
    val encodings: List<Int>,
    val channelCounts: List<Int>,
) {
    companion object {
        fun of(outputs: Array<AudioDeviceInfo>): List<OutputState> = outputs
            .map { OutputState(it.id, it.encodings.sorted(), it.channelCounts.sorted()) }
            .sortedBy { it.id }
    }
}

/** A question about the audio outputs, asked again only when they changed in any way ([OutputState]). */
internal class OutputsAnswer {
    private var last: Pair<List<OutputState>, Boolean>? = null

    @Synchronized
    fun answer(outputs: List<OutputState>, ask: () -> Boolean): Boolean {
        last?.takeIf { it.first == outputs }?.let { return it.second }
        return ask().also { last = outputs to it }
    }
}
