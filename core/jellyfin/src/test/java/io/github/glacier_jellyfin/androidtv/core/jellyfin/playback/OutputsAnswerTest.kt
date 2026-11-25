package io.github.glacier_jellyfin.androidtv.core.jellyfin.playback

import android.media.AudioFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nixie (tv-1.0-fixes, the owner's TV log): "IEC 61937 off" after a wake from standby. The answer whether HDMI takes
 * IEC 61937 is kept per state of the outputs, not per their ids: a sink that reports its capabilities a moment after
 * the wake, under the same output id, is asked again.
 */
class OutputsAnswerTest {

    private val waking = listOf(OutputState(7, listOf(AudioFormat.ENCODING_PCM_16BIT), listOf(2)))
    private val awake = listOf(
        OutputState(
            7,
            listOf(
                AudioFormat.ENCODING_PCM_16BIT,
                AudioFormat.ENCODING_AC3,
                AudioFormat.ENCODING_E_AC3,
                AudioFormat.ENCODING_IEC61937,
            ),
            listOf(8),
        ),
    )

    @Test
    fun `an HDMI sink that tells its capabilities after the wake, under the same id, is asked again`() {
        val answer = OutputsAnswer()

        assertFalse("just woken: no IEC 61937 yet", answer.answer(waking) { false })
        assertTrue("the same output, its capabilities in", answer.answer(awake) { true })
    }

    @Test
    fun `outputs that did not change are not asked again`() {
        val answer = OutputsAnswer()
        answer.answer(awake) { true }

        assertTrue(answer.answer(awake.map { it.copy() }) { error("asked again with nothing changed") })
    }
}
