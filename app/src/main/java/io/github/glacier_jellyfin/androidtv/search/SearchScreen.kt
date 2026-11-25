package io.github.glacier_jellyfin.androidtv.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrItem
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrMediaType
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.KeyCap
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.SystemTextInput
import io.github.glacier_jellyfin.androidtv.navigation.LocalLibraryKinds
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.TopNav
import io.github.glacier_jellyfin.androidtv.parental.showsLock
import io.github.glacier_jellyfin.androidtv.seerr.seerrStatusLabel
import io.github.glacier_jellyfin.androidtv.setup.InputField
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.LocalCardSizes
import io.github.glacier_jellyfin.androidtv.ui.PosterCard
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.ui.yearText
import io.github.mbustos27.nixie.tv.data.search.SearchKind
import io.github.mbustos27.nixie.tv.library.nixieListEnd
import io.github.mbustos27.nixie.tv.play.SearchChips
import io.github.mbustos27.nixie.tv.play.ShowMoreCard
import io.github.mbustos27.nixie.tv.play.searchCaption
import io.github.mbustos27.nixie.tv.search.KeepBestInView

/** Design keypad: six columns of letters and digits, then keyboard, space and delete. */
private val KeypadRows = listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ0123", "456789")
private const val KeyWidth = 94
private const val KeyHeight = 76
private const val KeyGap = 12
private const val Columns = 4

// Nixie: the "Show N more" card's key in the results grid.
private const val SHOW_MORE_KEY = "nixie-show-more"

@Composable
fun SearchScreen(onNavigate: (UiEvent.Navigate) -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    val keyFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    var systemKeyboard by rememberSaveable { mutableStateOf(false) }
    // Focus returns to whichever control opened the system keyboard.
    var openedFromField by rememberSaveable { mutableStateOf(false) }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    // Back from a title lands on its card again, not on the first control.
    var lastOpened by rememberSaveable { mutableStateOf<String?>(null) }
    val restoreFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (!initialFocusDone) {
            initialFocusDone = runCatching { keyFocus.requestFocus() }.isSuccess
            return@LaunchedEffect
        }
        val index = resultKeys(state).indexOf(lastOpened)
        if (index < 0) return@LaunchedEffect
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            gridState.scrollToItem(index)
            withFrameNanos { }
        }
        runCatching { restoreFocus.requestFocus() }
    }

    // Back among the results first goes to the keypad, from there to the navigation bar.
    val navFocus = remember { FocusRequester() }
    var inResults by remember { mutableStateOf(false) }
    var inInput by remember { mutableStateOf(false) }
    BackHandler(enabled = inResults) { runCatching { keyFocus.requestFocus() } }
    BackHandler(enabled = inInput) { runCatching { navFocus.requestFocus() } }
    // Nixie (TV-S4, review): a better match that arrives late shows at the top, unless the focus is in the grid.
    KeepBestInView(gridState, state.results.firstOrNull()?.resultKey, focusInGrid = { inResults })

    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 80.dp, end = 80.dp, top = 150.dp),
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            Column(
                Modifier.width(620.dp).onFocusChanged { inInput = it.hasFocus },
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                // Same field as sign-in: OK opens the system keyboard, Up reaches the top navigation.
                InputField(
                    label = null,
                    icon = GlacierIcons.Search,
                    value = state.query,
                    placeholder = stringResource(R.string.search_placeholder),
                    editing = systemKeyboard,
                    onClick = {
                        openedFromField = true
                        systemKeyboard = true
                    },
                    focusRequester = fieldFocus,
                    down = keyFocus,
                )
                Keypad(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    onSystemKeyboard = {
                        openedFromField = false
                        systemKeyboard = true
                    },
                    firstKeyFocus = keyFocus,
                )
            }
            Results(
                state = state,
                gridState = gridState,
                lastOpened = lastOpened,
                restoreFocus = restoreFocus,
                onOpen = { item ->
                    lastOpened = item.resultKey
                    viewModel.open(item)
                },
                onOpenSeerr = { item ->
                    lastOpened = item.resultKey
                    viewModel.open(item)
                },
                onRetry = viewModel::retry,
                onPickChip = viewModel::pickChip, // Nixie: type chips and "Show N more"
                onShowMore = viewModel::showMore,
                modifier = Modifier.weight(1f).onFocusChanged { inResults = it.hasFocus },
            )
        }

        TopNav(
            active = NavTarget.Search,
            kinds = LocalLibraryKinds.current,
            userName = state.userName,
            onSelect = viewModel::onNav,
            focusRequester = navFocus,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp),
        )
    }

    SystemTextInput(
        text = state.query,
        onTextChange = viewModel::setQuery,
        open = systemKeyboard,
        onClose = { done ->
            systemKeyboard = false
            (if (openedFromField) fieldFocus else keyFocus).requestFocus()
            if (done) viewModel.searchNow() // Nixie (TV-S4, review): Enter searches at once, one character too
        },
    )
}

