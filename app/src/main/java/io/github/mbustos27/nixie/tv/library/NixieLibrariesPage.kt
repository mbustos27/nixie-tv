// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.library.libraryTitle
import io.github.glacier_jellyfin.androidtv.navigation.FolderRoute
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.LocalLibraryKinds
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.TopNav
import io.github.glacier_jellyfin.androidtv.navigation.navEvent
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.LibraryCard
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme
import io.github.mbustos27.nixie.tv.detail.cardReturn
import io.github.mbustos27.nixie.tv.detail.cardReturnPage
import io.github.mbustos27.nixie.tv.detail.rememberCardReturn
import io.github.mbustos27.nixie.tv.home.requestFocusWhenReady
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/*
 * Nixie (tv-home-two-rows; the coordinator: "a library that can't be reached is a basics bug", and a big shared
 * server can have two dozen libraries): the top bar's Libraries entry, right after Home, opens every library's card
 * (Home's old "My media" cards), in the server's order, each with its count. The top bar keeps one entry per library
 * type.
 */

/** The top bar's Libraries page. */
@Serializable
data object NixieLibrariesRoute

/** Where a library's card leads: home videos to their folders, every other library to its own grid (as My media). */
fun nixieLibraryRoute(library: Library): Any = if (library.kind == LibraryKind.HomeVideos) {
    FolderRoute(library.id.toString(), library.name)
} else {
    LibraryRoute(library.kind.name, library.id.toString(), title = library.name)
}

/**
 * The page's data: the libraries the top bar already knows (HomeRepository, in the server's order), at once; asked for
 * only when none is known yet. Once the first row of cards is drawn, the list is asked again (a library added
 * meanwhile) and each library is counted, [COUNTS_AT_ONCE] at a time, its card's count filling in as it comes: a count
 * is a query over a whole library, never between the press and the cards (DESIGN-RULES 1, 3). The durations go to the
 * detailed log (DESIGN-RULES 10).
 */
