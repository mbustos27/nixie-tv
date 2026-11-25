package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.settings.HomeSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SpotlightSource
import io.github.glacier_jellyfin.androidtv.core.data.settings.SpotlightType
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.mbustos27.nixie.tv.data.ContinueWatchingRules
import io.github.mbustos27.nixie.tv.data.HomeParts
import io.github.mbustos27.nixie.tv.data.HomeViews
import io.github.mbustos27.nixie.tv.data.TidyNames
import io.github.mbustos27.nixie.tv.data.localNow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder

@Singleton
class HomeRepository @Inject constructor(
    private val sessions: SessionManager,
    private val ageFilter: AgeFilter,
    private val liveTvRepository: LiveTvRepository,
    // Nixie: the home stream's settings (NixieHomeSettings).
    private val settingsRepository: SettingsRepository,
) : HomeParts { // Nixie: Home's parts, each on its own (tv-home-start)

    private val _kinds = MutableStateFlow(DefaultKinds)

    /**
     * The library kinds the server has, in navigation order; known once the home
     * screen loaded. Until then the usual three, without music videos.
     */
    val kinds: StateFlow<List<LibraryKind>> = _kinds.asStateFlow()

    private val _liveTv = MutableStateFlow(false)

    /** The server offers Live TV to this profile; known once the home screen loaded. */
    val liveTv: StateFlow<Boolean> = _liveTv.asStateFlow()

    private val _libraries = MutableStateFlow<List<Library>>(emptyList())

    /** The libraries of the server, as on the home screen; known once it loaded. */
    val libraries: StateFlow<List<Library>> = _libraries.asStateFlow()

    private val _latestLibraries = MutableStateFlow<List<Library>>(emptyList())

    /** The libraries with a "New in" row, for Settings › Home; known once the home screen loaded. */
    val latestLibraries: StateFlow<List<Library>> = _latestLibraries.asStateFlow()

    // ---- Nixie (tv-home-start): Home's parts, each asked for on its own (HomeStart). Home used to wait for all of
    // them, the libraries' counts included, before it drew anything: about a minute on a big, slow server. ----

    /** Home drawn from what is saved on the device: the navigation's kinds and Live TV entry follow it at once. */
    fun seed(views: HomeViews) {
        if (_libraries.value.isNotEmpty()) return
        _kinds.value = views.libraries.map { it.kind }.distinct().sorted()
        _liveTv.value = views.liveTv
        _libraries.value = views.libraries
        _latestLibraries.value = views.latestLibraries
    }

    /**
     * The libraries without their counts, for the top bar; the kinds and Live TV follow. Nixie (tv-home-two-rows): one
     * request: Home has no "New in" rows any more, so the profile's "leave out of latest" list is not asked.
     */
    override suspend fun views(): HomeViews = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        val known = _libraries.value.associate { it.id to it.itemCount }
        val libraries = libraries(session, mapper).map { it.copy(itemCount = known[it.id]) }
        _kinds.value = libraries.map { it.kind }.distinct().sorted()
        _libraries.value = libraries
        HomeViews(libraries, liveTv = _liveTv.value)
    }

    /** Each library's count of titles: a count is a query over the whole library. Nixie (tv-home-two-rows): not on Home. */
    suspend fun counts(libraries: List<Library>): Map<UUID, Int> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val counts = libraries.map { library -> async { library.id to count(session, library.id, library.kind) } }
            .awaitAll()
            .mapNotNull { (id, count) -> count?.let { id to it } }
            .toMap()
        if (counts.isNotEmpty()) {
            // Nixie (tv-home-two-rows): read and written at once; the Libraries page counts a few libraries at a time.
            _libraries.update { list -> list.map { it.withCount(counts) } }
            _latestLibraries.update { list -> list.map { it.withCount(counts) } }
        }
        counts
    }

    private fun Library.withCount(counts: Map<UUID, Int>) = counts[id]?.let { copy(itemCount = it) } ?: this

    override suspend fun resume(): List<BaseItemDto> = withContext(Dispatchers.IO) { resume(requireSession()) }

    override suspend fun nextUp(): List<BaseItemDto> = withContext(Dispatchers.IO) { nextUp(requireSession()) }

    // Nixie (tv-home-two-rows): latest, recentAlbums, favoriteSongs, favorites, liveChannels and counts are no longer
    // parts of Home, which shows Continue watching and Up next only.
    suspend fun latest(library: Library): List<MediaItem> = withContext(Dispatchers.IO) {
        val session = requireSession()
        ageFilter.screen(latest(session, session.userId, library).map(MediaMapper(session.api)::item))
    }

    suspend fun recentAlbums(): List<MediaItem> = withContext(Dispatchers.IO) {
        val session = requireSession()
        recentAlbums(session, MediaMapper(session.api))
    }

    suspend fun favoriteSongs(): List<MusicTrack> = withContext(Dispatchers.IO) {
        val session = requireSession()
        favoriteSongs(session, MediaMapper(session.api))
    }

    override suspend fun items(dtos: List<BaseItemDto>): List<MediaItem> {
        if (dtos.isEmpty()) return emptyList()
        val session = requireSession()
        return ageFilter.screen(dtos.map(MediaMapper(session.api)::item))
    }
    // Nixie (tv-home-start) end.

    /** The TV channels for the Live TV rows, favorites first, otherwise in the server's order. */
    suspend fun liveChannels(): List<LiveChannel> =
        liveTvRepository.channels().filterNot { it.radio }.sortedByDescending { it.favorite }

    /** Albums of the songs played last: the server keeps the play date on songs, not on albums. */
    private suspend fun recentAlbums(session: Session, mapper: MediaMapper): List<MediaItem> {
        val albumIds = session.api.libraryApi.getItems(
            userId = session.userId,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            filters = listOf(ItemFilter.IS_PLAYED),
            sortBy = listOf(ItemSortBy.DATE_PLAYED),
            sortOrder = listOf(SortOrder.DESCENDING),
            limit = RECENT_SONGS,
            enableImages = false,
            enableTotalRecordCount = false, // Nixie: no count it does not show (tv-home-start)
        ).content.items.mapNotNull { it.albumId }.distinct().take(ROW_LIMIT)
        if (albumIds.isEmpty()) return emptyList()
        val albums = session.api.libraryApi.getItems(
            userId = session.userId,
            ids = albumIds,
            enableUserData = true,
            enableTotalRecordCount = false, // Nixie (tv-home-start)
        )
            .content.items.associateBy { it.id }
        return albumIds.mapNotNull { albums[it] }.map(mapper::item)
    }

    /** Favorite movies and shows for their home row, newest first; the row follows a favorite set or removed elsewhere. */
    suspend fun favorites(): List<MediaItem> = withContext(Dispatchers.IO) {
        val session = requireSession()
        favorites(session, MediaMapper(session.api))
    }

    private suspend fun favorites(session: Session, mapper: MediaMapper): List<MediaItem> =
        session.api.libraryApi.getItems(
            userId = session.userId,
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
            recursive = true,
            isFavorite = true,
            sortBy = listOf(ItemSortBy.DATE_CREATED),
            sortOrder = listOf(SortOrder.DESCENDING),
            // Nixie: what a poster card shows, and no count (tv-home-start).
            fields = NIXIE_CARD_FIELDS,
            enableImageTypes = NIXIE_CARD_IMAGES,
            imageTypeLimit = 1,
            enableTotalRecordCount = false,
            enableUserData = true,
            limit = FAVORITES,
        ).content.items.map(mapper::item).let { ageFilter.screen(it) }

    private suspend fun favoriteSongs(session: Session, mapper: MediaMapper): List<MusicTrack> =
        session.api.libraryApi.getItems(
            userId = session.userId,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            isFavorite = true,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            fields = listOf(ItemFields.MEDIA_STREAMS),
            enableUserData = true,
            limit = FAVORITE_SONGS,
            enableTotalRecordCount = false, // Nixie (tv-home-start)
        ).content.items.map(mapper::track)

    /**
     * The spotlight as Settings › Home asks for it, see [spotlightPool]. "Continue
     * watching" comes from [continueWatching]; the other sources ask the server.
     * Only titles with a backdrop qualify. No source chosen means no spotlight;
     * nothing matching falls back to the newest titles of the type, as the
     * design falls back to something rather than nothing.
     */
    suspend fun spotlight(settings: HomeSettings, continueWatching: List<MediaItem>): List<MediaItem> = withContext(
        Dispatchers.IO,
    ) {
        val sources = settings.spotlightSources
        if (sources.isEmpty()) return@withContext emptyList()
        val count = settings.spotlightCount.count
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        suspend fun fromServer(
            sort: ItemSortBy,
            favorites: Boolean,
            unwatched: Boolean,
        ): List<MediaItem> = session.api.libraryApi.getItems(
            userId = session.userId,
            recursive = true,
            includeItemTypes = settings.spotlightType.itemTypes,
            isFavorite = true.takeIf { favorites },
            filters = listOfNotNull(ItemFilter.IS_UNPLAYED.takeIf { unwatched }),
            imageTypes = listOf(ImageType.BACKDROP),
            sortBy = listOf(sort),
            sortOrder = listOf(SortOrder.DESCENDING),
            fields = FIELDS,
            enableUserData = true,
            limit = count,
            enableTotalRecordCount = false, // Nixie (tv-home-start)
        ).content.items.map(mapper::item).let { ageFilter.screen(it) }
        val unwatched = settings.spotlightUnwatched
        val started = if (SpotlightSource.ContinueWatching in sources) {
            continueWatching.filter { it.backdropUrl != null && settings.spotlightType.matches(it.kind) }
        } else {
            emptyList()
        }
        val others = sources.mapNotNull { source ->
            when (source) {
                SpotlightSource.ContinueWatching -> null
                SpotlightSource.RecentlyAdded -> async { fromServer(ItemSortBy.DATE_CREATED, false, unwatched) }
                SpotlightSource.Favorites -> async { fromServer(ItemSortBy.DATE_CREATED, true, unwatched) }
                SpotlightSource.Random -> async { fromServer(ItemSortBy.RANDOM, false, unwatched) }
            }
        }.awaitAll()
        spotlightPool(started, others, count, Random.Default) { it.id }
            .ifEmpty { fromServer(ItemSortBy.DATE_CREATED, false, false).take(count) }
    }

    suspend fun setFavorite(itemId: UUID, favorite: Boolean) {
        val api = requireSession().api.userDataApi
        withContext(Dispatchers.IO) {
            if (favorite) api.markFavoriteItem(itemId) else api.unmarkFavoriteItem(itemId)
        }
    }

    private suspend fun libraries(session: Session, mapper: MediaMapper): List<Library> = coroutineScope {
        val views = session.api.userViewApi.getUserViews(userId = session.userId).content.items
        _liveTv.value = views.any { it.collectionType == CollectionType.LIVETV }
        views
            .mapNotNull { view -> view.collectionType.toLibraryKind()?.let { view to it } }
            // Nixie: no count here; counts() asks for them once Home's rows are in (tv-home-start).
            .map { (view, kind) -> mapper.library(view, kind, null) }
            // Nixie: library names tidied ("[HUB] Kids Movies" reads "Kids Movies"), unless two would read the same.
            .let { list -> list.zip(TidyNames.libraries(list.map { it.name })) { lib, name -> lib.copy(name = name) } }
    }

    private suspend fun count(
        session: Session,
        libraryId: UUID,
        kind: LibraryKind,
    ): Int? = runSuspendCatching {
        session.api.libraryApi.getItems(
            userId = session.userId,
            parentId = libraryId,
            recursive = true,
            includeItemTypes = listOf(kind.countedType),
            limit = 0,
            enableTotalRecordCount = true,
        ).content.totalRecordCount
    }.getOrNull()

    private suspend fun resume(session: Session): List<BaseItemDto> = session.api.libraryApi.getResumeItems(
        userId = session.userId,
        limit = ROW_LIMIT,
        mediaTypes = listOf(MediaType.VIDEO),
        // Music videos play in the music player, from the start.
        excludeItemTypes = listOf(BaseItemKind.MUSIC_VIDEO),
        // Nixie: one image of each kind, no count; the hero shows the focused card's overview and genre, no stream
        // (tv-home-two-rows).
        fields = NIXIE_HERO_FIELDS,
        enableImageTypes = NIXIE_CARD_IMAGES,
        imageTypeLimit = 1,
        enableTotalRecordCount = false,
        enableUserData = true,
    ).content.items

    private suspend fun nextUp(session: Session): List<BaseItemDto> = session.api.showApi.getNextUp(
        userId = session.userId,
        limit = ROW_LIMIT,
        fields = NIXIE_HERO_FIELDS, // Nixie (tv-home-two-rows): the hero shows the focused card's overview and genre
        enableImageTypes = NIXIE_CARD_IMAGES, // Nixie: one image of each kind, no count (tv-home-start)
        imageTypeLimit = 1,
        enableTotalRecordCount = false,
        enableUserData = true,
        // Episodes already started are in the resume list.
        enableResumable = false,
        // Nixie: 365 days back, and shows being watched again as Settings says (NixieHomeSettings).
        nextUpDateCutoff = ContinueWatchingRules.nextUpCutoff(localNow()),
        enableRewatching = settingsRepository.settings.value.nixieHome.rewatching,
    ).content.items

    private suspend fun latest(
        session: Session,
        userId: UUID,
        library: Library,
    ): List<BaseItemDto> {
        val items = session.api.libraryApi.getLatestMedia(
            userId = userId,
            parentId = library.id,
            // Nixie: what a poster card shows (tv-home-start).
            fields = NIXIE_CARD_FIELDS,
            enableImageTypes = NIXIE_CARD_IMAGES,
            imageTypeLimit = 1,
            enableUserData = true,
            limit = ROW_LIMIT,
        ).content
        return if (library.kind == LibraryKind.Shows) asShows(session, userId, items) else items
    }

    /**
     * "New in shows" lists shows: the server hands out a season or an episode
     * when only that part is new, which would put a season poster in the row
     * and open the season. Those become their show, once each, in the same order.
     */
    private suspend fun asShows(
        session: Session,
        userId: UUID,
        items: List<BaseItemDto>,
    ): List<BaseItemDto> {
        val showIds = items.map { if (it.type == BaseItemKind.SERIES) it.id else it.seriesId }
        val missing = showIds.filterNotNull().filter { id -> items.none { it.id == id } }.distinct()
        val shows = if (missing.isEmpty()) {
            emptyMap()
        } else {
            session.api.libraryApi.getItems(
                userId = userId,
                ids = missing,
                // Nixie: what a poster card shows, and no count (tv-home-start).
                fields = NIXIE_CARD_FIELDS,
                enableImageTypes = NIXIE_CARD_IMAGES,
                imageTypeLimit = 1,
                enableTotalRecordCount = false,
                enableUserData = true,
            ).content.items.associateBy { it.id }
        }
        return items.mapIndexedNotNull { index, item ->
            val id = showIds[index] ?: return@mapIndexedNotNull item
            if (item.id == id) item else shows[id]
        }.distinctBy { it.id }
    }

    private fun requireSession(): Session = checkNotNull(sessions.session.value) { "No profile is signed in" }

    private val Session.userId: UUID get() = UUID.fromString(user.userId)

    private val SpotlightType.itemTypes: List<BaseItemKind>
        get() = when (this) {
            SpotlightType.All -> listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
            SpotlightType.Movies -> listOf(BaseItemKind.MOVIE)
            SpotlightType.Shows -> listOf(BaseItemKind.SERIES)
        }

    private fun SpotlightType.matches(kind: ItemKind): Boolean = when (this) {
        SpotlightType.All -> kind == ItemKind.Movie || kind == ItemKind.Series || kind == ItemKind.Episode
        SpotlightType.Movies -> kind == ItemKind.Movie
        SpotlightType.Shows -> kind == ItemKind.Series || kind == ItemKind.Episode
    }

    private val LibraryKind.countedType: BaseItemKind
        get() = when (this) {
            LibraryKind.Movies -> BaseItemKind.MOVIE
            LibraryKind.Shows -> BaseItemKind.SERIES
            LibraryKind.Music -> BaseItemKind.MUSIC_ALBUM
            LibraryKind.MusicVideos -> BaseItemKind.MUSIC_VIDEO
            LibraryKind.HomeVideos -> BaseItemKind.VIDEO
        }

    private companion object {
        val DefaultKinds = listOf(LibraryKind.Movies, LibraryKind.Shows, LibraryKind.Music)
        const val ROW_LIMIT = 16
        const val RECENT_SONGS = 200
        const val FAVORITE_SONGS = 100
        const val FAVORITES = 40
        val FIELDS = listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.MEDIA_STREAMS)

        // Nixie (tv-home-start): a poster card's caption shows the first genre; its pictures are these three kinds.
        val NIXIE_CARD_FIELDS = listOf(ItemFields.GENRES)
        val NIXIE_CARD_IMAGES = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB)

        // Nixie (tv-home-two-rows): what the hero shows of the focused card besides what every item carries.
        val NIXIE_HERO_FIELDS = listOf(ItemFields.OVERVIEW, ItemFields.GENRES)
    }
}

/**
 * One spotlight from several sources: "continue watching" ([started]) first in
 * its own order, then the titles of the [other] sources shuffled together,
 * each title once, cut to [count].
 */
internal fun <T> spotlightPool(
    started: List<T>,
    other: List<List<T>>,
    count: Int,
    random: Random,
    id: (T) -> Any,
): List<T> {
    val seen = started.mapTo(HashSet(), id)
    val mixed = other.flatten().filter { seen.add(id(it)) }.shuffled(random)
    return (started.distinctBy(id) + mixed).take(count)
}

/**
 * Started titles first (most recent first, as the server orders them, one
 * per show), then next episodes of shows that are not already in the list.
 */
internal fun mergeContinueWatching(resume: List<BaseItemDto>, nextUp: List<BaseItemDto>): List<BaseItemDto> {
    // One entry per show: the most recently watched of its started episodes.
    val started = resume.distinctBy { it.seriesId ?: it.id }
    val seriesInResume = started.mapNotNullTo(HashSet()) { it.seriesId }
    val ids = started.mapTo(HashSet()) { it.id }
    return started + nextUp.filter { it.id !in ids && (it.seriesId == null || it.seriesId !in seriesInResume) }
}
