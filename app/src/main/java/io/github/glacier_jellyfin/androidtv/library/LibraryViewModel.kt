package io.github.glacier_jellyfin.androidtv.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderContent
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderMedia
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryScope
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.data.media.LoadProgress
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaFolder
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.NameOrder
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.LibraryFilterChoice
import io.github.glacier_jellyfin.androidtv.core.data.settings.LibrarySortChoice
import io.github.glacier_jellyfin.androidtv.core.data.settings.MusicVideoView
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.music.MusicActions
import io.github.glacier_jellyfin.androidtv.music.MusicController
import io.github.glacier_jellyfin.androidtv.music.MusicTarget
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.MusicRoute
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.navEvent
import io.github.glacier_jellyfin.androidtv.navigation.routeFor
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.mbustos27.nixie.tv.data.library.NixieLibraryPaging
import io.github.mbustos27.nixie.tv.library.NixieCollectionsPage
import io.github.mbustos27.nixie.tv.library.NixieLibraryTimings
import io.github.mbustos27.nixie.tv.library.NixieRailLetters
import io.github.mbustos27.nixie.tv.library.NixieServerList
import io.github.mbustos27.nixie.tv.library.nixieFilterKey
import io.github.mbustos27.nixie.tv.library.nixieFirstPage
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.CollectionType

