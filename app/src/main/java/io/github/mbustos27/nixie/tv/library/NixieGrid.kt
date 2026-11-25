// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.size.Scale
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.ui.CardSizes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/*
 * TV-S2 (the owner's TV session, 2026-11-21): scrolling a big library had about one janky frame in nine on a TV box,
 * from UI-thread and draw work. These are the library grid's helpers for it: the grid's cell types, its artwork asked
 * at the card's own size, and the next rows' artwork fetched before they scroll in.
 */

/** What each cell of the library grid is, so a cell scrolled in reuses the composition of one of its kind. */
object NixieGridCells {
    const val HEADING = "heading"
    const val CARD = "card"
    const val SONG = "song"

    fun typeOf(item: MediaItem): String = if (item.kind == ItemKind.Song) SONG else CARD

    /**
     * What the grid composes beyond the screen: about two rows of posters ahead, made a card at a time while frames
     * have time to spare, so a row that scrolls in is ready; one row behind, so going back up composes nothing.
     */
    @OptIn(ExperimentalFoundationApi::class)
    val cacheWindow: LazyLayoutCacheWindow = LazyLayoutCacheWindow(ahead = 900.dp, behind = 450.dp)

    /**
     * TV-S13: after an A–Z jump the rows start at the letter's first title ([rowPhase]: the list's place of it, modulo the
     * columns), so no row moves when the titles before it come. Scrolled back to the very top (the list's first title at
     * [offset] 0), its first row then holds [rowPhase] titles: the last of them takes the rest of the row. Null for every
     * other title (one cell).
     */
    fun span(
        offset: Int,
        rowPhase: Int,
        index: Int,
    ): (LazyGridItemSpanScope.() -> GridItemSpan)? {
        if (offset != 0 || rowPhase <= 0 || index != rowPhase - 1) return null
        return { GridItemSpan((maxLineSpan - index).coerceAtLeast(1)) }
    }
}

/** TV-S13: the card that [ends] a short first row keeps its own width at the start of its wider cell (`span`). */
fun Modifier.nixieRowEnd(ends: Boolean): Modifier = if (ends) wrapContentWidth(Alignment.Start) else this

/**
 * tv-1.0-r1 (the quick basics check's Minor): Right on the [last] card of a list, at the end of a short last row, went up
 * a row (to the row above or the top bar). It goes to the A–Z [rail] as a group's last card does (Glacier's rule), or,
 * with none, stays where it is, as Glacier's last folder does. Used by the library grid and the Libraries page.
 */
fun Modifier.nixieListEnd(last: Boolean, rail: FocusRequester?): Modifier =
    if (last) focusProperties { end = rail ?: FocusRequester.Cancel } else this

/**
 * tv-gate-b (the basics gate, rule 12, "Back is instant"): a library page opened from another page (a library card on
 * Libraries, a genre, a title's library) goes back to that page with one Back from its cards. Glacier's way, from the
 * cards to the tabs, then to the top bar, then back, took three presses. A page the top bar opened ([inNavBar], or
 * [fromBar]: a library starred into the bar, from its entry) keeps it: Back goes up to its tabs and the bar first. The
 * review: a starred library opened from Libraries or a title is another page's too, and goes back in one press.
 */
fun nixieBackToPage(inNavBar: Boolean, fromBar: Boolean): Boolean = !inNavBar && !fromBar

/** The library grid's artwork sizes in pixels: posters (and square covers) and 16:9 cards. */
@Immutable
data class GridArtSizes(
    val poster: Int,
    val posterHeight: Int,
    val wide: Int,
    val wideHeight: Int,
) {
    /** The size [item]'s artwork is drawn at in the grid (see GridCard's shapes). */
    fun of(item: MediaItem): Pair<Int, Int> = when (item.kind) {
        ItemKind.MusicVideo -> wide to wideHeight
        ItemKind.Album, ItemKind.Playlist, ItemKind.Artist -> poster to poster
        else -> poster to posterHeight
    }
}

@Composable
fun rememberGridArtSizes(sizes: CardSizes, density: Density): GridArtSizes = remember(sizes, density) {
    with(density) {
        GridArtSizes(
            poster = sizes.posterWidth.dp.roundToPx(),
            posterHeight = sizes.posterHeight.dp.roundToPx(),
            wide = sizes.wideWidth.dp.roundToPx(),
            wideHeight = (sizes.wideWidth * 9 / 16).dp.roundToPx(),
        )
    }
}

