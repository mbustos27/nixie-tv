// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import java.text.DateFormat
import java.util.Date

/*
 * The owner, 2026-11-22: "Fix all noticed issues and pointed out issues". After a crash or a failed playback the log
 * was put on the local network by itself, for any device there to fetch. Now the app asks first, in one plain line
 * with Yes and No, and nothing is served until Yes. Settings › System › Diagnostics › Download log is the user's own
 * choice already and asks nothing.
 */

/** Test tags of the question's buttons. */
object NixieLogQuestion {
    const val YES = "nixie-log-yes"
    const val NO = "nixie-log-no"
}

/**
 * The question's line: [text], then Yes and No; [onAnswer] gets true for Yes. [noFocus], when given, is No's focus
 * requester (the crash page starts there: a stray OK serves nothing).
 */
@Composable
fun NixieLogAsk(
    text: String,
    onAnswer: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = GlacierText.body(19),
    noFocus: FocusRequester? = null,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = style, color = GlacierColors.Ice, modifier = Modifier.weight(1f, fill = false))
        PillButton(
            stringResource(R.string.nixie_yes),
            onClick = { onAnswer(true) },
            height = 56,
            modifier = Modifier.testTag(NixieLogQuestion.YES),
        )
        PillButton(
            stringResource(R.string.nixie_no),
            onClick = { onAnswer(false) },
            height = 56,
            modifier = Modifier.testTag(NixieLogQuestion.NO).let { m -> noFocus?.let { m.focusRequester(it) } ?: m },
        )
    }
}

/**
 * After a crash, before the first screen: the question alone on the page. The focus starts on No, and Back says No.
 * [crashedAt] is when the app closed (epoch ms).
 */
@Composable
fun NixieCrashLogQuestion(crashedAt: Long, onAnswer: (Boolean) -> Unit) {
    BackHandler { onAnswer(false) }
    val no = remember { FocusRequester() }
    GlacierBackground(
        Modifier
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 130.dp), contentAlignment = Alignment.Center) {
            NixieLogAsk(
                stringResource(
                    R.string.nixie_log_ask_crash,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(crashedAt)),
                ),
                onAnswer,
                style = GlacierText.body(24),
                noFocus = no,
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { no.requestFocus() } }
}
