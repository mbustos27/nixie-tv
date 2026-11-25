// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.startGradient
import io.github.glacier_jellyfin.androidtv.home.SpotlightHeight
import io.github.glacier_jellyfin.androidtv.home.continueSubtitle
import io.github.glacier_jellyfin.androidtv.parental.showsLock
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.ContinueCard
import io.github.glacier_jellyfin.androidtv.ui.FactsRow
import io.github.glacier_jellyfin.androidtv.ui.ImagePageGround
import io.github.glacier_jellyfin.androidtv.ui.KenBurns
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.episodeText
import io.github.glacier_jellyfin.androidtv.ui.runtimeText
import io.github.glacier_jellyfin.androidtv.ui.yearText
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import java.util.UUID
import kotlinx.coroutines.delay

/*
 * Nixie (tv-home-two-rows): Home is Continue watching, then Up next, on one page and nothing else (the owner,
 * 2026-11-22: "continue watching then up next ... on one page"). The hero is no row of its own: it shows the focused
 * card's picture and facts, in the theme's hero slots, and asks the server for nothing. Glacier's HomeScreen calls
 * [NixieHomePage] through a hook marked "// Nixie:".
 */

/** Room for the top bar above the page: its 34 dp margin, its 54 dp entries and a gap. */
private const val TOP_BAR_ROOM = 112

/** How long a card keeps the focus before the hero's picture follows it: a quick pass along a row asks for none. */
private const val PICTURE_SETTLE_MS = 250L

/** How far above the rows the picture starts to fade into the page, so their titles read on any picture. */
private const val SCRIM_ABOVE = 140

/** The test tags of the page's parts (HomeTwoRowsTest, NixieHomeShotsTest). */
object NixieHomeTags {
    const val CONTINUE_EMPTY = "nixie-home-continue-empty"
    const val UP_NEXT_EMPTY = "nixie-home-up-next-empty"
    const val ALL_EMPTY = "nixie-home-empty"
    const val ROWS = "nixie-home-rows"
    const val HERO = "nixie-hero"
}

/**
 * Home's page: the hero (the focused card's picture and facts) over Continue watching, then Up next. A row that is
 * empty says so in a line; with both empty, the page says what to do next instead. [continueWatchingIn] and
 * [upNextIn]: the row's answer is known (saved or from the server), so an empty one is really empty. [rowsModifier]
 * goes on the rows' column (Home's focus handles), [cardModifier] on each card by its focus key ([nixieRowKey]).
 */
