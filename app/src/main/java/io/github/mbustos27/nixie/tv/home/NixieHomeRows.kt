// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeContent
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierCard
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.ModalSheet
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusFrame
import io.github.glacier_jellyfin.androidtv.core.designsystem.ignoreHeldOk
import io.github.glacier_jellyfin.androidtv.ui.LocalCardSizes
import io.github.glacier_jellyfin.androidtv.ui.ProgressBar
import io.github.glacier_jellyfin.androidtv.ui.yearText
import io.github.mbustos27.nixie.tv.data.ContinueWatchingRemovals
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import java.util.UUID

/*
 * Nixie: Home's Continue watching and Up next rows (desktop Nixie 1.2.0): the Undo card, the Remove sheet and the
 * focus kept in a row whose cards change. The page that draws the rows is NixieHomePage (tv-home-two-rows).
 */

/** An Up next card's second line: the episode and its title. */
@Composable
internal fun upNextSubtitle(item: MediaItem): String {
    val season = item.seasonNumber
    val episode = item.episodeNumber
    return if (item.kind == ItemKind.Episode && season != null && episode != null) {
        listOfNotNull(stringResource(R.string.episode_short, season, episode), item.title).joinToString(" · ")
    } else {
        yearText(item).orEmpty()
    }
}

/**
 * A card taken out of Continue watching, in its place for [ContinueWatchingRemovals.UNDO_MS]:
 * it says so, a bar runs down the time left, and OK puts the card back.
 */
@Composable
fun NixieUndoCard(
    title: String,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sizes = LocalCardSizes.current
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    val left = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        left.animateTo(0f, tween(ContinueWatchingRemovals.UNDO_MS.toInt(), easing = LinearEasing))
    }
    GlacierCard(onClick = onUndo, modifier = modifier.width(sizes.continueWidth.dp)) { focused ->
        Box(
            Modifier
                .size(sizes.continueWidth.dp, sizes.continueHeight.dp)
                .focusFrame(focused, shape)
                .clip(shape)
                .background(GlacierColors.GlassFill2),
        ) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.nixie_cw_removed),
                    style = GlacierText.body(22, FontWeight.SemiBold),
                    color = GlacierColors.Ice,
                )
                Text(
                    stringResource(R.string.nixie_cw_undo),
                    style = GlacierText.body(17),
                    color = if (focused) LocalAccent.current.main else GlacierColors.Mist,
                )
            }
            ProgressBar(
                progress = left.value,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
                    .height(5.dp),
            )
        }
        // tv-home-two-rows: as tall as the card it stands for, so the rows (at the foot of Home) never move when it
        // comes or goes: a theme that draws its still cards with the words on them has no caption under them.
        if (NixieTheme.slots.stillCard == null) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = GlacierText.body(19, FontWeight.SemiBold), color = GlacierColors.Mist, maxLines = 1)
                // ContinueCard's second line (its subtitle), left empty.
                Text("", style = GlacierText.body(16), maxLines = 1)
            }
        }
    }
}

/**
 * The Remove sheet for [item] (none while null). Once it closes, Remove or Cancel, the focus goes back
 * to the card's place ([returnTo]), where the Undo card now stands after a removal.
 */
@Composable
fun NixieRemoveSheetHost(
    item: MediaItem?,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
    returnTo: (MediaItem) -> FocusRequester?,
) {
    var back by remember { mutableStateOf<MediaItem?>(null) }
    item?.let {
        NixieRemoveSheet(
            it.parentTitle ?: it.title,
            onRemove = {
                back = it
                onRemove()
            },
            onCancel = {
                back = it
                onCancel()
            },
        )
    }
    LaunchedEffect(back) {
        val card = back ?: return@LaunchedEffect
        withFrameNanos { }
        returnTo(card)?.let { runCatching { it.requestFocus() } }
        back = null
    }
}

/** Holding OK on a Continue watching card: take it out of the row. Cancel has the focus. */
@Composable
fun NixieRemoveSheet(
    title: String,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    ModalSheet(onDismiss = onCancel) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.nixie_cw_remove_title),
                style = GlacierText.display(28),
                color = GlacierColors.Ice,
            )
            Text(
                stringResource(R.string.nixie_cw_remove_body, title),
                style = GlacierText.body(19),
                color = GlacierColors.Mist,
            )
        }
        val cancelFocus = remember { FocusRequester() }
        // Holding OK opened the sheet: its repeats and its release must not press a button, and the
        // focus starts on Cancel, the harmless one.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.ignoreHeldOk()) {
            PillButton(
                stringResource(R.string.action_cancel),
                onClick = onCancel,
                modifier = Modifier.focusRequester(cancelFocus),
            )
            PillButton(stringResource(R.string.nixie_cw_remove), onClick = onRemove, primary = true)
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { cancelFocus.requestFocus() }
        }
    }
}

