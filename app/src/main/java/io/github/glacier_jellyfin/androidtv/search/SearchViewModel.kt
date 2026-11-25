package io.github.glacier_jellyfin.androidtv.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.SearchRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrItem
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrStatus
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.PersonRoute
import io.github.glacier_jellyfin.androidtv.navigation.SearchRoute
import io.github.glacier_jellyfin.androidtv.navigation.SeerrRoute
import io.github.glacier_jellyfin.androidtv.navigation.navEvent
import io.github.glacier_jellyfin.androidtv.navigation.routeFor
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.mbustos27.nixie.tv.data.search.NixieSearch
import io.github.mbustos27.nixie.tv.data.search.RankedResults
import io.github.mbustos27.nixie.tv.data.search.SearchKind
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    /** Library hits for [resultsFor]; suggestions while the query is empty. */
    val results: List<MediaItem> = emptyList(),
    /** Titles to request through Seerr, shown after the library hits. */
    val seerr: List<SeerrItem> = emptyList(),
    /** Seerr is still being asked for [resultsFor]. */
    val seerrLoading: Boolean = false,
    /** The query [results] belong to; null for suggestions. */
    val resultsFor: String? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val userName: String = "",
    // Nixie: the ranked results (core/data NixieSearch), the chip picked (null: All), and the weak matches shown.
    val ranked: RankedResults? = null,
    val chip: SearchKind? = null,
    val expanded: Boolean = false,
) {
    // Nixie: how many wait behind "Show N more".
    val hidden: Int get() = ranked?.hidden(chip, expanded) ?: 0
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SearchRepository,
    private val seerr: SeerrRepository,
    private val parental: ParentalControl,
    private val sessions: SessionManager,
    private val nixieSearch: NixieSearch, // Nixie: ranked search
) : ViewModel() {

    private val _state = MutableStateFlow(
        SearchState(
            query = savedStateHandle.toRoute<SearchRoute>().query.orEmpty(),
            userName = sessions.session.value?.user?.name.orEmpty(),
        ),
    )
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var searchJob: Job? = null

    // Nixie (TV-S4): the suggestions the page opened with, shown again while one character waits.
    private var suggestions: List<MediaItem>? = null

    // Nixie (TV-S4, review): the text the running search asked the server for, so Enter does not ask again.
    private var asked: String? = null

    init {
        search(_state.value.query, debounce = false)
    }

    fun setQuery(query: String) {
        if (query == _state.value.query) return
        _state.update { it.copy(query = query) }
        search(query, debounce = true)
    }

    fun retry() = search(_state.value.query, debounce = false)

    // Nixie (TV-S4, review): Enter on the keyboard searches at once, a single character included.
    fun searchNow() {
        if (_state.value.query.trim() != asked) search(_state.value.query, debounce = false)
    }

    private fun search(query: String, debounce: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // Wait for a typing pause so every key press does not hit the server.
            if (debounce) delay(DEBOUNCE_MS)
            val term = query.trim()
            asked = null
            // Nixie (TV-S4, review): one character shows the suggestions and is searched only after a longer pause (or
            // at once on Enter), so typing on never asks for it, and one-character titles (M, X, 9) can still be found.
            if (debounce && term.isNotEmpty() && !NixieSearch.searchable(term)) {
                showSuggestions()
                delay(SHORT_TERM_PAUSE_MS - DEBOUNCE_MS)
            }
            asked = term
            _state.update { it.copy(loading = true, failed = false) }
            try {
                if (term.isEmpty()) {
                    val results = repository.suggestions()
                    suggestions = results // Nixie
                    _state.update {
                        it.copy(
                            loading = false,
                            results = results,
                            resultsFor = null,
                            seerr = emptyList(),
                            seerrLoading = false,
                            ranked = null, // Nixie
                            chip = null,
                            expanded = false,
                        )
                    }
                } else {
                    searchBoth(term)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search failed", e)
                _state.update { it.copy(loading = false, failed = true, seerrLoading = false) }
            }
        }
    }

    // Nixie (TV-S4): one character shows the suggestions, loaded now if the page could not load them when it opened.
    private suspend fun showSuggestions() {
        val shown = suggestions ?: try {
            repository.suggestions().also { suggestions = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Loading the suggestions failed", e)
            emptyList()
        }
        _state.update { it.withSuggestions(shown) }
    }

    private fun SearchState.withSuggestions(shown: List<MediaItem>) = copy(
        loading = false,
        failed = false,
        results = shown,
        resultsFor = null,
        seerr = emptyList(),
        seerrLoading = false,
        ranked = null,
        chip = null,
        expanded = false,
    )

    /**
     * Library hits show at once; Seerr's follow when they arrive and never fail the search.
     * Nixie (TV-S4): the library hits show as each kind arrives (NixieSearch.results), Seerr's joining every step.
     */
    private suspend fun searchBoth(term: String) = coroutineScope {
        val started = System.currentTimeMillis()
        var shownAt: Long? = null
        // This search's answers so far; the screen gets them once the library has answered.
        var ranked: RankedResults? = null
        var complete = false
        var extra = emptyList<MediaItem>()
        var requestable: List<SeerrItem>? = null

        fun publish() {
            // Nixie: Seerr's library hits join the ranking as weak matches.
            val found = ranked?.withExtra(extra) ?: return
            _state.update { it.withStep(term, found, complete, requestable) }
            if (shownAt == null && found.items.isNotEmpty()) shownAt = System.currentTimeMillis() - started
        }

        // Nixie (TV-S4, review): the steps reach the screen at most every COALESCE_MS, the first one too, so answers that
        // come close together make one relayout, and a better match a moment later goes in with them rather than
        // above the cards already shown. The last step, and Seerr's answer after it, show at once.
        val due = Channel<Unit>(Channel.CONFLATED)
        val pacer = launch {
            delay(COALESCE_MS)
            while (true) {
                due.receive()
                publish()
                delay(COALESCE_MS)
            }
        }

        fun show() {
            if (complete) publish() else due.trySend(Unit)
        }

        launch {
            // Requests are kept away from profiles with an age limit.
            val hits = if (parental.lock.value.protection.restricts) emptyList() else softly { seerr.search(term) }
            // Titles Seerr knows in the library but the title search missed (another language, say).
            val known = ranked?.items?.mapTo(HashSet()) { it.item.id }.orEmpty()
            extra = softly { repository.byIds(hits.mapNotNull { it.jellyfinId }.filter { it !in known }.distinct()) }
            requestable = hits.filter {
                it.jellyfinId == null && it.status != SeerrStatus.Available &&
                    it.status != SeerrStatus.Blocklisted
            }
            show()
        }
        nixieSearch.results(term).collect { update ->
            ranked = update.ranked
            complete = update.complete
            show()
        }
        pacer.cancel()
        // No search term in the log: only how long the first titles and the whole answer took.
        val all = System.currentTimeMillis() - started
        Log.d(TAG, "Search: first titles after ${shownAt ?: "-"} ms, all after $all ms")
    }

    /**
     * Nixie: one step of a search on screen: best match first, weak matches behind "Show N more", one chip per kind (in
     * place of Glacier's list), then Seerr's titles ([requestable]; null while Seerr is still asked).
     */
    private fun SearchState.withStep(
        term: String,
        found: RankedResults,
        complete: Boolean,
        requestable: List<SeerrItem>?,
    ): SearchState {
        // The same search again (Retry) keeps the chip and "Show N more"; a new one starts at "All".
        val same = resultsFor == term
        val keptChip = if (same) chip else null
        val keptExpanded = same && expanded
        // The same titles keep their list, so the grid does not recompose for a step that changed nothing there.
        val visible = found.visible(keptChip, keptExpanded).let { list -> if (list == results) results else list }
        return copy(
            loading = !complete,
            results = visible,
            resultsFor = term,
            seerr = requestable.orEmpty(),
            seerrLoading = requestable == null,
            ranked = found,
            chip = keptChip,
            expanded = keptExpanded,
        )
    }

    private suspend fun <T> softly(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Seerr search failed", e)
        emptyList()
    }

    // Nixie: a person opens their titles (the person page).
    fun open(item: MediaItem) =
        navigate(if (item.kind == ItemKind.Person) PersonRoute(item.id.toString()) else routeFor(item))

    // Nixie: a type chip (null: All), and "Show N more".
    fun pickChip(chip: SearchKind?) = _state.update {
        it.copy(chip = chip, expanded = false, results = it.ranked?.visible(chip, expanded = false) ?: it.results)
    }

    fun showMore() = _state.update {
        it.copy(expanded = true, results = it.ranked?.visible(it.chip, expanded = true) ?: it.results)
    }

    fun open(item: SeerrItem) = navigate(SeerrRoute(item.type.name, item.tmdbId))

    fun onNav(target: NavTarget) {
        val event = sessions.navEvent(target, current = NavTarget.Search) ?: return
        viewModelScope.launch { _events.send(event) }
    }

    private fun navigate(route: Any) {
        viewModelScope.launch { _events.send(UiEvent.Navigate(route)) }
    }

    private companion object {
        const val TAG = "Search"
        const val DEBOUNCE_MS = 300L // Nixie (TV-S4): a 300 ms pause in typing (Glacier: 350)

        /** Nixie (TV-S4, review): the steps of one search reach the screen at most this often. */
        const val COALESCE_MS = 150L

        /** Nixie (TV-S4, review): a single character is searched after this long a pause (Enter: at once). */
        const val SHORT_TERM_PAUSE_MS = 800L
    }
}