object NixieGridArt {
    /** Widths the grid asks the server for, so a server and the image cache keep a few sizes of each picture. */
    const val STEP = 120

    private val maxWidth = Regex("([?&])maxWidth=(\\d+)", RegexOption.IGNORE_CASE)

    /**
     * [url] asked [widthPx] wide, rounded up to a [STEP]: a 216-pixel poster asks for 240, not the 480 the rows ask
     * for. Never wider than [url] asked, and unchanged when it names no width.
     */
    fun sized(url: String?, widthPx: Int): String? {
        if (url == null || widthPx <= 0) return url
        val match = maxWidth.find(url) ?: return url
        val asked = match.groupValues[2].toIntOrNull() ?: return url
        val wanted = (widthPx + STEP - 1) / STEP * STEP
        if (wanted >= asked) return url
        return url.replaceRange(match.range, "${match.groupValues[1]}maxWidth=$wanted")
    }

    /**
     * TV-S1 on a real server: what [item]'s card asks for, the same for the card and the rows fetched ahead. A film's,
     * show's or collection's poster is asked as Jellyfin's web client asks for it in a 1920-wide window ([poster]); the
     * rest at the card's width ([sized]).
     */
    fun cardUrl(item: MediaItem, art: GridArtSizes): String? = when (item.kind) {
        ItemKind.MusicVideo -> sized(item.thumbUrl ?: item.posterUrl, art.wide)
        ItemKind.Movie, ItemKind.Series, ItemKind.Collection -> poster(item.posterUrl, art.poster)
        else -> sized(item.posterUrl, art.poster)
    }

    /**
     * [url] asked as Jellyfin's web client asks for a poster card in a 1920-wide window (fillWidth 213, fillHeight 320,
     * quality 96, WebP), or at twice its pixels (426 x 639) for a card up to about [webPosters]' width: the server
     * keeps each picture it makes by its size, quality and format, so a poster any web user has seen comes from its
     * cache instead of being made again (on the test instance 14 first-sight posters took about 0.35 s, cached about
     * 10 ms; the old 240 or 480 wide JPEG at quality 90 matched no other client's). Wider cards keep [sized].
     */
    fun poster(url: String?, widthPx: Int): String? {
        if (url == null || widthPx <= 0) return url
        val match = maxWidth.find(url) ?: return url
        val shape = webPosters.firstOrNull { widthPx * SLACK_DIV <= it.first * SLACK_MUL } ?: return sized(url, widthPx)
        val (width, height) = shape
        return url.replaceRange(
            match.range,
            "${match.groupValues[1]}fillHeight=$height&fillWidth=$width&quality=$WEB_QUALITY&format=Webp",
        )
    }

    /** The web client's poster card in a 1920-wide window (213 = 1920 / 9 cards; 320 = 213 / (2 / 3), rounded up), x1 and x2. */
    private val webPosters = listOf(213 to 320, 426 to 639)

    private const val WEB_QUALITY = 96

    /** A card up to a tenth wider than the web's picture still takes it (216 or 188 pixels take 213). */
    private const val SLACK_MUL = 11
    private const val SLACK_DIV = 10