/**
 * A row whose titles changed (an Undo card running out, a live update, back from the player) keeps the
 * focus on the same title while it is there, else on its neighbour, the next one or else the one before.
 * The row is never scrolled back: the focus, kept or moved, brings its card into view. [keys] are the
 * row's focus keys in order; [lastFocus] is the key the screen restores on its way back.
 */
@Composable
fun NixieKeepRowFocus(
    keys: List<String>?,
    inRow: Boolean,
    lastFocus: () -> String?,
    setLastFocus: (String?) -> Unit,
    requester: (String) -> FocusRequester?,
) {
    var before by rememberSaveable { mutableStateOf<String?>(null) }
    // Where the focus was as the titles changed: a card that goes takes the focus with it, somewhere.
    val hadFocus = remember(keys) { inRow }
    val focusedThen = remember(keys) { lastFocus() }
    LaunchedEffect(keys) {
        val after = keys ?: return@LaunchedEffect
        val was = before?.split(KEY_SEPARATOR)
        before = after.joinToString(KEY_SEPARATOR)
        val last = focusedThen?.takeIf { was != null && was != after && it in was } ?: return@LaunchedEffect
        val target = focusAfterChange(was.orEmpty(), after, last)
        setLastFocus(target)
        val moved = target != null && target != last
        if (moved && (inRow || hadFocus)) {
            // tv-home-two-rows: the card that went took the focus along, and the rows' column may hand it to its first
            // card a frame later (its restorer finds the card gone); asked again until the neighbour has it.
            repeat(KEEP_FRAMES) {
                withFrameNanos { }
                if (lastFocus() == target && it > 0) return@LaunchedEffect
                target?.let(requester)?.let { runCatching { it.requestFocus() } }
            }
        }
    }
}

/** How many frames [NixieKeepRowFocus] asks for the neighbour's focus at most. */
private const val KEEP_FRAMES = 4

/** Where the focus goes when a row's keys change from [before] to [after]: see [NixieKeepRowFocus]. */
fun focusAfterChange(
    before: List<String>,
    after: List<String>,
    focused: String,
): String? {
    if (focused in after) return focused
    val at = before.indexOf(focused)
    val next = before.drop(at + 1).firstOrNull { it in after }
    val previous = before.take(at.coerceAtLeast(0)).lastOrNull { it in after }
    return next ?: previous ?: after.firstOrNull()
}

private const val KEY_SEPARATOR = "\n"

/** Home's own rows; each is kept by [NixieKeepRowFocus] through [NixieKeepRowsFocus]. */
enum class NixieRow { ContinueWatching, UpNext }

/** A card's focus key in [row]: what the screen remembers and restores. */
fun nixieRowKey(row: NixieRow, id: UUID): String = when (row) {
    NixieRow.ContinueWatching -> "continue-$id"
    NixieRow.UpNext -> "nixie-up-next-$id"
}

/** Every Nixie row of [content] with its cards' focus keys, in order; null before the content. */
fun nixieRows(content: HomeContent?): Map<NixieRow, List<String>?> = NixieRow.entries.associateWith { row ->
    val items = when (row) {
        NixieRow.ContinueWatching -> content?.continueWatching
        NixieRow.UpNext -> content?.upNext
    }
    items?.map { nixieRowKey(row, it.id) }
}

/** Which of Home's own rows has the focus, as the rows report it. */
class NixieRowsFocus {
    private val focused = mutableStateMapOf<NixieRow, Boolean>()

    fun report(row: NixieRow, hasFocus: Boolean) {
        focused[row] = hasFocus
    }

    fun has(row: NixieRow): Boolean = focused[row] == true
}

/**
 * [NixieKeepRowFocus] for every Nixie row of [content]: a card that goes, in any of them, hands the focus to its
 * neighbour, never to the top bar. [inRow] says whether a row has the focus.
 */
@Composable
fun NixieKeepRowsFocus(
    content: HomeContent?,
    inRow: (NixieRow) -> Boolean,
    lastFocus: () -> String?,
    setLastFocus: (String?) -> Unit,
    requester: (String) -> FocusRequester?,
) {
    nixieRows(content).forEach { (row, keys) ->
        key(row) { NixieKeepRowFocus(keys, inRow(row), lastFocus, setLastFocus, requester) }
    }
}
