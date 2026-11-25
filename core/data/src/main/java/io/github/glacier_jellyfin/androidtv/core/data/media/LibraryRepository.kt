package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.mbustos27.nixie.tv.data.library.NixieLibraryPaging
import io.github.mbustos27.nixie.tv.data.library.PageTiming
import io.github.mbustos27.nixie.tv.data.library.ServerSort
import io.github.mbustos27.nixie.tv.jellyfin.NixieRequestClock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.artistApi
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

/** The filter chips of the library screen. */
enum class LibraryScope { All, Collections, Genres, Albums, Artists, Songs, MusicVideos, Playlists }

enum class LibrarySort { DateAdded, Title, Artist, Album, Year, Rating, Runtime }

/**
 * What the library grid shows: every library of [kind], or one library when
 * [libraryId] is set, optionally narrowed to a genre.
 */
data class LibraryQuery(
    val kind: LibraryKind,
    val libraryId: UUID? = null,
    val genreId: UUID? = null,
    val scope: LibraryScope = defaultScope(kind),
    val sort: LibrarySort = LibrarySort.Title,
    val descending: Boolean = false,
    /** Design setting "Group movies into collections"; on by default. */
    val groupCollections: Boolean = true,
    /** The heart toggle: only what the profile marked as favorite, in every tab. */
    val favoritesOnly: Boolean = false,
    /** The "Unwatched" toggle of movies and shows. */
    val unwatchedOnly: Boolean = false,
    // Nixie (tv-home-two-rows): the top bar's Collections page: every collection on the server, its own sort and toggles.
    val nixieAllCollections: Boolean = false,
) {
    /** The collections tab of shows lists only the collections that hold a show. */
    val showCollections: Boolean
        get() = scope == LibraryScope.Collections && kind == LibraryKind.Shows

    /**
     * The movies tab shows collections in place of their movies. Filtered, the titles themselves count: a collection would
     * hide which of its movies match. Nixie (TV-S1): unwatched too (Glacier grouped those itself from the whole library,
     * half a minute on a big server: Jellyfin does not group once a played filter is in the query; its web client shows
     * the unwatched titles themselves as well).
     */
    val groupsCollections: Boolean
        get() = scope == LibraryScope.All && kind == LibraryKind.Movies && groupCollections && genreId == null &&
            !favoritesOnly && !unwatchedOnly

    /** Genres are listed as they are: the toggles narrow titles, not genres. */
    val filterable: Boolean get() = scope != LibraryScope.Genres

    /** Genres are always listed by name, A to Z, whatever the other scopes sort by. */
    val listedByName: Boolean get() = scope == LibraryScope.Genres

    /** The sorts the sort menu offers for this scope, in menu order. */
    val sorts: List<LibrarySort> get() = when (scope) {
        LibraryScope.Artists, LibraryScope.Playlists -> listOf(LibrarySort.DateAdded, LibrarySort.Title)

        LibraryScope.Albums -> listOf(
            LibrarySort.DateAdded,
            LibrarySort.Title,
            LibrarySort.Artist,
            LibrarySort.Year,
            LibrarySort.Rating,
            LibrarySort.Runtime,
        )

        LibraryScope.Songs -> listOf(
            LibrarySort.DateAdded,
            LibrarySort.Title,
            LibrarySort.Artist,
            LibrarySort.Album,
            LibrarySort.Year,
            LibrarySort.Runtime,
        )

        LibraryScope.MusicVideos -> listOf(
            LibrarySort.DateAdded,
            LibrarySort.Title,
            LibrarySort.Year,
            LibrarySort.Artist,
        )

        else -> listOf(
            LibrarySort.DateAdded,
            LibrarySort.Title,
            LibrarySort.Year,
            LibrarySort.Rating,
            LibrarySort.Runtime,
        )
    }

    /**
     * Where the chosen sort is kept: one per kind, and one per tab for music, whose tabs
     * sort by different things. Albums keep the plain kind key they had before tabs had their own.
     */
    val sortKey: String get() = if (nixieAllCollections) {
        // Nixie (tv-home-two-rows): the Collections page keeps its own (A to Z until changed), not the movies'.
        NIXIE_COLLECTIONS_KEY
    } else if (kind == LibraryKind.Music &&
        scope != LibraryScope.Albums
    ) {
        "${kind.name}.${scope.name}"
    } else {
        kind.name
    }

    /** The order actually requested: [descending] only applies to the chosen sort. */
    val orderDescending: Boolean get() = descending && !listedByName

    /** Sorted by something else than the name, the list goes in groups with a heading each (see [ListGroup]). */
    val grouped: Boolean get() = !listedByName && sort != LibrarySort.Title

    /** An A–Z rail where the list goes by names: titles, or groups of artists or albums. */
    val railed: Boolean get() = listedByName || sort == LibrarySort.Title || sort == LibrarySort.Artist ||
        sort == LibrarySort.Album

    companion object {
        /** Nixie (tv-home-two-rows): where the Collections page keeps its sort and toggles. */
        const val NIXIE_COLLECTIONS_KEY = "Collections"

        /**
         * Nixie (tv-home-two-rows): the top bar's Collections page, every collection on the server (box sets) A to Z,
         * paged from the server's order as a movie library's Collections chip is (NixieLibraryPaging).
         */
        fun nixieCollections() = LibraryQuery(
            kind = LibraryKind.Movies,
            scope = LibraryScope.Collections,
            nixieAllCollections = true,
        )

        fun defaultScope(kind: LibraryKind) = if (kind == LibraryKind.Music) LibraryScope.Albums else LibraryScope.All

        /** Music has a tab for the music videos when the server has a library of them ([musicVideos]). */
        fun scopes(
            kind: LibraryKind,
            inGenre: Boolean,
            musicVideos: Boolean = false,
        ): List<LibraryScope> = when {
            kind == LibraryKind.Music -> listOfNotNull(
                LibraryScope.Albums,
                LibraryScope.Artists,
                LibraryScope.Songs,
                LibraryScope.MusicVideos.takeIf { musicVideos },
                LibraryScope.Playlists,
            )

            inGenre -> listOf(LibraryScope.All)

            kind == LibraryKind.Movies || kind == LibraryKind.Shows -> listOf(
                LibraryScope.All,
                LibraryScope.Collections,
                LibraryScope.Genres,
            )

            else -> listOf(LibraryScope.All, LibraryScope.Genres)
        }
    }
}

