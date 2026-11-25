// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/*
 * tv-gate-b (the basics gate, rule 12): Right on a row's last card stays there, by tv-1.0-r1's rule for the library grid
 * (nixieListEnd: the last card's "end" is FocusRequester.Cancel), now for every row built on MediaRow (Home's rows, a
 * title's episodes, cast and "More like this", a collection's and a person's rows). Home's short Up next row sent the
 * focus to the top bar's Home. A row only knows its items as its content adds them, so [nixieRowWithEnd] notes them
 * first and then adds them to the row, each in the same box, whose focus properties stop Right on the last one (they
 * reach the card inside). A cancelled exit on the row as a whole held in a JVM test but not on the emulator. Every
 * card has the box, not just the last: a card that stops being the last (a refresh adds one after it) keeps its
 * composition, and with it the focus.
 */

/** [content] added to [scope], its last item (the last of the last non-empty call) ending the row for Right. */
fun nixieRowWithEnd(scope: LazyListScope, content: LazyListScope.() -> Unit) {
    val noted = NotedItems().apply(content)
    val last = noted.calls.indexOfLast { it.count > 0 }
    noted.calls.forEachIndexed { index, call -> call.addTo(scope, index == last) }
}

/** The box around each of the row's cards: Right on the card inside stays there when it [endsRow]. */
@Composable
private fun RowCard(endsRow: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.focusProperties { if (endsRow) end = FocusRequester.Cancel }) { content() }
}

private class NotedCall(val count: Int, val addTo: (LazyListScope, endsRow: Boolean) -> Unit)

/** A LazyListScope that only notes what a row's content adds, to add it to the real one afterwards. */
@OptIn(ExperimentalFoundationApi::class)
private class NotedItems : LazyListScope {
    val calls = mutableListOf<NotedCall>()

    override fun item(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        calls += NotedCall(1) { scope, endsRow ->
            scope.item(key, contentType) {
                RowCard(endsRow) { content() }
            }
        }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        calls += NotedCall(count) { scope, endsRow ->
            scope.items(count, key, contentType) { index ->
                RowCard(endsRow && index == count - 1) { itemContent(index) }
            }
        }
    }

    override fun stickyHeader(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.(Int) -> Unit,
    ) {
        calls += NotedCall(1) { scope, _ -> scope.stickyHeader(key, contentType, content) }
    }
}
