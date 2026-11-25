package io.github.glacier_jellyfin.androidtv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.util.EventLogger

/**
 * Nixie (tv-1.0-privacy): Media3's event log for Detailed logging, without what a file's tags say. Its metadata
 * blocks (`metadata [` … `]` for a stream's timed tags, `Metadata [` … `]` under a track) print each tag whole: a
 * song's title and artist, a film's title. A shared log may go into a public issue, so each tag shows only its kind
 * (`TIT2 …`, `TITLE …`); the rest of the event log stays as Media3 writes it.
 */
@OptIn(UnstableApi::class)
internal open class TagFreeEventLogger : EventLogger() {
    private var inMetadata = false

    @Synchronized
    override fun logd(msg: String) = emit(withoutTagValues(msg))

    /** Where the lines go: Media3's log (Media3Logs hands them to the app's). */
    protected open fun emit(line: String) = super.logd(line)

    private fun withoutTagValues(msg: String): String {
        val text = msg.trim()
        return when {
            text.startsWith("metadata [", ignoreCase = true) -> msg.also { inMetadata = true }
            !inMetadata -> msg
            text == "]" -> msg.also { inMetadata = false }
            else -> msg.substring(0, msg.length - msg.trimStart().length) + kindOf(text) + " …"
        }
    }

    private companion object {
        private const val MAX_KIND_LENGTH = 32

        /** `TIT2: description=null: values=[…]` -> `TIT2`; `TITLE=…` -> `TITLE`; anything else -> `tag`. */
        fun kindOf(entry: String): String {
            val kind = entry.takeWhile { it != ':' && it != '=' }.trim()
            val plain = kind.isNotEmpty() && kind.length <= MAX_KIND_LENGTH
            return if (plain && kind.all { it.isLetterOrDigit() || it == '_' }) kind else "tag"
        }
    }
}
