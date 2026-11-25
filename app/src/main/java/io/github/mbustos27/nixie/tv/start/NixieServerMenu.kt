// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.ModalSheet
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.ignoreHeldOk
import io.github.glacier_jellyfin.androidtv.setup.ServerEntry

/**
 * Where the focus goes as the server menu and Remove's question come and go: back to the server's card when they
 * close with nothing done; to the list's first entry ([first]) once a removal took the card away ([refocus] counts
 * removals, [removed] names the last), and only when the focus was still on that card: an entry picked meanwhile
 * keeps it.
 */
@Composable
fun NixieServerMenuFocus(
    open: ServerEntry?,
    cards: NixieServerCardFocus,
    refocus: Int,
    removed: String?,
    first: FocusRequester,
) {
    var last by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(open?.server?.id) {
        val id = open?.server?.id
        if (id != null) {
            last = id
            return@LaunchedEffect
        }
        val closed = last ?: return@LaunchedEffect
        last = null
        withFrameNanos { }
        runCatching { cards.of(closed).requestFocus() }
    }
    LaunchedEffect(refocus) {
        if (refocus == 0 || !cards.wasOn(removed)) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
}

/**
 * A saved server's menu, from its card's Menu key or a long OK: the server named, and Remove. Back closes it with
 * nothing done. The held OK that opened it presses nothing in it.
 */
@Composable
fun NixieServerMenu(
    entry: ServerEntry,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalSheet(onDismiss = onDismiss) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                entry.server.name,
                style = GlacierText.display(28),
                color = GlacierColors.Ice,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Text(entry.server.address, style = GlacierText.mono(17), color = GlacierColors.Mist, maxLines = 1)
        }
        val remove = remember { FocusRequester() }
        Column(Modifier.ignoreHeldOk(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                stringResource(R.string.nixie_server_menu_remove),
                onClick = onRemove,
                icon = GlacierIcons.Trash,
                fillWidth = true,
                modifier = Modifier.focusRequester(remove),
            )
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { remove.requestFocus() }
        }
    }
}

/** Remove's question, asked once: Cancel has the focus; Remove takes the server and its profiles off this TV. */
@Composable
fun NixieRemoveServerQuestion(
    entry: ServerEntry,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    ModalSheet(onDismiss = onCancel, width = 720) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.nixie_server_remove_title, entry.server.name),
                style = GlacierText.display(28),
                color = GlacierColors.Ice,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.nixie_server_remove_body),
                style = GlacierText.body(19).copy(lineHeight = 28.sp),
                color = GlacierColors.Mist,
                textAlign = TextAlign.Center,
            )
        }
        val cancel = remember { FocusRequester() }
        Row(Modifier.ignoreHeldOk(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PillButton(
                stringResource(R.string.action_cancel),
                onClick = onCancel,
                modifier = Modifier.focusRequester(cancel),
            )
            PillButton(stringResource(R.string.nixie_server_remove), onClick = onRemove, primary = true)
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { cancel.requestFocus() }
        }
    }
}
