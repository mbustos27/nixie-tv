// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TV-S4 (review M1): a row of better matches that arrives late goes in front of the search grid. A lazy grid keeps its
 * top card where it was, so without KeepBestInView they would sit above the screen ("bare episodes first" again).
 * The grid is the search page's (four columns), the focus on the keypad beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class KeepBestInViewTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var results by mutableStateOf((1..40).map { "Episode $it" })
    private var focusInGrid = false
    private var firstVisible = -1

    private fun page() {
        compose.setContent {
            val grid = rememberLazyGridState()
            val keypad = remember { FocusRequester() }
            KeepBestInView(grid, results.firstOrNull(), focusInGrid = { focusInGrid })
            Row {
                Box(Modifier.size(100.dp).focusRequester(keypad).focusable()) { Text("Keypad") }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    state = grid,
                    modifier = Modifier.onFocusChanged { focusInGrid = it.hasFocus },
                ) {
                    items(results, key = { it }) { title ->
                        Box(Modifier.size(120.dp).testTag(title).focusable()) { Text(title) }
                    }
                }
            }
            LaunchedEffect(Unit) { keypad.requestFocus() }
            firstVisible = grid.firstVisibleItemIndex
        }
        compose.waitForIdle()
    }

    @Test
    fun `better matches that arrive late show at the top while the focus is on the keypad`() {
        page()
        assertEquals(0, firstVisible)
        // Six shows rank above the episodes already on screen.
        results = (1..6).map { "Show $it" } + results
        compose.waitForIdle()
        assertEquals(0, firstVisible)
        compose.onNodeWithTag("Show 1").assertIsDisplayed()
    }
}