@Composable
private fun Keypad(
    query: String,
    onQueryChange: (String) -> Unit,
    onSystemKeyboard: () -> Unit,
    firstKeyFocus: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KeyGap.dp)) {
        KeypadRows.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap.dp)) {
                row.forEachIndexed { index, char ->
                    KeyCap(
                        onClick = { onQueryChange(typeChar(query, char)) },
                        width = KeyWidth,
                        height = KeyHeight,
                        modifier = if (rowIndex == 0 && index == 0) {
                            Modifier.focusRequester(firstKeyFocus)
                        } else {
                            Modifier
                        },
                    ) { color -> Text(char.toString(), style = GlacierText.display(24), color = color) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap.dp)) {
            KeyCap(onClick = onSystemKeyboard, width = span(2), height = KeyHeight) { color ->
                Icon(
                    GlacierIcons.Keyboard,
                    contentDescription = stringResource(R.string.keyboard_system),
                    tint = color,
                    modifier = Modifier.size(30.dp),
                )
            }
            KeyCap(
                onClick = { if (query.isNotEmpty() && !query.endsWith(' ')) onQueryChange("$query ") },
                width = span(3),
                height = KeyHeight,
            ) { color ->
                Text(
                    stringResource(R.string.keyboard_space),
                    style = GlacierText.body(23, FontWeight.SemiBold),
                    color = color,
                )
            }
            KeyCap(onClick = { onQueryChange(query.dropLast(1)) }, width = KeyWidth, height = KeyHeight) { color ->
                Icon(
                    GlacierIcons.Backspace,
                    contentDescription = stringResource(R.string.keyboard_delete),
                    tint = color,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

private fun span(keys: Int) = KeyWidth * keys + KeyGap * (keys - 1)

/** Keys show capitals; typed text is capitalised per word so it reads like a title. */
internal fun typeChar(query: String, char: Char): String {
    val wordStart = query.isEmpty() || query.last() == ' '
    return query + if (wordStart) char.uppercaseChar() else char.lowercaseChar()
}

@Composable
private fun Results(
    state: SearchState,
    gridState: LazyGridState,
    lastOpened: String?,
    restoreFocus: FocusRequester,
    onOpen: (MediaItem) -> Unit,
    onOpenSeerr: (SeerrItem) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
    // Nixie: type chips and "Show N more".
    onPickChip: (SearchKind?) -> Unit = {},
    onShowMore: () -> Unit = {},
) {
    // Nixie: after "Show N more", focus goes to the first title it showed.
    val reveal = remember { FocusRequester() }
    var revealKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revealKey, state.results) {
        if (revealKey == null || state.results.none { it.resultKey == revealKey }) return@LaunchedEffect
        withFrameNanos { }
        runCatching { reveal.requestFocus() }
        revealKey = null
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        val count = state.results.size + state.seerr.size
        val title = when {
            state.resultsFor == null -> stringResource(R.string.search_suggestions)
            else -> pluralStringResource(R.plurals.search_results, count, count, state.resultsFor)
        }
        Text(
            title,
            style = GlacierText.display(28),
            color = GlacierColors.Ice,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SearchChips(state.ranked?.chips.orEmpty(), state.chip, onPickChip) // Nixie: "All", "Shows", "Movies" …
        when {
            state.failed -> Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Text(stringResource(R.string.search_error), style = GlacierText.body(21), color = GlacierColors.Mist)
                PillButton(stringResource(R.string.action_retry), onClick = onRetry)
            }

            (state.loading || state.seerrLoading) && count == 0 -> Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) { SpinningDiamond(60) }

            state.resultsFor != null && count == 0 -> Text(
                stringResource(R.string.search_none, state.resultsFor),
                style = GlacierText.body(21),
                color = GlacierColors.Mist,
                modifier = Modifier.padding(vertical = 60.dp),
            )

            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(Columns),
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
                // Room for the focus ring and scale on the edges.
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 60.dp),
                modifier = Modifier.width((LocalCardSizes.current.posterWidth * Columns + 26 * (Columns - 1) + 24).dp),
            ) {
                fun Modifier.restoring(key: String) = if (key == lastOpened) focusRequester(restoreFocus) else this

                // Nixie (tv-gate-b): the grid's last card (a short last row's) keeps the focus on Right (nixieListEnd).
                val seerrShown = state.seerr.takeIf { state.chip == null }.orEmpty()
                val lastKey = seerrShown.lastOrNull()?.resultKey
                    ?: if (state.hidden > 0) SHOW_MORE_KEY else state.results.lastOrNull()?.resultKey
                fun Modifier.ending(key: String) = nixieListEnd(key == lastKey, rail = null)
                items(state.results, key = { it.resultKey }) { item ->
                    // Nixie: the first title "Show N more" showed takes the focus.
                    val revealing = if (item.resultKey == revealKey) Modifier.focusRequester(reveal) else Modifier
                    ResultCard(
                        item,
                        onClick = { onOpen(item) },
                        modifier = Modifier.restoring(item.resultKey).then(revealing).ending(item.resultKey),
                    )
                }
                // Nixie: weaker matches wait behind "Show N more"; Seerr's titles show under "All" only.
                if (state.hidden > 0) {
                    item(key = SHOW_MORE_KEY) {
                        ShowMoreCard(state.hidden, modifier = Modifier.ending(SHOW_MORE_KEY), onClick = {
                            val all = state.ranked?.visible(state.chip, expanded = true).orEmpty()
                            revealKey = all.getOrNull(state.results.size)?.resultKey
                            onShowMore()
                        })
                    }
                }
                items(seerrShown, key = { it.resultKey }) { item ->
                    SeerrCard(
                        item,
                        onClick = { onOpenSeerr(item) },
                        modifier = Modifier.restoring(item.resultKey).ending(item.resultKey),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    if (item.kind == ItemKind.Album) {
        // Design: albums show title and artist under a square cover.
        PosterCard(
            imageUrl = item.posterUrl,
            caption = item.parentTitle.orEmpty(),
            onClick = onClick,
            modifier = modifier,
            square = true,
            title = item.title,
        )
    } else {
        // Title above the year, laid out like the album cards.
        PosterCard(
            imageUrl = item.posterUrl,
            // Nixie: a person says so, an episode names its show and SxEy.
            caption = searchCaption(item) ?: yearText(item).orEmpty(),
            onClick = onClick,
            modifier = modifier,
            title = item.title,
            locked = item.showsLock(),
        )
    }
}

/** A title from Seerr: what it is and where its request stands. */
@Composable
private fun SeerrCard(
    item: SeerrItem,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val kind = stringResource(if (item.type == SeerrMediaType.Movie) R.string.seerr_movie else R.string.seerr_show)
    PosterCard(
        imageUrl = item.posterUrl,
        caption = listOfNotNull(item.year?.toString(), kind).joinToString(" · "),
        onClick = onClick,
        modifier = modifier,
        title = item.title,
        tag = stringResource(seerrStatusLabel(item.status)),
    )
}

private val MediaItem.resultKey: String get() = id.toString()
private val SeerrItem.resultKey: String get() = "seerr-$type-$tmdbId"

/** Grid keys in display order: library hits, then Seerr's. */
private fun resultKeys(state: SearchState) = state.results.map { it.resultKey } + state.seerr.map { it.resultKey }