data class LibraryPage(
    val items: List<MediaItem>,
    /** Nixie (TV-S1): null while unknown: a list from the server is counted apart (LibraryRepository.serverPosition). */
    val total: Int?,
    /** Nixie (TV-S1): straight from the server in its own order, while the index is not in memory (NixieLibraryPaging). */
    val serverOrder: Boolean = false,
    /** Nixie (TV-S1): how long its request and its parsing took, for the detailed log; null when not timed. */
    val timing: PageTiming? = null,
)

/** How far the whole list of [query] has come while the grid waits for it: [loaded] of [total] titles. */
data class LoadProgress(
    val query: LibraryQuery,
    val loaded: Int,
    val total: Int,
) {
    /** Whether this is the list [other] waits for. One list serves every sort. */
    fun isFor(other: LibraryQuery): Boolean = query.unsorted == other.unsorted
}

private val LibraryQuery.unsorted: LibraryQuery get() = copy(sort = LibrarySort.Title, descending = false)

/** Letters of the A–Z rail; '#' stands for everything that does not start with A–Z. */
val AlphabetLetters: List<Char> = listOf('#') + ('A'..'Z')

/** The rail letter [name] is filed under: its first letter without accents, else '#'. Matches [NameOrder]. */
fun railLetter(name: String): Char {
    val first = NameOrder.key(name).firstOrNull() ?: return '#'
    return if (first in 'a'..'z') first.uppercaseChar() else '#'
}

