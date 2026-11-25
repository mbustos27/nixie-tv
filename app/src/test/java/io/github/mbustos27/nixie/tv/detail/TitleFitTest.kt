// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.detail

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.detail.Title
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import io.github.mbustos27.nixie.tv.designsystem.NixieThemeId
import io.github.mbustos27.nixie.tv.theme.cinematic.CinematicTheme
import io.github.mbustos27.nixie.tv.theme.drivein.DriveInTheme
import io.github.mbustos27.nixie.tv.theme.editorial.EditorialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * tv-gate-b: an item page's long title ends in an ellipsis on its second line in every theme. The gate's text check
 * flagged Editorial's as cut: its negative letter spacing lays the characters hidden behind the "…" a hair before one
 * another, which the check read as text laid back over the line. The title was ellipsized; the check now skips
 * characters with no width (gate/b/TextFit.kt). This holds the title to it, and the hidden characters to no width.
 */
@RunWith(RobolectricTestRunner::class)
// Real text measurement (Robolectric's native graphics), so the lines break as on a TV.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class TitleFitTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun wear() = listOf(DriveInTheme, CinematicTheme, EditorialTheme).forEach(NixieTheme::register)

    @After
    fun takeOff() = NixieTheme.use(NixieThemeId.Glacier)

    @Test
    fun `a long title ends in an ellipsis on its second line in every theme`() {
        var theme by mutableStateOf(NixieThemeId.Glacier)
        compose.setContent {
            GlacierTheme {
                key(theme) {
                    Box(Modifier.width((PAGE_WIDTH - 2 * PageEdge).dp)) {
                        Title(LONG, size = SIZE, maxWidth = MAX_WIDTH)
                    }
                }
            }
        }
        for (t in listOf(NixieThemeId.DriveIn, NixieThemeId.Cinematic, NixieThemeId.Editorial)) {
            NixieTheme.use(t)
            theme = t
            compose.waitForIdle()
            val layout = layoutOf(LONG)
            val last = layout.lineCount - 1
            assertEquals("$t: the title is laid out in two lines", 2, layout.lineCount)
            assertTrue("$t: the title ends in an ellipsis", layout.isLineEllipsized(last))
            val behind = layout.getLineEnd(last, visibleEnd = true) + 1
            val hidden = (behind until LONG.length).map { layout.getBoundingBox(it) }
            assertTrue("$t: the characters behind the ellipsis have no width", hidden.all { it.right - it.left < 1f })
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    private companion object {
        const val PAGE_WIDTH = 960
        const val SIZE = 84
        const val MAX_WIDTH = 1180
        const val LONG = "The Remarkably Long and Winding Chronicles of the Lighthouse Keepers of the " +
            "Northern Archipelago"
    }
}