@HiltViewModel
class NixieLibrariesViewModel @Inject constructor(
    private val home: HomeRepository,
    private val sessions: SessionManager,
) : ViewModel() {
    val libraries: StateFlow<List<Library>> = home.libraries

    private val _loading = MutableStateFlow(home.libraries.value.isEmpty())

    /** No library known yet: the page waits for the server's list. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val userName: String = sessions.session.value?.user?.name.orEmpty()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val opened = SystemClock.elapsedRealtime()
    private val firstScreen = CompletableDeferred<Unit>()

    init {
        viewModelScope.launch {
            val known = home.libraries.value.isNotEmpty()
            if (!known) {
                runSuspendCatching { home.views() }.onFailure { Log.w(TAG, "Loading the libraries failed", it) }
                _loading.value = false
            }
            firstScreen.await()
            if (known) {
                runSuspendCatching { home.views() }.onFailure { Log.w(TAG, "Refreshing the libraries failed", it) }
            }
            counts()
        }
    }

    private suspend fun counts() {
        val all = home.libraries.value
        val permits = Semaphore(COUNTS_AT_ONCE)
        coroutineScope {
            all.forEach { library ->
                launch { permits.withPermit { runSuspendCatching { home.counts(listOf(library)) } } }
            }
        }
        Log.d(TAG, "Libraries: counts all in after ${sinceOpened()} ms (${all.size} libraries)")
    }

    /** The page drew its first row of cards. */
    fun firstRowShown() {
        if (!firstScreen.complete(Unit)) return
        val shown = libraries.value.size
        Log.d(TAG, "Libraries: first row on screen ${sinceOpened()} ms after opening ($shown libraries)")
    }

    fun open(library: Library) = send(UiEvent.Navigate(nixieLibraryRoute(library)))

    fun onNav(target: NavTarget) {
        sessions.navEvent(target, current = NavTarget.Libraries)?.let(::send)
    }

    private fun send(event: UiEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    private fun sinceOpened() = SystemClock.elapsedRealtime() - opened

    companion object {
        /** Libraries counted at once: the server belongs to someone else. */
        const val COUNTS_AT_ONCE = 4
        private const val TAG = "Libraries"
    }
}

/** The page: the top bar over every library's card. Back in the cards goes up to the bar; Back there goes Home. */
@Composable
fun NixieLibrariesScreen(onNavigate: (UiEvent.Navigate) -> Unit, viewModel: NixieLibrariesViewModel = hiltViewModel()) {
    CollectEvents(viewModel.events, onNavigate)
    val libraries by viewModel.libraries.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val navFocus = remember { FocusRequester() }
    val cardsFocus = remember { FocusRequester() }
    var inCards by remember { mutableStateOf(false) }
    BackHandler(enabled = inCards) { runCatching { navFocus.requestFocus() } }
    LaunchedEffect(libraries.isNotEmpty()) {
        if (libraries.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        requestFocusWhenReady({ cardsFocus })
    }
    Box(Modifier.fillMaxSize()) {
        NixieLibrariesPage(
            libraries = libraries,
            loading = loading,
            onOpen = viewModel::open,
            cardsModifier = Modifier
                .focusRequester(cardsFocus)
                .onFocusChanged { inCards = it.hasFocus },
            onFirstRow = viewModel::firstRowShown,
        )
        TopNav(
            active = NavTarget.Libraries,
            kinds = LocalLibraryKinds.current,
            userName = viewModel.userName,
            onSelect = viewModel::onNav,
            down = cardsFocus.takeIf { libraries.isNotEmpty() },
            focusRequester = navFocus,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 34.dp),
        )
    }
}

/** The test tags of the page's parts. */
object NixieLibrariesTags {
    const val CARDS = "nixie-libraries-cards"
    const val NONE = "nixie-libraries-none"
}

/**
 * Every library's card in [libraries]' order (the server's), each named as Home's My media named it ([libraryTitle]:
 * the type when it is the only library of its type, else its name) with its count once known. [cardsModifier] goes on
 * the cards' group; [onFirstRow] hears once the first row is drawn.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun NixieLibrariesPage(
    libraries: List<Library>,
    loading: Boolean,
    onOpen: (Library) -> Unit,
    modifier: Modifier = Modifier,
    cardsModifier: Modifier = Modifier,
    onFirstRow: () -> Unit = {},
) {
    // tv-fix-b: from the page's first frame, so a return whose libraries come late is dropped in time (CardReturn).
    val cardReturn = rememberCardReturn()
    if (libraries.isEmpty() && loading) {
        Box(modifier.fillMaxSize().cardReturnPage(cardReturn), contentAlignment = Alignment.Center) {
            SpinningDiamond(110)
        }
        return
    }
    Column(
        modifier
            .cardReturnPage(cardReturn)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = PageEdge.dp, end = PageEdge.dp, top = PAGE_TOP.dp, bottom = PAGE_BOTTOM.dp),
    ) {
        val title = stringResource(R.string.nixie_nav_libraries)
        val line = pluralStringResource(R.plurals.nixie_libraries_count, libraries.size, libraries.size)
        NixieTheme.slots.browse.libraryHeader?.invoke(title, line, Modifier) ?: Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = GlacierText.display(50), color = GlacierColors.Ice)
            Text(line, style = GlacierText.body(19), color = GlacierColors.Mist)
        }
        Spacer(Modifier.height(HEADER_GAP.dp))
        if (libraries.isEmpty()) {
            Text(
                stringResource(R.string.nixie_libraries_none),
                style = GlacierText.body(21),
                color = GlacierColors.Mist,
                modifier = Modifier.testTag(NixieLibrariesTags.NONE),
            )
            return@Column
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            onFirstRow()
        }
        FlowRow(
            cardsModifier
                .testTag(NixieLibrariesTags.CARDS)
                .fillMaxWidth()
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(GAP.dp),
            verticalArrangement = Arrangement.spacedBy(GAP.dp),
        ) {
            libraries.forEachIndexed { index, library ->
                LibraryCard(
                    name = libraryTitle(library, libraries),
                    count = libraryCountText(library),
                    // Jellyfin's generated library pictures carry the name already: drawn under the card's own name
                    // it read twice (on the emulator, "Movies" over "Movies"). My media used a title's backdrop, a
                    // request per library; the cards stay plain instead.
                    imageUrl = null,
                    onClick = { onOpen(library) },
                    // tv-1.0-r1: Right on the last card, alone in its row, stays there instead of going up to the bar.
                    modifier = Modifier.nixieListEnd(index == libraries.lastIndex, rail = null)
                        // tv-gate-b: Back from the library opened focuses its card again, not the first one.
                        .cardReturn(cardReturn, library.id.toString()),
                )
            }
        }
    }
}

/** "12,345 titles", "120 albums", "48 videos": a library's count as My media wrote it; null until it is known. */
@Composable
fun libraryCountText(library: Library): String? {
    val count = library.itemCount ?: return null
    return when (library.kind) {
        LibraryKind.Music -> pluralStringResource(R.plurals.count_albums, count, count)
        LibraryKind.HomeVideos -> pluralStringResource(R.plurals.count_videos, count, count)
        else -> pluralStringResource(R.plurals.count_titles, count, count)
    }
}

private const val PAGE_TOP = 140
private const val PAGE_BOTTOM = 90
private const val HEADER_GAP = 34
private const val GAP = 26
