// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

/** The top bar's shape for the row end tests: entries up and to the right, entered through the page's entry (TopNav). */
@Composable
internal fun TopBarLike() {
    val home = remember { FocusRequester() }
    Row {
        Spacer(Modifier.width(BAR_X.dp))
        Row(Modifier.focusProperties { onEnter = { home.requestFocus() } }.focusGroup()) {
            Text("Search", Modifier.size(ENTRY.dp).focusable())
            Text("Home", Modifier.size(ENTRY.dp).testTag(BAR_TAG).focusRequester(home).focusable())
        }
    }
}

internal const val BAR_TAG = "bar-entry"
private const val BAR_X = 600
private const val ENTRY = 80