@Singleton
class LibraryRepository @Inject constructor(
    private val sessions: SessionManager,
    private val ageFilter: AgeFilter,
    private val store: NameIndexStore,
) {

    private val _progress = MutableStateFlow<LoadProgress?>(null)

    /** The whole list the grid waits for, while it takes more than one request; null otherwise. */
    val progress: StateFlow<LoadProgress?> = _progress.asStateFlow()

    /**
     * Titles [start] to [start] + [limit] of [query], each with its group when the list is grouped.
     * Nixie (TV-S1): [serverOrder] continues a list that came from the server in its own order ([firstPage]); [total]
     * is its size, asked again when null. Nixie (TV-S13): [fromLetter], titles counted from that letter's first one in
     * the server's order (an A–Z jump's first row in one request: the server filters by sort name,
     * [NixieLibraryPaging.letterStart], so the page needs no place in the list first), uncounted.
     */
    suspend fun page(
        query: LibraryQuery,
        start: Int,
        limit: Int,
        serverOrder: Boolean = false,
        total: Int? = null,
        // Nixie (TV-S1): a page of a list in the server's order starts a few titles back, unless it fills in before.
        overlap: Boolean = true,
        fromLetter: Char? = null, // Nixie (TV-S13)
    ): LibraryPage = withContext(Dispatchers.IO) {
        val session = requireSession()
        // Nixie (TV-S13): an A–Z jump's page, from its letter.
        fromLetter?.let { letter ->
            val bounds = NixieLibraryPaging.letterStart(letter, query.orderDescending)
            val from = checkNotNull(bounds) { "The list starts with $letter" }
            return@withContext serverPage(session, query, start, limit, known = null, overlap = false, letter = from)
        }
        // Nixie (TV-S1): the next pages of a list that came from the server in its own order (firstPage).
        if (serverOrder) return@withContext serverPage(session, query, start, limit, total, overlap)
        val mapper = MediaMapper(session.api)
        // The first page is a fresh look: something may have changed meanwhile.
        val listing = listing(session, query, fresh = start == 0)
        val list = listing.list
        val end = minOf(start + limit, list.size)
        if (start >= end) return@withContext LibraryPage(emptyList(), list.size)
        val slice = list.entries.subList(start, end)
        val groups = list.groups?.let { all -> slice.indices.associate { slice[it].id to all[start + it] } }
        val dtos = listing.whole?.let { whole -> slice.mapNotNull { whole[it.id] } } ?: itemsByIds(
            session,
            slice.map { it.id },
        )
        val items = dtos.map(mapper::item).map { item -> groups?.get(item.id)?.let { item.copy(group = it) } ?: item }
        LibraryPage(ageFilter.mark(items), list.size)
    }

    /** Letters that have at least one title, for greying out the rest of the A–Z rail. */

    /** The share of [query]'s titles that name an artist, from the same index the grid pages through. */
    suspend fun artistShare(query: LibraryQuery): Float = withContext(Dispatchers.IO) {
        val entries = listing(requireSession(), query).source
        if (entries.isEmpty()) 1f else entries.count { it.artist != null } / entries.size.toFloat()
    }

    /** Nixie (TV-S1): fetched in the [background] (big pages, uncounted) when nobody waits for it, else as a wait. */
    suspend fun availableLetters(query: LibraryQuery, background: Boolean = false): Set<Char> =
        withContext(Dispatchers.IO) {
            val list = listing(requireSession(), query, waited = !background).list
            (0 until list.size).mapNotNullTo(mutableSetOf()) { list.letterAt(it) }
        }

    /**
     * Nixie (TV-S1): the letters of [query]'s A–Z rail when its index is in memory or saved on the device; null when
     * only the server has it. Opening a list asks the server for no index (a big library takes it some 26 requests).
     */
    suspend fun knownLetters(query: LibraryQuery): Set<Char>? = withContext(Dispatchers.IO) {
        val session = requireSession()
        val key = indexKey(session, query)
        if (!query.listedWhole && cachedIndex(key) == null && store.savedAt(key) == null) return@withContext null
        val list = listing(session, query).list
        (0 until list.size).mapNotNullTo(mutableSetOf()) { list.letterAt(it) }
    }

    /**
     * Position of the first title filed under [letter] in the current order. Nixie (TV-S1): with no title under it (the
     * rail offers every letter while its index is not known), the first title after it, else the last one.
     */
    suspend fun indexOfLetter(query: LibraryQuery, letter: Char): Int = withContext(Dispatchers.IO) {
        val list = listing(requireSession(), query).list
        (0 until list.size).firstOrNull { list.letterAt(it) == letter }
            ?: NixieLibraryPaging.nearestLetter(letter, query.orderDescending, list.size) { list.letterAt(it) }
    }

    /** Position of the title [id] in the current order; null when the list does not hold it. */
    suspend fun indexOf(query: LibraryQuery, id: UUID): Int? = withContext(Dispatchers.IO) {
        listing(requireSession(), query).list.entries.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    }

    /** Every title of [query] in the grid's order, for playing them one after another. */
    suspend fun ids(query: LibraryQuery): List<UUID> = withContext(Dispatchers.IO) {
        listing(requireSession(), query).list.entries.map { it.id }
    }

    /** Where the row that holds [index] starts in a grid of [columns] (see [ArrangedList.rowStart]). */
    suspend fun rowStart(
        query: LibraryQuery,
        index: Int,
        columns: Int,
    ): Int = withContext(Dispatchers.IO) {
        listing(requireSession(), query).list.rowStart(index, columns)
    }

    // Nixie (TV-S1) ------------------------------------------------------------------------------------------------

    /**
     * A list's first [limit] titles as the grid opens it: from the index as [page] has them, or, while the index is not
     * in memory and the server's order will do ([NixieLibraryPaging]), straight from the server in that order
     * ([LibraryPage.serverOrder]); the index then comes (from the device, or gently from the server) when the A–Z rail
     * or a jump asks for it.
     */
    suspend fun firstPage(query: LibraryQuery, limit: Int): LibraryPage = withContext(Dispatchers.IO) {
        val session = requireSession()
        if (NixieLibraryPaging.pagesOnServer(query) && !indexReady(session, query)) {
            // Uncounted: the count of a big library is its own request, after the first row (DESIGN-RULES 3).
            serverPage(session, query, 0, limit, known = null)
        } else {
            page(query, 0, limit)
        }
    }

    /** Whether [query]'s index is in memory (and a fresh look would not fetch it again): its order is the app's. */
    suspend fun indexReady(query: LibraryQuery): Boolean = withContext(Dispatchers.IO) {
        indexReady(requireSession(), query)
    }

    /**
     * As [index] with `fresh` decides, but only from memory: a saved index takes a big library seconds to read and put in
     * order (6 s for 25,000 films on the emulator), so the first page does not wait for it either.
     */
    private suspend fun indexReady(session: Session, query: LibraryQuery): Boolean {
        if (query.listedWhole) return true
        val key = indexKey(session, query)
        val cached = cachedIndex(key) ?: return false
        val filtered = query.favoritesOnly || query.unwatchedOnly || query.scope == LibraryScope.Playlists
        return !filtered || System.currentTimeMillis() - cached.at < WHOLE_FRESH_MS
    }

    /**
     * Titles [start] to [start] + [limit] of [query] in the server's order ([NixieLibraryPaging.serverSort]), with only
     * what a card needs, uncounted: [known] is the list's size once counted apart ([serverPosition]), else the size is
     * null until a page comes back short. A later page starts [NixieLibraryPaging.OVERLAP] titles back, so the server's
     * list moving meanwhile skips none; the titles it repeats are left out when appended ([NixieLibraryPaging.appended]).
     * No [overlap] for a page that fills in before what is loaded, starts the list anew or loads it again in place.
     */
    private suspend fun serverPage(
        session: Session,
        query: LibraryQuery,
        start: Int,
        limit: Int,
        known: Int?,
        overlap: Boolean = true,
        // Nixie (TV-S13): an A–Z jump's page, from its letter; [start] counted from the letter's first title.
        letter: NixieLibraryPaging.LetterStart? = null,
    ): LibraryPage {
        val sort = checkNotNull(NixieLibraryPaging.serverSort(query)) { "No server order for $query" }
        val from = if (start == 0 || !overlap) start else (start - NixieLibraryPaging.OVERLAP).coerceAtLeast(0)
        val asked = limit + (start - from)
        // Nixie (TV-S1): the request and the parsing timed apart, for the detailed log (DESIGN-RULES 10).
        val clock = NixieRequestClock()
        val sent = clock.now()
        val result = withContext(clock) {
            fetch(
                session,
                query,
                start = from,
                limit = asked,
                serverSort = sort,
                withTotal = false, // Nixie (TV-S1)
                nameLessThan = letter?.below, // Nixie (TV-S13): a jump's page, by its letter
                nameFrom = letter?.from,
            )
        }
        val mapper = MediaMapper(session.api)
        val items = result.items.map { dto ->
            val item = mapper.item(dto)
            if (query.grouped) item.copy(group = ListArrangement.group(entryOf(dto, query), query.sort)) else item
        }
        val total = when {
            // Nixie (TV-S13): where the letter's titles sit in the list is asked apart (serverPosition).
            letter != null -> null

            known != null -> NixieLibraryPaging.totalAfter(known, from, asked, items.size)

            // The list ends here.
            items.size < asked -> from + items.size

            else -> null
        }
        val marked = ageFilter.mark(items)
        return LibraryPage(marked, total, serverOrder = true, PageTiming.of(sent, clock.receivedAt, clock.now()))
    }

    /**
     * Nixie (TV-S1): a place in [query]'s list in the server's order, asked apart from its pages, with the same filters
     * and limit 0 (counting a big library takes the server a while: never with the first page, DESIGN-RULES 3).
     * With no [letter], the end of the list: how many titles it holds. With a [letter], where an A–Z jump to it lands,
     * without the index: the titles whose sort name comes before it (`NameLessThan`); Z to A counts back from the end,
     * [total] when known. A letter with no titles lands on the next one. One request; two for Z to A uncounted.
     */
    suspend fun serverPosition(
        query: LibraryQuery,
        letter: Char? = null,
        total: Int? = null,
    ): Int = withContext(Dispatchers.IO) {
        val session = requireSession()

        suspend fun titlesBefore(name: String?) = fetch(
            session,
            query,
            start = 0,
            limit = 0,
            light = true,
            withTotal = true,
            nameLessThan = name,
        ).totalRecordCount

        if (letter == null) return@withContext titlesBefore(null)
        val bounds = NixieLibraryPaging.letterBounds(letter, query.orderDescending)
        val before = bounds.below?.let { titlesBefore(it) } ?: 0
        if (bounds.fromTheEnd) ((total ?: titlesBefore(null)) - before).coerceAtLeast(0) else before
    }

    /**
     * Fetched whole, with everything a card needs ([wholeList]). Artists and genres come
     * from their own endpoints, which count albums and titles. Fetched by id they would not.
     */
    private val LibraryQuery.listedWhole: Boolean
        get() = scope == LibraryScope.Artists || scope == LibraryScope.Genres // Nixie (TV-S1): no unwatched grouping

    /** What a title is sorted and filed under (see [NameOrder.title]). A song's sort name starts with its disc and track ("0001 - 0003 - Title"). */
    private fun nameOf(dto: BaseItemDto, query: LibraryQuery): String =
        if (query.scope == LibraryScope.Songs) dto.name.orEmpty() else NameOrder.title(dto.name.orEmpty(), dto.sortName)

    /** [shared] keeps one copy of each artist and album: a big music library repeats them on every song. */
    private fun entryOf(
        dto: BaseItemDto,
        query: LibraryQuery,
        shared: MutableMap<String, String>? = null,
    ): IndexEntry {
        fun String.once() = shared?.getOrPut(this) { this } ?: this
        return IndexEntry(
            id = dto.id,
            name = nameOf(dto, query),
            addedMonth = dto.dateCreated?.let { it.year * 12 + it.monthValue - 1 },
            year = dto.productionYear,
            rating = dto.communityRating,
            runtimeMs = dto.runTimeTicks?.div(10_000),
            artist = when (query.scope) {
                LibraryScope.Songs -> dto.artists?.firstOrNull() ?: dto.albumArtist
                LibraryScope.MusicVideos -> dto.artists?.firstOrNull()
                else -> dto.albumArtist
            }?.once(),
            albumId = dto.albumId?.toString()?.once(),
            album = dto.album?.once(),
            disc = dto.parentIndexNumber,
            track = dto.indexNumber,
        )
    }

    /** A list in the grid's order and, when fetched whole, its titles. */
    private class Listing(
        val query: LibraryQuery,
        val source: List<IndexEntry>,
        val list: ArrangedList,
        val whole: Map<UUID, BaseItemDto>?,
    )

    private var listingCache: Listing? = null
    private val listingLock = Mutex()

    /**
     * [query]'s titles in the grid's order. The app puts them in order itself (see
     * [ListArrangement]), so it needs every title first: most come as a light index ([index]),
     * and each page is then fetched by its ids. One index serves every sort, so a new sort
     * only puts the titles in a new order.
     */
    private suspend fun listing(
        session: Session,
        query: LibraryQuery,
        fresh: Boolean = false,
        // Nixie (TV-S1): whether someone waits for it (small pages, counted) or not (the background index).
        waited: Boolean = true,
    ): Listing {
        val whole = if (query.listedWhole) wholeList(session, query, fresh) else null
        val entries = whole?.entries ?: index(session, query, fresh, waited) // Nixie (TV-S1): waited
        return listingLock.withLock {
            listingCache?.takeIf { it.query == query && it.source === entries }
                ?: Listing(
                    query,
                    entries,
                    ListArrangement.arrange(entries, query),
                    whole?.dtos,
                ).also { listingCache = it }
        }
    }

    private class Cached<T>(
        val key: Any,
        val at: Long,
        val value: T,
    )

    /**
     * The indexes the grid pages through. Nixie (TV-S1): several, the last used kept, for the session: each library's
     * index fetched once, not again whenever another library was opened in between (Glacier kept one).
     */
    private val indexCache = object : LinkedHashMap<String, Cached<List<IndexEntry>>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached<List<IndexEntry>>>) =
            size > NixieLibraryPaging.INDEXES_KEPT
    }

    // Nixie (TV-S1): read without the index lock by indexReady and knownLetters.
    private fun cachedIndex(key: String): Cached<List<IndexEntry>>? = synchronized(indexCache) { indexCache[key] }

    private fun keepIndex(key: String, index: Cached<List<IndexEntry>>) = synchronized(indexCache) {
        indexCache[key] = index
    }
    private val indexLock = Mutex()
    private val refreshing = mutableSetOf<String>()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Every title of [query] with what the sorts need. Kept for the next pages and the A–Z
     * rail, and on the device ([NameIndexStore]): a big library takes the server half a
     * minute to list. A fresh look (the first page) takes the newest copy and, when that is
     * old, has a new one fetched in the background for next time. Narrowed to favorites or
     * unwatched, and the playlists, it is not saved: a change elsewhere can take a title out (or
     * add a playlist), so each fresh look fetches it again unless that was a moment ago.
     */
    private suspend fun index(
        session: Session,
        query: LibraryQuery,
        fresh: Boolean = false,
        // Nixie (TV-S1): decided per request (see listing).
        waited: Boolean = true,
    ): List<IndexEntry> = indexLock.withLock {
        val key = indexKey(session, query)
        val now = System.currentTimeMillis()
        val cached = cachedIndex(key) // Nixie (TV-S1): one of several
        when {
            cached != null && !fresh -> cached.value

            // Playlists change from here too (a new one, from a song's or video's options) and are few.
            query.favoritesOnly || query.unwatchedOnly || query.scope == LibraryScope.Playlists ->
                if (cached != null && now - cached.at < WHOLE_FRESH_MS) {
                    cached.value
                } else {
                    // Nixie (TV-S1): small pages and counted when waited for, big pages in the background.
                    fetchIndex(session, query, waitedFor = waited).also { keepIndex(key, Cached(key, now, it)) }
                }

            else -> {
                val savedAt = store.savedAt(key)
                val newest = if (savedAt != null && (cached == null || savedAt > cached.at)) store.read(key) else null
                when {
                    newest != null -> {
                        keepIndex(key, Cached(key, newest.at, newest.entries)) // Nixie (TV-S1)
                        if (now - newest.at > INDEX_REFRESH_MS) refreshInBackground(session, query, key)
                        newest.entries
                    }

                    cached != null -> {
                        if (now - cached.at > INDEX_REFRESH_MS) refreshInBackground(session, query, key)
                        cached.value
                    }

                    // Nixie (TV-S1): small pages and counted when waited for, big pages in the background.
                    else -> fetchIndex(session, query, waitedFor = waited).also {
                        keepIndex(key, Cached(key, now, it)) // Nixie (TV-S1)
                        store.write(key, SavedIndex(now, it))
                    }
                }
            }
        }
    }

    /**
     * One index serves every sort and both directions. The version changes with what an entry holds.
     * The age limits are part of it: the server leaves hidden titles out, so a list fetched under
     * other limits shows titles the profile must not see, or misses ones it may.
     */
    private suspend fun indexKey(session: Session, query: LibraryQuery): String {
        val ages = ageLimits(query)
        return "$INDEX_VERSION|${session.server.id}|${session.user.userId}|${query.unsorted}|${ages?.maxOfficialRating}|${ages?.hasOfficialRating}"
    }

    /** Music and playlists have no age ratings worth filtering on. */
    private suspend fun ageLimits(query: LibraryQuery): AgeLimits? =
        if (query.kind == LibraryKind.Music || query.scope == LibraryScope.Playlists) null else ageFilter.limits()

    /**
     * [query]'s titles from the server. Each page becomes index entries as it comes: 70,000
     * songs as the server's items at once do not fit in a TV's memory.
     */
    private suspend fun fetchIndex(
        session: Session,
        query: LibraryQuery,
        waitedFor: Boolean = true,
    ): List<IndexEntry> {
        val shared = HashMap<String, String>()
        return fetchAll(
            session,
            query,
            light = true,
            waitedFor = waitedFor,
        ) { page -> synchronized(shared) { page.map { entryOf(it, query, shared) } } }
            .distinctBy { it.id }
    }

    /** Saves a new copy of an index for the next fresh look; the one in use stays as it is. */
    private fun refreshInBackground(
        session: Session,
        query: LibraryQuery,
        key: String,
    ) {
        synchronized(refreshing) { if (!refreshing.add(key)) return }
        background.launch {
            try {
                val entries = fetchIndex(session, query, waitedFor = false)
                store.write(key, SavedIndex(System.currentTimeMillis(), entries))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Refreshing a name index failed", e)
            } finally {
                synchronized(refreshing) { refreshing.remove(key) }
            }
        }
    }

    /** A list fetched whole: its titles as index entries and as they came. */
    private class Whole(val entries: List<IndexEntry>, val dtos: Map<UUID, BaseItemDto>)

    private var wholeCache: Cached<Whole>? = null
    private val wholeLock = Mutex()

    /**
     * The whole list of [query], kept for the next pages and the A–Z rail. [fresh] fetches
     * it again unless it was fetched a moment ago (the first page and the rail load together).
     */
    private suspend fun wholeList(
        session: Session,
        query: LibraryQuery,
        fresh: Boolean = false,
    ): Whole = wholeLock.withLock {
        val key = indexKey(session, query)
        wholeCache?.let { cached ->
            val recent = System.currentTimeMillis() - cached.at < WHOLE_FRESH_MS
            if (cached.key == key && (!fresh || recent)) return@withLock cached.value
        }
        val items = fetchAll(session, query) { it }.distinctBy { it.id } // Nixie (TV-S1): no unwatched grouping
        val whole = Whole(items.map { entryOf(it, query) }, items.associateBy { it.id })
        wholeCache = Cached(key, System.currentTimeMillis(), whole)
        whole
    }

    /**
     * Every title of [query], page by page, a few pages at once, each page made into [T] as it comes.
     * When the grid [waitedFor] it, [progress] counts the titles that came.
     */
    private suspend fun <T> fetchAll(
        session: Session,
        query: LibraryQuery,
        light: Boolean = false,
        waitedFor: Boolean = true,
        convert: (List<BaseItemDto>) -> List<T>,
    ): List<T> {
        // Nixie (TV-S1): a list someone waits for comes in small pages, counted in [progress]; one nobody waits for
        // (the background index) in big pages, two at once, so the server is asked less often and the pages the grid
        // asks for meanwhile stay quick. Only the first page asks the server to count.
        val pageSize = if (waitedFor) NixieLibraryPaging.WAITED_PAGE else NixieLibraryPaging.BACKGROUND_PAGE
        val first = fetch(session, query, start = 0, limit = pageSize, light = light)
        val total = first.totalRecordCount.coerceAtMost(MAX_WHOLE)
        val counted = waitedFor && total > pageSize // Nixie (TV-S1): pageSize
        val loaded = AtomicInteger(first.items.size)
        if (counted) _progress.value = LoadProgress(query, loaded.get(), total)
        val permits = Semaphore(
            if (waitedFor) NixieLibraryPaging.WAITED_PARALLEL else NixieLibraryPaging.BACKGROUND_PARALLEL,
        )
        try {
            val rest = coroutineScope {
                // Nixie (TV-S1): pageSize
                (pageSize until total step pageSize).map { start ->
                    async {
                        permits.withPermit {
                            val page = fetch(
                                session,
                                query,
                                start = start,
                                limit = pageSize, // Nixie (TV-S1)
                                light = light,
                                withTotal = false, // Nixie (TV-S1): only the first page counts the list
                            ).items
                            if (counted) {
                                val now = loaded.addAndGet(page.size).coerceAtMost(total)
                                // Pages finish in any order: the count only goes up.
                                _progress.update {
                                    if (it?.query == query && it.loaded >= now) {
                                        it
                                    } else {
                                        LoadProgress(query, now, total)
                                    }
                                }
                            }
                            convert(page)
                        }
                    }
                }.awaitAll()
            }
            return convert(first.items) + rest.flatten()
        } finally {
            if (counted) _progress.update { if (it?.query == query) null else it }
        }
    }

    /** The titles with [ids], in that order. A few at once, so the address stays short. */
    private suspend fun itemsByIds(session: Session, ids: List<UUID>): List<BaseItemDto> {
        if (ids.isEmpty()) return emptyList()
        val userId = UUID.fromString(session.user.userId)
        val found = coroutineScope {
            ids.chunked(IDS_PER_REQUEST).map { chunk ->
                async {
                    session.api.libraryApi.getItems(
                        userId = userId,
                        ids = chunk,
                        fields = listOf(ItemFields.CHILD_COUNT, ItemFields.GENRES, ItemFields.SORT_NAME),
                        enableUserData = true,
                    ).content.items
                }
            }.awaitAll().flatten()
        }.associateBy { it.id }
        return ids.mapNotNull { found[it] }
    }

    /** The last [showCollectionIds], the user they belong to, and when they were found. */
    private var showCollectionsCache: Triple<String, Long, List<UUID>>? = null
    private val showCollectionsLock = Mutex()

    /**
     * Collections that hold at least one show. The server groups only movies into
     * collections and cannot filter collections by what they hold, so each collection
     * is asked for its shows, a few at a time. Collections change rarely: the answer is
     * kept for a while.
     */
    private suspend fun showCollectionIds(session: Session): List<UUID> = showCollectionsLock.withLock {
        val userId = UUID.fromString(session.user.userId)
        showCollectionsCache?.let { (user, at, ids) ->
            if (user == session.user.userId &&
                System.currentTimeMillis() - at < SHOW_COLLECTIONS_FRESH_MS
            ) {
                return@withLock ids
            }
        }
        val collections = session.api.libraryApi.getItems(
            userId = userId,
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.BOX_SET),
            enableImages = false,
            enableUserData = false,
        ).content.items.map { it.id }
        val limit = Semaphore(SHOW_COLLECTIONS_PARALLEL)
        val ids = coroutineScope {
            collections.map { id ->
                async {
                    limit.withPermit {
                        val shows = session.api.libraryApi.getItems(
                            userId = userId,
                            parentId = id,
                            includeItemTypes = listOf(BaseItemKind.SERIES),
                            limit = 0,
                            enableTotalRecordCount = true,
                        ).content.totalRecordCount
                        id.takeIf { shows > 0 }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        showCollectionsCache = Triple(session.user.userId, System.currentTimeMillis(), ids)
        ids
    }

    private suspend fun fetch(
        session: Session,
        query: LibraryQuery,
        start: Int,
        limit: Int,
        /** Only what [index] needs: no images, user data or extra fields. */
        light: Boolean = false,
        /** Nixie (TV-S1): a page of a list shown in the server's order (NixieLibraryPaging), not the app's. */
        serverSort: ServerSort = PagesApart,
        /** Nixie (TV-S1): whether the server counts the list; only its first page needs that. */
        withTotal: Boolean = true,
        /** Nixie (TV-S1): only the titles whose sort name comes before it (an A–Z jump's place). */
        nameLessThan: String? = null,
        /** Nixie (TV-S13): only the titles whose sort name is this or comes after it (an A–Z jump's first page). */
        nameFrom: String? = null,
    ): BaseItemDtoQueryResult {
        val userId = UUID.fromString(session.user.userId)
        val sortBy = serverSort.by // Nixie (TV-S1): PagesApart, Glacier's own, unless in the server's order
        val favorite = true.takeIf { query.favoritesOnly }
        val ages = ageLimits(query)
        val order = serverSort.order // Nixie (TV-S1): as sortBy
        val only = if (query.showCollections) showCollectionIds(session) else emptyList()
        // Asked without ids, the server would list every collection.
        if (query.showCollections && only.isEmpty()) {
            return BaseItemDtoQueryResult(
                items = emptyList(),
                totalRecordCount = 0,
                startIndex = 0,
            )
        }
        return when (query.scope) {
            LibraryScope.Genres -> session.api.genreApi.getGenres(
                userId = userId,
                parentId = query.libraryId,
                includeItemTypes = listOf(query.kind.itemType),
                fields = listOf(ItemFields.ITEM_COUNTS, ItemFields.SORT_NAME),
                startIndex = start,
                limit = limit,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = order,
            ).content

            // Deprecated since the 12.1 API, but nothing else lists only album artists: /Artists and
            // /Items with MusicArtist also list every guest artist of a song (checked on 12.2).
            LibraryScope.Artists ->
                @Suppress("DEPRECATION")
                session.api.artistApi.getAlbumArtists(
                    userId = userId,
                    parentId = query.libraryId,
                    fields = listOf(ItemFields.CHILD_COUNT, ItemFields.SORT_NAME, ItemFields.DATE_CREATED),
                    startIndex = start,
                    limit = limit,
                    isFavorite = favorite,
                    sortBy = sortBy,
                    sortOrder = order,
                    enableUserData = true,
                ).content

            else -> session.api.libraryApi.getItems(
                userId = userId,
                // Music videos come from their own libraries, not the music library the tab belongs to.
                parentId = if (query.scope == LibraryScope.Playlists ||
                    query.scope == LibraryScope.MusicVideos
                ) {
                    null
                } else {
                    query.libraryId
                },
                ids = only,
                recursive = true,
                includeItemTypes = listOf(itemTypeFor(query)),
                genreIds = listOfNotNull(query.genreId),
                maxOfficialRating = ages?.maxOfficialRating,
                hasOfficialRating = ages?.hasOfficialRating,
                filters = listOfNotNull(ItemFilter.IS_UNPLAYED.takeIf { query.unwatchedOnly }), // Nixie (TV-S1)
                isFavorite = favorite,
                // Filtered, the titles themselves count: a collection would hide which of its movies match.
                collapseBoxSetItems = query.groupsCollections.takeIf { it }, // Nixie (TV-S1): unwatched included
                fields = if (light) {
                    listOf(
                        ItemFields.SORT_NAME,
                        ItemFields.DATE_CREATED,
                    )
                } else {
                    listOf(
                        ItemFields.CHILD_COUNT,
                        ItemFields.GENRES,
                        ItemFields.SORT_NAME,
                        ItemFields.DATE_CREATED,
                    )
                },
                enableImages = !light,
                // Nixie (TV-S1): a card shows one picture of each kind at most.
                imageTypeLimit = 1.takeIf { serverSort !== PagesApart },
                enableUserData = !light,
                startIndex = start,
                limit = limit,
                sortBy = sortBy,
                sortOrder = order,
                enableTotalRecordCount = withTotal, // Nixie (TV-S1): only the first page counts the list
                nameLessThan = nameLessThan, // Nixie (TV-S1): an A–Z jump's place
                nameStartsWithOrGreater = nameFrom, // Nixie (TV-S13): an A–Z jump's first page
            ).content
        }
    }

    private fun itemTypeFor(query: LibraryQuery): BaseItemKind = when (query.scope) {
        LibraryScope.Collections -> BaseItemKind.BOX_SET
        LibraryScope.Playlists -> BaseItemKind.PLAYLIST
        LibraryScope.Songs -> BaseItemKind.AUDIO
        LibraryScope.MusicVideos -> BaseItemKind.MUSIC_VIDEO
        else -> query.kind.itemType
    }

    private val LibraryKind.itemType: BaseItemKind
        get() = when (this) {
            LibraryKind.Movies -> BaseItemKind.MOVIE

            LibraryKind.Shows -> BaseItemKind.SERIES

            LibraryKind.Music -> BaseItemKind.MUSIC_ALBUM

            LibraryKind.MusicVideos -> BaseItemKind.MUSIC_VIDEO

            // Browsed by folder (FolderRepository), never listed here.
            LibraryKind.HomeVideos -> BaseItemKind.VIDEO
        }

    private companion object {
        /**
         * The app puts the titles in order itself (ListArrangement). This only keeps the pages apart.
         * Nixie (TV-S1): the order [fetch] asks for unless a list is shown in the server's own.
         */
        val PagesApart = ServerSort(listOf(ItemSortBy.SORT_NAME, ItemSortBy.DATE_CREATED), listOf(SortOrder.ASCENDING))

        // Nixie (TV-S1): the page sizes and requests at once of a whole list are NixieLibraryPaging's (waited or not).

        /** The grid's first page and the A–Z rail ask for the whole list together: one fetch serves both. */
        const val WHOLE_FRESH_MS = 3_000L

        /**
         * An [index] older than this is fetched again in the background. The pages fetched with it are always fresh.
         * Nixie (TV-S1): a day (Glacier: 30 minutes). Pages from the server show new titles anyway, and the server
         * belongs to someone else: its whole library is not asked for again at nearly every open.
         */
        const val INDEX_REFRESH_MS = 24 * 60 * 60_000L
        const val TAG = "Library"

        /** Ids per request when a page is fetched by its ids. */
        const val IDS_PER_REQUEST = NixieLibraryPaging.IDS_PER_REQUEST // Nixie (TV-S13): Glacier's 40

        /** How long the collections holding shows are trusted before they are looked up again. */
        const val SHOW_COLLECTIONS_FRESH_MS = 5 * 60_000L

        /** Collections asked for their shows at once. */
        const val SHOW_COLLECTIONS_PARALLEL = 8

        /** Beyond this many titles only the first ones are listed. */
        const val MAX_WHOLE = 200_000

        /** Part of the index key: saved indexes of an older form are not read. */
        const val INDEX_VERSION = 2
    }

    private fun requireSession(): Session = checkNotNull(sessions.session.value) { "No profile is signed in" }
}
