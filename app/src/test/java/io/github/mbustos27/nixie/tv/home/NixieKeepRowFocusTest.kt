// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import android.annotation.SuppressLint
import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Nixie: a Continue watching row that changes keeps the focus where it was, and is not scrolled back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class NixieKeepRowFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private var keys by mutableStateOf(('a'..'h').map { "continue-$it" })
    private lateinit var row: LazyListState

    @SuppressLint("RememberInComposition") // Each FocusRequester is made once, kept in the remembered map.
    private fun row() {
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides DpadMode) {
                // As on Home, the row's titles come from the screen's state, read where the screen is composed.
                val shown = keys
                val requesters = remember { mutableMapOf<String, FocusRequester>() }
                var lastFocus by remember { mutableStateOf<String?>(null) }
                var inRow by remember { mutableStateOf(false) }
                // Scrolled along, as a row is once the focus went right a few cards.
                row = remember { LazyListState(firstVisibleItemIndex = 3) }
                NixieKeepRowFocus(shown, inRow, { lastFocus }, { lastFocus = it }, { requesters[it] })
                LazyRow(state = row, modifier = Modifier.onFocusChanged { inRow = it.hasFocus }) {
                    items(shown, key = { it }) { key ->
                        Box(
                            Modifier
                                .size(300.dp)
                                .testTag(key)
                                .focusRequester(requesters.getOrPut(key) { FocusRequester() })
                                .onFocusChanged { if (it.isFocused) lastFocus = key }
                                .focusable(),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a card going away before the focused one leaves the focus and the scroll alone`() {
        row()
        compose.onNodeWithTag("continue-f").requestFocus()
        compose.waitForIdle()
        val scrolled = row.firstVisibleItemIndex
        assertTrue(scrolled > 0)
        // An Undo card for "b" runs out.
        keys = keys - "continue-b"
        compose.waitForIdle()
        compose.onNodeWithTag("continue-f").assertIsFocused()
        assertTrue(row.firstVisibleItemIndex >= scrolled - 1)
    }

    @Test
    fun `the focused card going away hands the focus to its neighbour`() {
        row()
        compose.onNodeWithTag("continue-f").requestFocus()
        compose.waitForIdle()
        keys = keys - "continue-f"
        compose.waitForIdle()
        compose.onNodeWithTag("continue-g").assertIsFocused()
    }

    @Test
    fun `the neighbour is the next one still there, else the one before`() {
        val before = listOf("a", "b", "c", "d")
        assertEquals("b", focusAfterChange(before, listOf("b", "a", "c", "d"), "b"))
        assertEquals("c", focusAfterChange(before, listOf("a", "c", "d"), "b"))
        assertEquals("a", focusAfterChange(before, listOf("a"), "d"))
        assertEquals("z", focusAfterChange(before, listOf("z"), "b"))
        assertEquals(null, focusAfterChange(before, emptyList(), "b"))
    }

    private object DpadMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
    }
}