data class LibraryState(
    val query: LibraryQuery,
    /** Library or genre name; null shows the kind name. */
    val title: String?,
    /** The loaded part of the list: titles [offset] to [offset] + size. */
    val items: List<MediaItem> = emptyList(),
    /**
     * Where [items] start in the whole list. An A–Z jump far down loads the titles
     * there and leaves those before it for when the user scrolls back up.
     */
    val offset: Int = 0,
    val total: Int? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val letters: Set<Char> = emptySet(),
    val sortMenuOpen: Boolean = false,
    /** Grid index to scroll to and focus after an A–Z jump; consumed by the screen. */
    val jumpTo: Int? = null,
    val userName: String = "",
    /** The server has a music video library: music gets their tab. */
    val musicVideos: Boolean = false,
    /** How the music videos tab lists them; null until known (picked, or decided by their metadata). */
    val musicVideoView: MusicVideoView? = null,
    /** The folders opened in the music videos' folder view, from the top; empty at the top. */
    val folderPath: List<MediaFolder> = emptyList(),
    /** The folder shown in the folder view, its subfolders and videos in the tab's order. */
    val folder: FolderContent? = null,
    val viewMenuOpen: Boolean = false,
    /**
     * Nixie (TV-S1): [items] came from the server in its own order while the index was not in memory; the next
     * pages follow it, and a jump starts the list anew in the app's order.
     */
    val serverOrder: Boolean = false,
    /**
     * Nixie (TV-S13): [items] start at this letter's first title (an A–Z jump's page, asked by the letter), and their
     * place in the list ([offset]) is not known yet; null once it is (NixieServerList.place).
     */
    val anchor: Char? = null,
    /**
     * Nixie (TV-S13): rows start at the titles whose place is this much more than a multiple of the grid's columns, so a
     * jump's letter starts a row; the list's first row then holds this many titles (0: rows from the top).
     */
    val rowPhase: Int = 0,
) {
    /** The music videos tab shows folders instead of the paged grid. */
    val inFolders: Boolean get() = query.scope == LibraryScope.MusicVideos && musicVideoView == MusicVideoView.Folders

    // Nixie (tv-home-two-rows): the Collections page has no other tab.
    val scopes: List<LibraryScope> get() = NixieCollectionsPage.scopes(query) ?: LibraryQuery.scopes(
        query.kind,
        inGenre = query.genreId != null,
        musicVideos = musicVideos,
    )
    val complete: Boolean get() = total != null && offset + items.size >= total
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LibraryRepository,
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
    private val music: MusicController,
    private val songs: MusicRepository,
    details: DetailRepository,
    playback: PlaybackRepository,
    home: HomeRepository,
    private val folders: FolderRepository,
    private val memory: BrowseMemory,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<LibraryRoute>()

    /** Opened from the navigation bar, not for one library or genre from another page. */
    val inNavBar: Boolean get() = route.libraryId == null && route.genreId == null

    /** Nixie (tv-fix-b): opened by the top bar's entry, a starred library's too (LibraryRoute.fromBar). */
    val nixieFromBar: Boolean get() = route.fromBar

    private val kind = LibraryKind.valueOf(route.kind)

    /** Where the user left this library last time (the bar starts it anew); only for the bar's own libraries. */
    private val remembered: LibraryMemory? = memory.library(kind)?.takeIf {
        inNavBar && !route.collections && it.scope in LibraryQuery.scopes(kind, inGenre = false, musicVideos = true)
    }

    /** The title that had the focus then, for the grid to go back to. */
    val restoredFocus: String? = remembered?.focusId

    private val _state = MutableStateFlow(
        LibraryState(
            // Nixie (tv-home-two-rows): the Collections page's own query.
            query = NixieCollectionsPage.query(route.collections) ?: LibraryQuery(
                kind = kind,
                libraryId = route.libraryId?.let(UUID::fromString),
                genreId = route.genreId?.let(UUID::fromString),
                groupCollections = settings.settings.value.appearance.groupCollections,
                scope = remembered?.scope ?: LibraryQuery.defaultScope(kind),
            ),
            title = route.title,
            userName = sessions.session.value?.user?.name.orEmpty(),
            // Dropped again below when the tab turns out not to list folders.
            folderPath = remembered?.folderPath.orEmpty(),
        ),
    )
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /** How far a big list has come while the grid waits for it. */
    val progress: StateFlow<LoadProgress?> = repository.progress

    /** The folder path came from the memory and has not loaded yet: a folder that is gone sends the tab back to the top. */
    private var restoredPath = remembered?.folderPath?.isNotEmpty() == true

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The options of a song of the songs tab. */
    val musicActions = MusicActions(
        scope = viewModelScope,
        controller = music,
        music = songs,
        toast = { _events.send(it) },
    )

    private var loadJob: Job? = null

    // Nixie (TV-S1): the A–Z rail's letters (NixieRailLetters), in place of Glacier's letters job.
    private val railLetters = NixieRailLetters(viewModelScope, repository, { _state.value }) { query, letters ->
        _state.update { if (it.query == query) it.copy(letters = letters) else it }
    }
    private var refreshJob: Job? = null

    // Nixie (TV-S1): the A–Z jump under way (a newer letter cancels it); the index it waits for is the rail's
    // (NixieRailLetters.awaitIndex).
    private var jumpJob: Job? = null

    // Nixie (TV-S1): a list from the server is counted after its first page, and jumps by the server's offset.
    private val serverList = NixieServerList(viewModelScope, repository, _state, PAGE_SIZE)

    /** Nixie (TV-S1): the first page, the first row and the A–Z jumps, timed for the detailed log. */
    private val timings = NixieLibraryTimings(viewModelScope)

    init {
        // The kept sort and toggles come from the settings file, which may not be read yet right after start.
        viewModelScope.launch {
            val current = runSuspendCatching { settings.current() }
                .onFailure { Log.w(TAG, "Reading the library sort failed", it) }
                .getOrNull()
            if (current != null) {
                val filters = current.libraryFilters[route.nixieFilterKey] // Nixie (tv-home-two-rows): its own
                _state.update {
                    it.copy(
                        query = it.query.withKeptSort(current.librarySorts).copy(
                            favoritesOnly = filters?.favoritesOnly ?: false,
                            unwatchedOnly = filters?.unwatchedOnly ?: false,
                        ),
                    )
                }
            }
            val view = runSuspendCatching { settings.current().musicVideoView }.getOrNull()
            _state.update {
                it.copy(
                    musicVideoView = view,
                    folderPath = it.folderPath.takeIf { view == MusicVideoView.Folders }.orEmpty(),
                )
            }
            reload()
        }
        // Marked watched (here, on a detail page or by watching it): the grid shows it at once,
        // also while it waits behind the detail page.
        viewModelScope.launch { details.playedChanged.collect { refresh() } }
        viewModelScope.launch { playback.stopped.collect { refresh() } }
        viewModelScope.launch {
            home.kinds.collect { kinds ->
                _state.update {
                    it.copy(
                        musicVideos = LibraryKind.MusicVideos in kinds,
                    )
                }
            }
        }
    }

    /**
     * What is loaded, loaded again in place: the grid keeps its scroll and focus.
     * Unwatched drops what was watched meanwhile.
     */
    private fun refresh() {
        val current = _state.value
        // Nixie (TV-S13): a jump's page whose place is not known yet is not asked again in place.
        if (current.items.isEmpty() || loadJob?.isActive == true || current.anchor != null) return
        val query = current.query
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                // Nixie (TV-S1): a list in the server's order is asked again in that order, with its size.
                val page = with(current) { repository.page(query, offset, items.size, serverOrder, total, false) }
                _state.update {
                    if (it.query == query && it.offset == current.offset &&
                        loadJob?.isActive != true
                    ) {
                        it.copy(items = page.items, total = page.total ?: it.total)
                    } else {
                        it
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Refreshing the library failed", e)
            }
        }
    }

    /** Called by the grid when it gets near the end of what is loaded. */
    fun loadMore() {
        val current = _state.value
        if (current.inFolders || current.loading || current.failed || current.complete) return
        loadUpTo(current.offset + current.items.size + PAGE_SIZE - 1)
    }

    /** Back to the toolbar: a list that starts further down (after a jump) starts at the top again. */
    fun toStart() {
        // Nixie (TV-S13): also a jump's page whose place is not known yet, or rows that start at a jump's letter.
        if (_state.value.let { it.offset > 0 || it.anchor != null || it.rowPhase != 0 }) reload()
    }

    /** Called by the grid of [columns] when it gets near the start of what is loaded, after a jump: the titles before it. */
    fun loadBefore(columns: Int) {
        val current = _state.value
        if (current.offset == 0 || current.loading || current.failed || loadJob?.isActive == true) return
        val query = current.query
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                // From the start of a row, so the rows already there stay as they are.
                // Nixie (TV-S1): in the server's order too (after a jump by offset).
                val start = serverList.rowStartBefore(current, PAGE_SIZE, columns)
                val page = with(current) { repository.page(query, start, offset - start, serverOrder, total, false) }
                _state.update {
                    if (it.query != query || it.offset != current.offset) {
                        it.copy(loading = false)
                    } else {
                        // Nixie (TV-S1): each title once (the grid's keys).
                        it.copy(
                            items = NixieLibraryPaging.appended(page.items, it.items),
                            offset = start,
                            total = page.total ?: it.total,
                            loading = false,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading library failed", e)
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    fun retry() = reload()

    /** Each music tab has its own sort, so a tab change also brings back the sort kept for it. */
    fun setScope(scope: LibraryScope) {
        if (scope == _state.value.query.scope) return
        val kept = settings.settings.value.librarySorts
        update { it.copy(scope = scope).withKeptSort(kept) }
    }

    /** The heart toggle; it stays on across the tabs. */
    fun toggleFavoritesOnly() {
        update { it.copy(favoritesOnly = !it.favoritesOnly) }
        saveFilters()
    }

    /** The "Unwatched" toggle of movies and shows; it stays on across the tabs. */
    fun toggleUnwatchedOnly() {
        update { it.copy(unwatchedOnly = !it.unwatchedOnly) }
        saveFilters()
    }

    /** The toggles are kept for the profile, like the sort, so they outlast the app. */
    private fun saveFilters() {
        val query = _state.value.query
        val choice = LibraryFilterChoice(query.favoritesOnly, query.unwatchedOnly)
        viewModelScope.launch {
            runSuspendCatching {
                settings.update {
                    it.copy(
                        libraryFilters = it.libraryFilters + (route.nixieFilterKey to choice), // Nixie
                    )
                }
            }
                .onFailure { Log.w(TAG, "Saving the library filters failed", it) }
        }
    }

    fun toggleSortMenu(open: Boolean = !_state.value.sortMenuOpen) = _state.update { it.copy(sortMenuOpen = open) }

    fun toggleViewMenu(open: Boolean = !_state.value.viewMenuOpen) = _state.update { it.copy(viewMenuOpen = open) }

    /** The music videos tab's view, kept for the profile; a new view starts at the top folder. */
    fun setMusicVideoView(view: MusicVideoView) {
        _state.update { it.copy(viewMenuOpen = false) }
        if (view == _state.value.musicVideoView) return
        _state.update { it.copy(musicVideoView = view, folderPath = emptyList(), folder = null) }
        remember(focusId = null)
        reload()
        viewModelScope.launch {
            runSuspendCatching { settings.update { it.copy(musicVideoView = view) } }
                .onFailure { Log.w(TAG, "Saving the music video view failed", it) }
        }
    }

    /** Opens a subfolder in the folder view. */
    fun openFolder(folder: MediaFolder) {
        _state.update { it.copy(folderPath = it.folderPath + folder, folder = null) }
        remember(focusId = null)
        reload()
    }

    /** Up a folder in the folder view; false at the top. */
    fun folderUp(): Boolean {
        if (_state.value.folderPath.isEmpty()) return false
        _state.update { it.copy(folderPath = it.folderPath.dropLast(1), folder = null) }
        remember(focusId = null)
        reload()
        return true
    }

    /** The design's sort menu: picking the active sort again flips the direction. */
    fun setSort(sort: LibrarySort) {
        _state.update { it.copy(sortMenuOpen = false) }
        update { query ->
            if (query.sort == sort) {
                query.copy(descending = !query.descending)
            } else {
                query.copy(sort = sort, descending = sort !in AscendingFirst)
            }
        }
        val query = _state.value.query
        val choice = LibrarySortChoice(query.sort, query.descending)
        viewModelScope.launch {
            runSuspendCatching {
                settings.update {
                    it.copy(
                        librarySorts = it.librarySorts + (query.sortKey to choice),
                    )
                }
            }
                .onFailure { Log.w(TAG, "Saving the library sort failed", it) }
        }
    }

    /**
     * Scrolls to the first title filed under [letter]. When that is far beyond what is loaded,
     * the list starts anew a few rows of [columns] before it.
     */
    fun jumpToLetter(letter: Char, columns: Int) {
        val current = _state.value
        // Nixie (TV-S1): a list from the server is uncounted until its count comes; it jumps by offset meanwhile.
        if (current.total == null && !(current.serverOrder && current.items.isNotEmpty())) return
        // Nixie (TV-S1): only the latest letter wins: a jump still waiting for the index gives way to a newer one.
        jumpJob?.cancel()
        val pressed = timings.mark() // Nixie (TV-S1)
        jumpJob = viewModelScope.launch {
            // Nixie (TV-S1): a list in the server's order whose index is not in memory jumps by the server's offset:
            // two small requests, no whole-library work between the press and the grid (DESIGN-RULES 3).
            // Nixie (TV-S13): one request before the first row, the letter's own page; its place comes after it.
            if (serverList.jumpsByOffset(current)) {
                val how = serverList.jump(letter, columns, { loadFrom(it, fromServer = true) }, ::asLoad) {
                    loadBefore(columns)
                } ?: return@launch
                return@launch timings.jumped(pressed, how)
            }
            railLetters.awaitIndex(current.query) // Nixie (TV-S1)
            val index = runSuspendCatching { repository.indexOfLetter(current.query, letter) }
                .onFailure { Log.w(TAG, "A-Z jump failed", it) }
                .getOrNull() ?: return@launch
            railLetters.afterJump(current.query) // Nixie (TV-S1)
            if (!loadAround(index, columns)) return@launch
            scrollTo(index)
            timings.jumped(pressed, "the index") // Nixie (TV-S1)
        }
    }

    /** The grid scrolls to position [index] of the list (a jump's landing). */
    private fun scrollTo(index: Int) = _state.update {
        it.copy(jumpTo = (index - it.offset).coerceIn(0, (it.items.size - 1).coerceAtLeast(0)))
    }

    /** Nixie (TV-S1): the grid drew the first row of the list, for the detailed log's timing. */
    fun firstRowShown() = timings.firstRowShown()

    /**
     * Loads the title [id] when it is further down than what is loaded, as an A–Z jump would;
     * true when it is loaded then. Back after "More like this" focuses the title it came from.
     */
    suspend fun reveal(id: String, columns: Int): Boolean {
        val current = _state.value
        if (current.items.any { it.id.toString() == id }) return true
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return false
        // Nixie (TV-S1): a list in the server's order does not wait for the whole index to find a title further down.
        if (serverList.withoutIndex(current)) return false
        return viewModelScope.async {
            val index = runSuspendCatching { repository.indexOf(current.query, uuid) }.getOrNull() ?: return@async false
            loadAround(index, columns) && _state.value.items.any { it.id == uuid }
        }.await()
    }

    /** Loads up to position [index], or a list that starts a few rows of [columns] before it when that is far beyond what is loaded. */
    private suspend fun loadAround(index: Int, columns: Int): Boolean {
        val state = _state.value
        val end = state.offset + state.items.size
        // Nixie (TV-S1): a list in the server's order starts anew in the app's, where the index puts the title.
        if (!state.serverOrder && index >= state.offset && index < end + PAGE_SIZE) {
            loadUpTo(index + PAGE_SIZE / 2)?.join()
            return true
        }
        val first = (index - JUMP_ROWS_BEFORE * columns).coerceAtLeast(0)
        val start = runSuspendCatching { repository.rowStart(state.query, first, columns) }.getOrElse { return false }
        return loadFrom(start)
    }

    /**
     * Nixie (TV-S1): the list starts anew at [start] (a jump's place); [fromServer], from the server in its order (a jump
     * by the server's offset), with no overlap; false when the page did not come. TV-S13: NixieServerList's, as the load.
     */
    private suspend fun loadFrom(start: Int, fromServer: Boolean = false): Boolean {
        var loaded = false
        asLoad { loaded = serverList.loadFrom(start, fromServer) }
        return loaded
    }

    /** Nixie (TV-S13): [block] as the list's load, which a newer load or a new list cancels. */
    private suspend fun asLoad(block: suspend () -> Unit) {
        loadJob?.cancel()
        refreshJob?.cancel()
        viewModelScope.launch { block() }.also { loadJob = it }.join()
    }

    // Nixie (TV-S13): the jump on screen, for the line from the key.
    fun jumpHandled() = _state.update { it.copy(jumpTo = null) }.also { timings.jumpShown() }

    fun open(item: MediaItem) {
        val query = _state.value.query
        val route = when (item.kind) {
            ItemKind.Genre -> LibraryRoute(
                query.kind.name,
                query.libraryId?.toString(),
                title = item.title,
                genreId = item.id.toString(),
            )

            else -> routeFor(item)
        }
        viewModelScope.launch { _events.send(UiEvent.Navigate(route)) }
    }

    /**
     * A song of the songs tab plays with the loaded songs after it as the queue, and the
     * player opens; [title] names the queue.
     */
    fun play(item: MediaItem, title: String) {
        val items = _state.value.items
        val start = items.indexOf(item).takeIf { it >= 0 } ?: return
        val ids = items.drop(start).take(SONG_QUEUE).map { it.id }
        music.playSongs(title, ids, startTrackId = item.id.toString())
        viewModelScope.launch { _events.send(UiEvent.Navigate(MusicRoute())) }
    }

    /**
     * A music video of the music videos tab plays in the music player with the videos after it
     * (in the grid, or in the folder shown) as the queue; [title] names the queue.
     */
    fun playVideo(item: MediaItem, title: String) {
        val state = _state.value
        val videos =
            state.folder?.takeIf { state.inFolders }?.media?.filterIsInstance<FolderMedia.Video>()?.map { it.item }
                ?: state.items
        val start = videos.indexOfFirst { it.id == item.id }.takeIf { it >= 0 } ?: return
        music.playSongs(title, videos.drop(start).map { it.id }, startTrackId = item.id.toString())
        viewModelScope.launch { _events.send(UiEvent.Navigate(MusicRoute())) }
    }

    /**
     * Play and shuffle of the music videos tab: every video in the tab's order, or in the folder
     * view those of the folder shown and the folders below it. [title] names the queue.
     */
    fun playVideos(title: String, shuffled: Boolean) = playVideos(title, shuffled) { shownVideoIds() }

    /** The videos [load] brings, in its order or [shuffled], in the music player; [title] names the queue. */
    private fun playVideos(
        title: String,
        shuffled: Boolean,
        load: suspend () -> List<UUID>,
    ) {
        viewModelScope.launch {
            val ids = runSuspendCatching { load() }.getOrElse {
                Log.w(TAG, "Loading the music videos to play failed", it)
                _events.send(UiEvent.Toast(R.string.music_videos_failed))
                return@launch
            }
            if (ids.isEmpty()) {
                _events.send(UiEvent.Toast(R.string.music_videos_none))
                return@launch
            }
            music.playSongs(title, if (shuffled) ids.shuffled() else ids)
            _events.send(UiEvent.Navigate(MusicRoute()))
        }
    }

    /** The videos Play would play: every one in the tab's order, or in the folder view those of the folder shown and below it. */
    private suspend fun shownVideoIds(): List<UUID> {
        val state = _state.value
        return if (state.inFolders) videoIdsBelow(state.folderPath.lastOrNull()?.id) else repository.ids(state.query)
    }

    /** The music videos in [folderId] and the folders below it (all of them without one), in the tab's order. */
    private suspend fun videoIdsBelow(folderId: UUID?): List<UUID> =
        folders.videosBelow(folderId, CollectionType.MUSICVIDEOS).videosFor(_state.value.query).map { it.item.id }

    /** Options for a music video of the tab (holding OK, or the menu key). */
    fun videoOptions(item: MediaItem) {
        musicActions.open(
            MusicTarget(title = item.title, subtitle = item.parentTitle, tracks = { songs.tracks(listOf(item.id)) }),
        )
    }

    /** Options for a folder of the folder view, as for an album: its videos and those of the folders below it. */
    fun folderOptions(folder: MediaFolder) {
        musicActions.open(
            MusicTarget(
                title = folder.name,
                tracks = { songs.tracks(videoIdsBelow(folder.id)) },
                play = { playVideos(folder.name, shuffled = false) { videoIdsBelow(folder.id) } },
                shuffle = { playVideos(folder.name, shuffled = true) { videoIdsBelow(folder.id) } },
            ),
        )
    }

    /** The toolbar's options, as for an album: play or shuffle all, the queue, a playlist; [title] names them. */
    fun videosOptions(title: String) {
        musicActions.open(
            MusicTarget(
                title = title,
                tracks = { songs.tracks(shownVideoIds()) },
                play = { playVideos(title, shuffled = false) },
                shuffle = { playVideos(title, shuffled = true) },
            ),
        )
    }

    /** Options for a song of the songs tab (its "…", holding OK, or the menu key). */
    fun songOptions(item: MediaItem) {
        musicActions.open(
            MusicTarget(
                title = item.title,
                subtitle = listOfNotNull(item.parentTitle, item.album).joinToString(" · ").ifEmpty { null },
                tracks = { songs.tracks(listOf(item.id)) },
                mixFrom = item.id,
            ),
        )
    }

    fun onNav(target: NavTarget) {
        val event = sessions.navEvent(
            target,
            current = NixieCollectionsPage.navTarget(route.collections, kind).takeIf { inNavBar }, // Nixie
        ) ?: return
        viewModelScope.launch { _events.send(event) }
    }

    private fun update(change: (LibraryQuery) -> LibraryQuery) {
        _state.update { it.copy(query = change(it.query)) }
        // The title focused before may not be in the new list.
        remember(focusId = null)
        reload()
    }

    /** The grid reports the title that has the focus, for the way back through the navigation bar. */
    fun focused(id: String) = remember(focusId = id)

    private fun remember(focusId: String?) {
        if (!inNavBar || route.collections) return // Nixie (tv-home-two-rows): never the movies' memory
        val query = _state.value.query
        memory.remember(kind, LibraryMemory(query.scope, focusId, _state.value.folderPath))
    }

    private fun reload() {
        timings.opened() // Nixie (TV-S1)
        loadJob?.cancel()
        // Nixie (TV-S1): a sort or filter change also drops the old list's refresh, jump and index; its letters below.
        refreshJob?.cancel()
        jumpJob?.cancel()
        serverList.cancel()
        railLetters.reset()
        _state.update {
            it.copy(
                items = emptyList(),
                offset = 0,
                total = null,
                loading = false,
                failed = false,
                letters = emptySet(),
                jumpTo = null,
                serverOrder = false, // Nixie (TV-S1)
                anchor = null, // Nixie (TV-S13)
                rowPhase = 0,
            )
        }
        val current = _state.value
        if (current.query.scope == LibraryScope.MusicVideos) {
            if (current.musicVideoView == null) return chooseMusicVideoView()
            if (current.inFolders) return loadFolder()
        }
        loadUpTo(PAGE_SIZE - 1)
        // Nixie (TV-S1): after the first screen (DESIGN-RULES 1): the count, and the rail's letters from memory or the
        // device (a saved index of 25,000 films is seconds of work on a TV), never an index from the server on open.
        serverList.countAfter(timings.firstScreen)
        railLetters.open(_state.value.query, timings.firstScreen)
    }

    /**
     * The music videos tab before the profile picked a view: the grid when most videos name their
     * artist (tagged libraries), else their folders. Decided anew each time until a view is picked.
     */
    private fun chooseMusicVideoView() {
        val query = _state.value.query
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val share = runSuspendCatching { repository.artistShare(query) }
                .onFailure { Log.w(TAG, "Counting the music videos' artists failed", it) }
                .getOrDefault(1f)
            val view = if (share >= TAGGED_SHARE) MusicVideoView.All else MusicVideoView.Folders
            _state.update {
                it.copy(
                    musicVideoView = view,
                    loading = false,
                    folderPath = it.folderPath.takeIf { view == MusicVideoView.Folders }.orEmpty(),
                )
            }
            reload()
        }
    }

    /** The folder at the end of the folder path, its subfolders and videos in the tab's order. */
    private fun loadFolder() {
        val state = _state.value
        val query = state.query
        val path = state.folderPath
        loadJob = viewModelScope.launch {
            // A new sort keeps the folder on screen until it comes back in the new order.
            _state.update { it.copy(loading = true, failed = false) }
            try {
                val content = folders.load(path.lastOrNull()?.id, CollectionType.MUSICVIDEOS)
                val sorted = content.copy(
                    folders = content.folders.foldersFor(query),
                    media = content.media.filterIsInstance<FolderMedia.Video>().videosFor(query),
                )
                if (restoredPath && content.isEmpty) return@launch backToTop()
                restoredPath = false
                _state.update {
                    if (it.query != query || it.folderPath != path) {
                        it
                    } else {
                        it.copy(folder = sorted, total = sorted.folders.size + sorted.media.size, loading = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading the music video folder failed", e)
                if (restoredPath) return@launch backToTop()
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    /** The remembered folder is gone: the folder view starts at its top folder instead. */
    private fun backToTop() {
        restoredPath = false
        _state.update { it.copy(folderPath = emptyList(), folder = null) }
        remember(focusId = null)
        reload()
    }

    /** Loads pages until position [index] is covered (or everything is loaded). */
    private fun loadUpTo(index: Int): Job? {
        val current = _state.value
        if (current.complete || current.inFolders) return null
        if (index < current.offset + current.items.size) return null
        loadJob?.let { if (it.isActive) return it }
        val query = current.query
        return viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            try {
                while (true) {
                    val state = _state.value
                    val end = state.offset + state.items.size
                    if ((state.total != null && end >= state.total) || end > index) break
                    // Nixie (TV-S1): the first page may come straight from the server; the next ones then follow it.
                    val page = if (end == 0) {
                        // Nixie (TV-S1): timed. Nixie (tv-home-two-rows): the Collections page's first screen only.
                        timings.firstPage { repository.firstPage(query, limit = query.nixieFirstPage) }
                    } else {
                        // Nixie (TV-S13): a jump's page whose place is not known yet goes on from its letter.
                        serverList.nextFromLetter(state) ?: repository.page(
                            query,
                            start = end,
                            limit = PAGE_SIZE,
                            serverOrder = state.serverOrder,
                            total = state.total,
                        )
                    }
                    // Nixie (TV-S13): the same list, though a jump's page may have learned its place meanwhile.
                    if (!serverList.sameList(state, _state.value)) return@launch
                    // Nixie (TV-S1): each title once, though the server's list moved between pages (the grid's keys).
                    val before = _state.value.items.size
                    _state.update {
                        it.copy(
                            items = NixieLibraryPaging.appended(it.items, page.items),
                            total = page.total ?: it.total,
                            serverOrder = page.serverOrder,
                        )
                    }
                    if (page.items.isEmpty() || _state.value.items.size == before) break
                    railLetters.whenScrolledFar() // Nixie (TV-S1)
                }
                _state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading library failed", e)
                _state.update { it.copy(loading = false, failed = true) }
            }
        }.also { loadJob = it }
    }

    /** The sort kept for this query's kind and tab, if the tab offers it; else by title, A to Z. */
    private fun LibraryQuery.withKeptSort(kept: Map<String, LibrarySortChoice>): LibraryQuery {
        val choice = kept[sortKey]?.takeIf { it.sort in sorts } ?: LibrarySortChoice()
        return copy(sort = choice.sort, descending = choice.descending)
    }

    companion object {
        /** From this share of music videos naming their artist on, their tab starts with all of them in one grid. */
        private const val TAGGED_SHARE = 0.5f

        /** Ten rows of seven posters. */
        const val PAGE_SIZE = 70

        /** Names sort A to Z first; dates, years, ratings and runtimes newest or largest first. */
        private val AscendingFirst = setOf(LibrarySort.Title, LibrarySort.Artist, LibrarySort.Album)

        /** Rows kept above the title an A–Z jump lands on, when the list starts anew there. */
        private const val JUMP_ROWS_BEFORE = 3

        /** Songs queued from the songs tab; their ids go into one request. */
        private const val SONG_QUEUE = 100
        private const val TAG = "Library"
    }
}

/** Subfolders by name, or newest first when the videos go by when they were added; [query]'s direction turns them around. */
private fun List<MediaFolder>.foldersFor(query: LibraryQuery): List<MediaFolder> {
    val byName = compareBy<MediaFolder, String>(NameOrder) { it.name }
    val order = if (query.sort == LibrarySort.DateAdded) compareBy<MediaFolder> { it.date }.then(byName) else byName
    return sortedWith(if (query.descending) order.reversed() else order)
}

/** A folder's videos in [query]'s order; those without what it sorts by come last, by title. */
private fun List<FolderMedia.Video>.videosFor(query: LibraryQuery): List<FolderMedia.Video> {
    val byTitle = compareBy<FolderMedia.Video, String>(NameOrder) { it.item.title }
    fun <K : Comparable<K>> by(key: (FolderMedia.Video) -> K?): List<FolderMedia.Video> {
        val (known, unknown) = partition { key(it) != null }
        val order = compareBy<FolderMedia.Video> { key(it) }.then(byTitle)
        return known.sortedWith(if (query.descending) order.reversed() else order) + unknown.sortedWith(byTitle)
    }
    return when (query.sort) {
        LibrarySort.DateAdded -> by { it.added }

        LibrarySort.Year -> by { it.item.year }

        LibrarySort.Artist -> {
            val (known, unknown) = partition { it.item.parentTitle != null }
            val order = compareBy<FolderMedia.Video, String>(NameOrder) { it.item.parentTitle.orEmpty() }.then(byTitle)
            known.sortedWith(if (query.descending) order.reversed() else order) + unknown.sortedWith(byTitle)
        }

        else -> sortedWith(if (query.descending) byTitle.reversed() else byTitle)
    }
}
