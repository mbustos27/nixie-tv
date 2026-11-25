// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.skips

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.playback.MediaSegment
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentAction
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentPolicy
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.mbustos27.nixie.tv.data.play.NixiePlaySettings
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.ClientInfo

/**
 * The player's skips from all four sources ([SegmentSource]): the server's segments come from Glacier's
 * `PlaybackRepository.segments`, this adds the chapters', a learned one, and (opt-in) the online ones.
 */
@Singleton
class SkipChain internal constructor(
    /** The play stream's settings of the signed-in profile, read fresh. */
    private val playSettings: suspend () -> NixiePlaySettings,
    /** The same, as last seen, for the player's ticker. */
    private val playSettingsNow: () -> NixiePlaySettings,
    /** The signed-in profile's server and user ids; null while no one is signed in. */
    private val profile: () -> Pair<String, String>?,
    /** The show's public ids and genres, from the user's own server. */
    private val showIds: suspend (UUID) -> ShowInfo?,
    private val store: LearnedSkipStore,
    private val online: IntroOnline,
) {
    @Inject
    constructor(
        sessions: SessionManager,
        settings: SettingsRepository,
        store: LearnedSkipStore,
        online: IntroOnline,
    ) : this(
        playSettings = { settings.current().nixiePlay },
        playSettingsNow = { settings.settings.value.nixiePlay },
        profile = { sessions.session.value?.let { it.server.id to it.user.userId } },
        showIds = ShowIdsFromServer(sessions)::get,
        store = store,
        online = online,
    )

    val learnSkips: Boolean get() = playSettingsNow().learnSkips

    fun learner(): SkipLearner = SkipLearner(store)

    /** Where the season's learned skip is kept; null for anything but an episode of a numbered season. */
    fun learnedKey(details: ItemDetails): String? {
        val (serverId, userId) = profile() ?: return null
        val series = details.seriesId ?: details.item.seriesId ?: return null
        val season = details.item.seasonNumber ?: return null
        if (details.item.kind != ItemKind.Episode) return null
        return LearnedSkipStore.key(serverId, userId, series.toString(), season)
    }

    /** The server's [server] segments with the chapters' and a learned one for the kinds still missing. */
    suspend fun withLocalSources(
        details: ItemDetails,
        server: List<MediaSegment>,
        policy: SegmentPolicy,
    ): List<MediaSegment> = withContext(Dispatchers.IO) {
        val chapters = ChapterSkips.segments(details.chapters, details.item.runtimeMs)
        val learned = SkipLearner.offered(store, learnedKey(details), playSettings().learnSkips)
        SkipSources.combine(server, chapters, learned, wanted(policy))
    }

    /**
     * The online sources' segments for the kinds [current] still lacks; empty with lookups off (the default), for
     * anything but a movie or an episode, or when nothing is missing. Asks the user's own server for the show's ids.
     */
    suspend fun lookupOnline(
        details: ItemDetails,
        current: List<MediaSegment>,
        policy: SegmentPolicy,
    ): List<MediaSegment> = withContext(Dispatchers.IO) {
        if (!playSettings().lookupOnline) return@withContext emptyList()
        val item = details.item
        val movie = item.kind == ItemKind.Movie
        if (!movie && item.kind != ItemKind.Episode) return@withContext emptyList()
        val missing = SkipSources.missing(current, wanted(policy))
        if (missing.isEmpty()) return@withContext emptyList()
        val showId = if (movie) item.id else details.seriesId ?: item.seriesId ?: return@withContext emptyList()
        val show = showIds(showId) ?: return@withContext emptyList()
        online.lookup(LookupItem(movie, item.seasonNumber, item.episodeNumber, item.runtimeMs), show, missing)
    }

    /** "Forget learned skips". */
    fun forgetLearned() {
        store.clear()
        online.reset()
    }

    companion object {
        /** The kinds the player does something with: a source is asked only for those. */
        fun wanted(policy: SegmentPolicy): Set<SegmentKind> =
            SegmentKind.entries.filterTo(mutableSetOf()) { policy.action(it) != SegmentAction.None }
    }
}

/** A show's public ids and genres from the user's own server, kept in memory for the session. */
private class ShowIdsFromServer(private val sessions: SessionManager) {
    private val shows = ConcurrentHashMap<UUID, ShowInfo>()

    @Suppress("TooGenericExceptionCaught") // The SDK's failures are many; any of them means no lookup.
    suspend fun get(id: UUID): ShowInfo? {
        shows[id]?.let { return it }
        val session = sessions.session.value ?: return null
        return try {
            val dto = session.api.libraryApi.getItem(itemId = id, userId = UUID.fromString(session.user.userId)).content
            ShowInfo(
                ids = dto.providerIds.orEmpty().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
                    .filterValues(String::isNotBlank),
                genres = dto.genres.orEmpty(),
            ).also { shows[id] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Loading the show's ids for the online lookup failed", e)
            null
        }
    }

    private companion object {
        const val TAG = "SkipChain"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NixieSkipModule {

    @Provides
    @Singleton
    fun provideLearnedSkips(@ApplicationContext context: Context): LearnedSkipStore {
        val writer = Executors.newSingleThreadExecutor { Thread(it, "learned-skips").apply { isDaemon = true } }
        return LearnedSkipStore(File(context.filesDir, "nixie/learned-skips.json"), save = writer::execute)
    }

    @Provides
    @Singleton
    fun provideIntroOnline(clientInfo: ClientInfo): IntroOnline =
        IntroOnline(PlainHttpGet, "NixieTV/${clientInfo.version}", log = { Log.i("IntroOnline", it) })
}