    /**
     * The titles whose artwork to fetch ahead: the [rows] rows of [columns] after the last title on screen
     * ([lastVisible] the last grid index shown, [positions] each title's grid index). Empty when nothing is shown.
     */
    fun ahead(
        lastVisible: Int,
        positions: IntArray,
        columns: Int,
        rows: Int,
    ): IntRange {
        if (lastVisible < 0 || positions.isEmpty()) return IntRange.EMPTY
        // The last title at or before the last cell shown (positions only go up).
        var lo = 0
        var hi = positions.size - 1
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (positions[mid] <= lastVisible) {
                last = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val from = last + 1
        val to = minOf(positions.size - 1, last + rows * columns)
        return if (from > to) IntRange.EMPTY else from..to
    }

    /** Rows of artwork fetched ahead of the grid. */
    const val ROWS_AHEAD = 3
}

/**
 * Fetches the artwork of the [NixieGridArt.ROWS_AHEAD] rows after the last one on screen, at the size the cards draw it,
 * into the image cache: a row that scrolls in shows its posters at once instead of loading them then. The visible row
 * goes first: nothing is fetched ahead until the grid has stood still a moment ([PREFETCH_SETTLE_MS], so a held key
 * fetches nothing ahead), and what falls out of the rows ahead, or is still coming when the screen is left, is cancelled.
 */
@Composable
fun NixiePrefetchArt(
    gridState: LazyGridState,
    items: List<MediaItem>,
    positions: IntArray,
    columns: Int,
    art: GridArtSizes,
    enabled: Boolean,
) {
    val context = LocalContext.current
    val currentItems by rememberUpdatedState(items)
    val currentPositions by rememberUpdatedState(positions)
    LaunchedEffect(gridState, columns, art, enabled) {
        if (!enabled) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        lateinit var plan: NixiePrefetchPlan<Disposable>
        plan = NixiePrefetchPlan(
            start = { ahead ->
                val done = { plan.finished(ahead.url) }
                loader.enqueue(
                    ImageRequest.Builder(context)
                        .data(ahead.url)
                        .size(ahead.width, ahead.height)
                        .scale(Scale.FILL)
                        .listener(onSuccess = { _, _ -> done() }, onError = { _, _ -> done() })
                        .build(),
                )
            },
            cancel = { it.dispose() },
        )
        try {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .distinctUntilChanged()
                .collectLatest { last ->
                    delay(PREFETCH_SETTLE_MS)
                    val titles = currentItems
                    val ahead = NixieGridArt.ahead(last, currentPositions, columns, NixieGridArt.ROWS_AHEAD)
                    plan.want(
                        ahead.mapNotNull { titles.getOrNull(it) }.mapNotNull { item ->
                            val (width, height) = art.of(item)
                            NixieGridArt.cardUrl(item, art)?.let { AheadArt(it, width, height) }
                        },
                    )
                }
        } finally {
            plan.cancelAll()
        }
    }
}

/**
 * TV-S13: titles that come in before those on screen (the rows above an A–Z jump's letter, loaded once its place in the
 * list is known) leave the titles on screen where they were. A jump's page now starts at its letter, right under the
 * toolbar: the grid would keep the toolbar in place and push the letter's rows down, out of view. The first title shown
 * before keeps its place on screen, in the same frame ([LazyGridState.requestScrollToItem]); the toolbar goes up and
 * the titles before it fill the space above. [positions] are the titles' places in the grid.
 */
@Composable
fun NixieKeepRowsInPlace(
    gridState: LazyGridState,
    items: List<MediaItem>,
    positions: IntArray,
) {
    // The first title of the list composed last: titles came in before it when it is further down now (its place in
    // the list may have come in the same step, so the offset says nothing).
    val before = remember { arrayOfNulls<Any>(1) }
    val oldFirst = before[0]
    before[0] = items.firstOrNull()?.id
    if (oldFirst == null) return
    val moved = items.indexOfFirst { it.id == oldFirst }.takeIf { it > 0 } ?: return
    val shown = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == oldFirst } ?: return
    val position = positions.getOrNull(moved) ?: return
    // Offsets count from the end of the grid's top padding, as the scroll's does.
    gridState.requestScrollToItem(position, -shown.offset.y)
}

/** A picture to fetch ahead: its address and the size the card draws it at. */
data class AheadArt(
    val url: String,
    val width: Int,
    val height: Int,
)

/**
 * Which pictures are being fetched ahead of the grid: [want] starts those of the rows ahead, nearest first, and cancels
 * those no longer ahead; a picture [finished] is not fetched again.
 */
class NixiePrefetchPlan<T>(private val start: (AheadArt) -> T, private val cancel: (T) -> Unit) {
    private val coming = LinkedHashMap<String, T>()
    private val fetched = LinkedHashSet<String>()

    fun want(ahead: List<AheadArt>) {
        val wanted = ahead.mapTo(HashSet()) { it.url }
        coming.keys.filter { it !in wanted }.forEach { url -> coming.remove(url)?.let(cancel) }
        for (art in ahead) {
            if (art.url !in coming && art.url !in fetched) coming[art.url] = start(art)
        }
    }

    fun finished(url: String) {
        coming.remove(url)
        fetched += url
        if (fetched.size > FETCHED_KEPT) fetched.remove(fetched.first())
    }

    fun cancelAll() {
        coming.values.forEach(cancel)
        coming.clear()
    }

    /** The URLs still coming, nearest first (for the tests). */
    val pending: List<String> get() = coming.keys.toList()
}

/** How long the grid stands still before the rows ahead are fetched: the row on screen goes first. */
const val PREFETCH_SETTLE_MS = 300L

/** The most pictures remembered as fetched ahead. */
private const val FETCHED_KEPT = 2_000

/** Runs [content] in a composition scope of its own: a state it reads composes only it again, not its caller. */
@Composable
fun NixieOwnScope(content: @Composable () -> Unit) = content()