@Composable
fun NixieHomePage(
    continueWatching: List<MediaItem>,
    upNext: List<MediaItem>,
    continueWatchingIn: Boolean,
    upNextIn: Boolean,
    onOpen: (MediaItem) -> Unit,
    onAskRemove: (MediaItem) -> Unit,
    onUndo: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    rowsModifier: Modifier = Modifier,
    pending: Set<UUID> = emptySet(),
    continueRow: LazyListState = rememberLazyListState(),
    upNextRow: LazyListState = rememberLazyListState(),
    rowsFocus: NixieRowsFocus = remember { NixieRowsFocus() },
    onContinueFocus: (Boolean) -> Unit = {},
    cardModifier: (String) -> Modifier = { Modifier },
) {
    // The focused card's id, kept across a return from another page so the hero comes back on it.
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = continueWatching.filterNot { it.id in pending } + upNext
    val hero = shown.firstOrNull { it.id.toString() == focusedId } ?: shown.firstOrNull()
    val allEmpty = continueWatchingIn && upNextIn && continueWatching.isEmpty() && upNext.isEmpty()
    var rowsTop by remember { mutableFloatStateOf(Float.NaN) }
    val focusModifier = { item: MediaItem, key: String ->
        cardModifier(key).onFocusChanged { if (it.isFocused) focusedId = item.id.toString() }
    }
    Box(modifier.fillMaxSize().background(ImagePageGround)) {
        if (hero != null) {
            NixieHeroArt(hero, Modifier.fillMaxWidth().height(SpotlightHeight.dp))
            val ground = ImagePageGround
            Canvas(Modifier.fillMaxSize()) {
                val top = rowsTop.takeUnless { it.isNaN() } ?: return@Canvas
                val from = top - SCRIM_ABOVE.dp.toPx()
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to ground.copy(alpha = 0.9f),
                        startY = from,
                        endY = top + 24.dp.toPx(),
                    ),
                    topLeft = Offset(0f, from),
                    size = Size(size.width, size.height - from),
                )
            }
        }
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(TOP_BAR_ROOM.dp))
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                when {
                    allEmpty -> NixieHomeEmpty(Modifier.align(Alignment.Center))

                    hero != null -> NixieHeroInfo(
                        hero,
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = PageEdge.dp, end = PageEdge.dp, bottom = 4.dp),
                    )
                }
            }
            if (!allEmpty) {
                Column(
                    rowsModifier
                        .testTag(NixieHomeTags.ROWS)
                        .onGloballyPositioned { rowsTop = it.positionInParent().y },
                ) {
                    MediaRow(
                        title = stringResource(R.string.home_continue_watching),
                        index = 0,
                        state = continueRow,
                        modifier = Modifier.onFocusChanged { onContinueFocus(it.hasFocus) },
                    ) {
                        if (continueWatching.isEmpty() && continueWatchingIn) {
                            item(key = "empty") {
                                EmptyLine(R.string.nixie_home_cw_empty, NixieHomeTags.CONTINUE_EMPTY)
                            }
                        }
                        items(continueWatching, key = { it.id }) { item ->
                            val key = nixieRowKey(NixieRow.ContinueWatching, item.id)
                            // A removed card shows its Undo card for a while.
                            if (item.id in pending) {
                                NixieUndoCard(
                                    item.parentTitle ?: item.title,
                                    onUndo = { onUndo(item) },
                                    modifier = cardModifier(key),
                                )
                                return@items
                            }
                            ContinueCard(
                                title = cardTitle(item),
                                subtitle = continueSubtitle(item),
                                // The show's art, not the episode still (Glacier's choice).
                                imageUrl = item.showThumbUrl ?: item.posterUrl,
                                progress = item.progress,
                                onClick = { onOpen(item) },
                                modifier = focusModifier(item, key),
                                locked = item.showsLock(),
                                // Holding OK offers Remove, with an Undo.
                                onLongClick = { onAskRemove(item) },
                            )
                        }
                    }
                    MediaRow(
                        title = stringResource(R.string.nixie_home_up_next),
                        index = 1,
                        state = upNextRow,
                        modifier = Modifier.onFocusChanged { rowsFocus.report(NixieRow.UpNext, it.hasFocus) },
                    ) {
                        if (upNext.isEmpty() && upNextIn) {
                            item(key = "empty") {
                                EmptyLine(R.string.nixie_home_up_next_empty, NixieHomeTags.UP_NEXT_EMPTY)
                            }
                        }
                        items(upNext, key = { it.id }) { item ->
                            ContinueCard(
                                title = cardTitle(item),
                                subtitle = upNextSubtitle(item),
                                imageUrl = item.showThumbUrl ?: item.posterUrl,
                                progress = null,
                                onClick = { onOpen(item) },
                                modifier = focusModifier(item, nixieRowKey(NixieRow.UpNext, item.id)),
                                locked = item.showsLock(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The hero's picture: the focused card's backdrop in the theme's hero (Drive-in's outdoor screen, Editorial's plate,
 * Cinematic's full bleed), else Glacier's, with Glacier's slow zoom and the theme's fade at its foot. The picture
 * follows a card that kept the focus for [PICTURE_SETTLE_MS].
 */
@Composable
private fun NixieHeroArt(item: MediaItem, modifier: Modifier) {
    val url by produceState(item.backdropUrl, item.backdropUrl) {
        delay(PICTURE_SETTLE_MS)
        value = item.backdropUrl
    }
    Box(modifier.clipToBounds()) {
        val backdrop = NixieTheme.slots.heroBackdrop
        if (backdrop != null) {
            backdrop(url) { picture, artModifier ->
                Crossfade(targetState = picture, animationSpec = tween(CROSSFADE_MS), label = "hero") { shown ->
                    KenBurns { Artwork(shown, artModifier) }
                }
            }
        } else {
            Crossfade(targetState = url, animationSpec = tween(CROSSFADE_MS), label = "hero") { shown ->
                KenBurns { Artwork(shown, Modifier.fillMaxSize()) }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .startGradient(0f to Color(0xF50A1420), 0.38f to Color(0xC20A1420), 0.72f to Color(0x1A0A1420)),
            )
        }
        val fade = NixieTheme.slots.heroFade
        if (fade != null) {
            Canvas(Modifier.matchParentSize()) { fade() }
        } else {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, GlacierColors.Void))),
            )
        }
    }
}

private const val CROSSFADE_MS = 600

/**
 * The hero's words: the show (or title), the episode, the facts and two lines of the overview. No buttons. They echo
 * the focused card, which says its own title: one description stands for them (and their test tag), so nothing is
 * found or read twice.
 */
@Composable
private fun NixieHeroInfo(item: MediaItem, modifier: Modifier) {
    val isEpisode = item.kind == ItemKind.Episode
    val title = cardTitle(item)
    val episode = if (isEpisode) listOfNotNull(episodeText(item), item.title).joinToString(" · ") else null
    val description = listOfNotNull(title, episode, item.overview).joinToString(". ")
    Column(
        modifier
            .width(HERO_TEXT_WIDTH.dp)
            .clearAndSetSemantics {
                contentDescription = description
                testTag = NixieHomeTags.HERO
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            style = GlacierText.display(TITLE_SIZE).copy(lineHeight = TITLE_LINE.sp, shadow = TitleShadow),
            color = GlacierColors.Ice,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (episode != null) {
            Text(
                episode,
                style = GlacierText.body(22, FontWeight.SemiBold),
                color = GlacierColors.Ice,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FactsRow(
            item,
            listOfNotNull(
                yearText(item),
                item.runtimeMinutes?.takeIf { it > 0 }?.let { runtimeText(it) },
                item.genres.firstOrNull(),
            ),
        )
        item.overview?.let {
            Text(
                it,
                style = GlacierText.body(19).copy(lineHeight = 29.sp),
                color = GlacierColors.Ice,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(OVERVIEW_WIDTH.dp),
            )
        }
    }
}

private const val HERO_TEXT_WIDTH = 1100
private const val TITLE_SIZE = 54
private const val TITLE_LINE = 60
private val TitleShadow = Shadow(Color(0x99000000), blurRadius = 30f)

/** A card's title: an episode's show, else the title itself. */
private fun cardTitle(item: MediaItem): String =
    if (item.kind == ItemKind.Episode) item.parentTitle ?: item.title else item.title
private const val OVERVIEW_WIDTH = 900

/** An empty row's line, where its cards would be. */
@Composable
private fun EmptyLine(text: Int, tag: String) {
    Text(
        stringResource(text),
        style = GlacierText.body(21),
        color = GlacierColors.Mist,
        modifier = Modifier.testTag(tag).padding(vertical = 6.dp),
    )
}

/** Both rows empty: what to do next, in place of the rows. */
@Composable
private fun NixieHomeEmpty(modifier: Modifier) {
    Column(
        modifier.width(EMPTY_WIDTH.dp).testTag(NixieHomeTags.ALL_EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.nixie_home_empty_title),
            style = GlacierText.display(38),
            color = GlacierColors.Ice,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.nixie_home_empty_body),
            style = GlacierText.body(22).copy(lineHeight = 34.sp),
            color = GlacierColors.Mist,
            textAlign = TextAlign.Center,
        )
    }
}

private const val EMPTY_WIDTH = 900
