// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import android.app.Application
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierCard
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * tv-gate-b: Right on a row's last card, sent the way a remote's key arrives (through the activity's window; a key the
 * app leaves unspent goes on to the system's own focus search). Compose's cancelled exit alone left it unspent, and on
 * the emulator the focus still went from Home's Up next to the top bar's Home; RowEndTest, sending the key to Compose
 * alone, passed either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class RowEndKeyTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the remote's Right on a row's last card stays there`() {
        compose.setContent {
            GlacierTheme {
                Column(Modifier.fillMaxSize()) {
                    TopBarLike()
                    MediaRow(title = "Up next") {
                        items(listOf("Show A", "Show B")) { name ->
                            GlacierCard(onClick = {}, modifier = Modifier.width(CARD.dp)) { Text(name) }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Show B").requestFocus()
        compose.waitForIdle()

        // The window's dispatch: whatever the app leaves unspent, the system's own focus search moves on a TV.
        var spent = false
        compose.runOnIdle {
            spent = compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        }
        compose.waitForIdle()

        assertTrue("Right on the row's last card is spent by the app, not left to the system's focus search", spent)
        compose.onNodeWithText("Show B").assertIsFocused()
    }

    private companion object {
        const val CARD = 200
    }
}
