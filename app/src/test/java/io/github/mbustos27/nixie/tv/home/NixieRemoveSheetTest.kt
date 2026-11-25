// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import android.app.Application
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.unit.dp
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Nixie: holding OK on a Continue watching card opens the Remove sheet; the held key removes nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class NixieRemoveSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val item = MediaItem(
        id = UUID(3, 3),
        kind = ItemKind.Movie,
        title = "Harbour Lights",
        sortName = null,
        year = null,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = null,
        genres = emptyList(),
        overview = null,
        parentTitle = null,
        seasonNumber = null,
        episodeNumber = null,
        progress = 0.4f,
        remainingMinutes = 30,
        unwatchedCount = null,
        isFavorite = false,
        played = false,
        childCount = null,
        quality = null,
        posterUrl = null,
        thumbUrl = null,
        backdropUrl = null,
    )

    private var removed = 0
    private var cancelled = 0

    /** The card, focusable like a Continue watching card, and the sheet open for it. */
    private fun sheet() {
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                GlacierTheme {
                    val card = remember { FocusRequester() }
                    var asked by remember { mutableStateOf<MediaItem?>(item) }
                    Column {
                        // Home has more to focus than the card: the bar, the hero, other rows.
                        Box(Modifier.size(200.dp).testTag("bar").focusable())
                        Box(Modifier.size(200.dp).testTag("card").focusRequester(card).focusable())
                    }
                    NixieRemoveSheetHost(
                        asked,
                        onRemove = {
                            removed++
                            asked = null
                        },
                        onCancel = {
                            cancelled++
                            asked = null
                        },
                        returnTo = { card },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun ok(action: Int, repeat: Int) {
        compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0, 0, action, KEYCODE_DPAD_CENTER, repeat)))
        compose.waitForIdle()
    }

    @Test
    fun `the sheet opens on Cancel, not on Remove`() {
        sheet()
        compose.onNodeWithText("Cancel").assertIsFocused()
    }

    @Test
    fun `the held OK that opened the sheet presses nothing in it`() {
        sheet()
        // The remote repeats OK while it is held, then lets go.
        ok(ACTION_DOWN, repeat = 5)
        ok(ACTION_DOWN, repeat = 6)
        ok(ACTION_UP, repeat = 0)
        assertEquals(0 to 0, removed to cancelled)
        // A new press works.
        ok(ACTION_DOWN, repeat = 0)
        ok(ACTION_UP, repeat = 0)
        assertEquals(0 to 1, removed to cancelled)
    }

    @Test
    fun `after Remove the focus goes back to the card's place, where the Undo card stands`() {
        sheet()
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()
        assertEquals(1, removed)
        compose.onNodeWithTag("card").assertIsFocused()
    }

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }
}
