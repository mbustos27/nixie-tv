package io.github.glacier_jellyfin.androidtv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nixie (tv-1.0-privacy): with Detailed logging on, Media3's event log printed a file's tags whole (a song's title
 * and artist, a film's title) into a log that may go into a public issue. Each tag shows only its kind. Made-up tags.
 */
@OptIn(UnstableApi::class)
class TagFreeEventLoggerTest {

    private class Capturing : TagFreeEventLogger() {
        val lines = mutableListOf<String>()

        override fun emit(line: String) {
            lines += line
        }
    }

    private val time = AnalyticsListener.EventTime(0, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0)

    @Test
    fun `a file's tags are logged by their kind, not what they say`() {
        val logger = Capturing()

        logger.onMetadata(
            time,
            Metadata(
                TextInformationFrame("TIT2", null, listOf("Lanterns over Harrow Bay")),
                TextInformationFrame("TPE1", null, listOf("Marguerite Okonkwo")),
                VorbisComment("TITLE", "The Lighthouse Keeper's Daughter"),
            ),
        )

        val log = logger.lines.joinToString("\n")
        for (tag in listOf("Lanterns", "Harrow", "Marguerite", "Okonkwo", "Lighthouse")) {
            assertFalse("\"$tag\" in\n$log", tag in log)
        }
        assertTrue(log, logger.lines.any { it.trim() == "TIT2 …" } && logger.lines.any { it.trim() == "TPE1 …" })
        val opensAndCloses = logger.lines.first().startsWith("metadata [") && logger.lines.last() == "]"
        assertTrue("the block still opens and closes:\n$log", opensAndCloses)
    }

    @Test
    fun `the player's event log is the one without tags`() {
        assertTrue(Media3Logs.eventLogger() is TagFreeEventLogger)
    }
}
