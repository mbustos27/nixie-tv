// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.search

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.AgeFilter
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaMapper
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.personApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

/** One step of a search: everything found so far, ranked; [complete] once every request has answered. */
class SearchUpdate(val ranked: RankedResults, val complete: Boolean)

/**
 * Nixie's search: the server's matches for shows, movies, episodes, collections, albums and people, with titles by a
 * matching person or genre as weak matches (Glacier's "title, genre, person"), ranked on the box ([SearchRanking]).
 *
 * TV-S4 (a server with tens of thousands of films: "takes wayyyy too long"): one request per kind with a small limit,
 * the one field the ranking needs and one image, all at once, each answer shown as it arrives ([results]). The weak
 * matches wait for the kinds, so they never hold up the first results.
 */
@Singleton
class NixieSearch @Inject constructor(private val sessions: SessionManager, private val ageFilter: AgeFilter) {

    /** Everything at once: the last step of [results]. */
    suspend fun search(query: String): RankedResults = results(query).last().ranked

    /**
     * The search for [query], step by step: each answer re-ranks everything found so far and is sent at once, the last
     * step [SearchUpdate.complete]. An empty term asks the server nothing; the page decides when a short one is asked
     * for ([searchable]). Cancelling the
     * collection cancels the requests in flight. Each request fails quietly (review M2: one failed kind wiped what was
     * on screen); the search fails only when every kind and the people request have failed.
     */
    fun results(query: String): Flow<SearchUpdate> = channelFlow {
        val term = query.trim()
        if (term.isEmpty()) {
            send(SearchUpdate(RankedResults(term, emptyList()), complete = true))
            return@channelFlow
        }
        val session = checkNotNull(sessions.session.value) { "No profile is signed in" }
        val ask = Requests(session, term)
        val found = Found(term)
        val lock = Mutex()

        // A step goes out when what would show changed: an empty answer, or episodes still held back, send nothing.
        var sent = emptyList<Pair<UUID, Boolean>>()
        suspend fun arrive(change: Found.() -> Unit) = lock.withLock {
            found.change()
            val ranked = found.ranked()
            val shape = ranked.items.map { it.item.id to it.folded }
            if (shape != sent) {
                sent = shape
                send(SearchUpdate(ranked, complete = false))
            }
        }
        val failures = mutableListOf<Throwable>()
        coroutineScope {
            KINDS.forEachIndexed { index, (kind, limit) ->
                launch {
                    val answer = attempt { ask.kind(kind, limit) }
                    val items = answer.getOrNull().orEmpty()
                    arrive {
                        own[index] = items
                        answer.exceptionOrNull()?.let { failures += it }
                    }
                }
            }
            launch {
                val answer = attempt { ask.people() }
                val people = answer.getOrNull().orEmpty()
                arrive {
                    own[KINDS.size] = people
                    answer.exceptionOrNull()?.let { failures += it }
                }
            }
        }
        if (failures.size == KINDS.size + 1) throw failures.first()
        // The weak matches: titles with one of the first people found, then with a matching genre.
        coroutineScope {
            val byPerson = async {
                quietly { ask.titlesWith(people = found.people().take(MATCH_LIMIT).map { it.id }) }
            }
            val byGenre = async { quietly { ask.titlesWith(genres = ask.genres()) } }
            val person = byPerson.await()
            arrive { related = person }
            val genre = byGenre.await()
            arrive { related = person + genre }
        }
        send(SearchUpdate(found.ranked(), complete = true))
    }.flowOn(Dispatchers.IO)

    private suspend fun quietly(block: suspend () -> List<MediaItem>): List<MediaItem> =
        attempt(block).getOrNull().orEmpty()

