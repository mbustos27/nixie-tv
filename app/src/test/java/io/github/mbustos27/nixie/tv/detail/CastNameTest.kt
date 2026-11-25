// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.detail

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.detail.CastCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * tv-gate-b (the basics gate's text check, rule 12): a cast card's long name or role was cut at its second line with
 * the rest missing and no "…" ("Maximiliano Alejandro de la"). Both end in an ellipsis now, and the whole name and
 * role stay what the card says to TalkBack.
 */
@RunWith(RobolectricTestRunner::class)
// Real text measurement (Robolectric's native graphics), so the lines break as on a TV.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class CastNameTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a long name and role end in an ellipsis on their second line`() {
        show()
        for (text in listOf(NAME, ROLE)) {
            val layout = layoutOf(text)
            assertEquals("'$text' is laid out in two lines", 2, layout.lineCount)
            assertTrue("'$text' ends in an ellipsis on its last line", layout.isLineEllipsized(layout.lineCount - 1))
        }
    }

    @Test
    fun `TalkBack reads the whole name and role that the card shows cut with an ellipsis`() {
        show()
        // The card's merged semantics (what TalkBack reads) hold both texts whole, while the screen shows them cut
        // short with "…": the visible "…" and the whole text together, not a whole text drawn cut off.
        val card = compose.onNode(hasClickAction()).fetchSemanticsNode().config
        val read = card.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        assertTrue("TalkBack reads the whole name and role on the card: $read", read.containsAll(listOf(NAME, ROLE)))
        for (text in listOf(NAME, ROLE)) {
            val layout = layoutOf(text)
            assertTrue("'$text' is shown cut with an ellipsis", layout.isLineEllipsized(layout.lineCount - 1))
        }
    }

    private fun show() {
        compose.setContent { GlacierTheme { CastCard(name = NAME, role = ROLE, imageUrl = null, onClick = {}) } }
        compose.waitForIdle()
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    private companion object {
        const val NAME = "Maximiliano Alejandro de la Fuente-Villanueva"
        const val ROLE = "The Harbourmaster of Saint Brendan's Rock and the Northern Sound"
    }
}