    /** One request's titles through the age filter, or its failure (logged without the search term). */
    // Whatever one request throws is that request's failure, never the whole search's (review M2).
    @Suppress("TooGenericExceptionCaught")
    private suspend fun attempt(block: suspend () -> List<MediaItem>): Result<List<MediaItem>> = try {
        Result.success(ageFilter.screen(block()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Search: a request failed (${e::class.simpleName})")
        Result.failure(e)
    }

    /** What has arrived: the kinds' and people's answers in [KINDS] order, then the weak matches. */
    private class Found(private val term: String) {
        val own = arrayOfNulls<List<MediaItem>>(KINDS.size + 1)
        var related: List<MediaItem> = emptyList()

        fun people(): List<MediaItem> = own[KINDS.size].orEmpty()

        fun ranked(): RankedResults {
            // The ranking breaks ties by kind before the server's order, so the order the answers came in never shows.
            // Review: episodes wait for the shows' answer, as a show found folds its episodes away; shown before it,
            // they would appear and then fold under the focus.
            val showsIn = own[SHOWS] != null
            val mine = own.withIndex().flatMap { (i, items) ->
                if (i == EPISODES && !showsIn) emptyList() else items.orEmpty()
            }
            val ids = mine.mapTo(HashSet()) { it.id }
            val others = related.filter { it.id !in ids }
            return SearchRanking.rank(term, mine + others, weak = others.mapTo(HashSet()) { it.id })
        }
    }

    /** The server requests of one search. */
    private class Requests(private val session: Session, private val term: String) {
        private val userId = UUID.fromString(session.user.userId)
        private val mapper = MediaMapper(session.api)

        /** One kind's matches: the sort name for the ranking, one image for the card, no total to count. */
        suspend fun kind(kind: BaseItemKind, limit: Int): List<MediaItem> = session.api.libraryApi.getItems(
            userId = userId,
            recursive = true,
            searchTerm = term,
            includeItemTypes = listOf(kind),
            fields = CARD_FIELDS,
            enableUserData = true,
            imageTypeLimit = 1,
            enableImageTypes = CARD_IMAGES,
            enableTotalRecordCount = false,
            limit = limit,
        ).content.items.map(mapper::item)

        suspend fun people(): List<MediaItem> = session.api.personApi.getPersons(
            searchTerm = term,
            userId = userId,
            limit = PEOPLE_LIMIT,
            imageTypeLimit = 1,
            enableImageTypes = CARD_IMAGES,
        ).content.items.map(mapper::item)

        suspend fun genres(): List<UUID> = session.api.genreApi.getGenres(
            searchTerm = term,
            userId = userId,
            limit = MATCH_LIMIT,
            enableImages = false,
            enableTotalRecordCount = false,
        ).content.items.map { it.id }

        /** Films, shows and albums with one of [people] or [genres], newest first. */
        suspend fun titlesWith(people: List<UUID> = emptyList(), genres: List<UUID> = emptyList()): List<MediaItem> {
            if (people.isEmpty() && genres.isEmpty()) return emptyList()
            return session.api.libraryApi.getItems(
                userId = userId,
                recursive = true,
                personIds = people,
                genreIds = genres,
                includeItemTypes = RELATED_KINDS,
                fields = CARD_FIELDS,
                enableUserData = true,
                imageTypeLimit = 1,
                enableImageTypes = CARD_IMAGES,
                enableTotalRecordCount = false,
                limit = RELATED_LIMIT,
                sortBy = listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.DESCENDING),
            ).content.items.map(mapper::item)
        }
    }

    companion object {
        private const val TAG = "Search"

        /**
         * A term of this many characters is searched as soon as typing pauses (TV-S4: no request for one letter while
         * typing); a shorter one only after a longer pause or Enter (review: titles such as "M" or "9").
         */
        const val MIN_LENGTH = 2

        fun searchable(term: String): Boolean = term.trim().let { it.codePointCount(0, it.length) >= MIN_LENGTH }

        /** The kinds searched, one request each, with how many each brings: enough for the grid's first rows. */
        internal val KINDS = listOf(
            BaseItemKind.SERIES to 24,
            BaseItemKind.MOVIE to 24,
            BaseItemKind.EPISODE to 24,
            BaseItemKind.BOX_SET to 12,
            BaseItemKind.MUSIC_ALBUM to 12,
        )
        private const val PEOPLE_LIMIT = 12
        private val SHOWS = KINDS.indexOfFirst { it.first == BaseItemKind.SERIES }
        private val EPISODES = KINDS.indexOfFirst { it.first == BaseItemKind.EPISODE }

        /** People and genres whose titles join as weak matches, and how many titles they bring. */
        private const val MATCH_LIMIT = 3
        private const val RELATED_LIMIT = 24
        private val RELATED_KINDS = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.MUSIC_ALBUM)

        /** The ranking reads the sort name; a card shows the primary image (an episode its show's). */
        private val CARD_FIELDS = listOf(ItemFields.SORT_NAME)
        private val CARD_IMAGES = listOf(ImageType.PRIMARY)
    }
}
