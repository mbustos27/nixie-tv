// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.data.media.HdrFormat
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeContent
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.VideoQuality
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Nixie (tv-home-start, tv-home-two-rows): Home as it last showed for a profile: its two rows (Continue watching, Up
 * next), and the top bar's libraries and whether Live TV was offered.
 */
data class SavedHomePage(val content: HomeContent, val liveTv: Boolean)

/**
 * Nixie (tv-home-start): Home as it last showed, kept per profile in memory and on the device, so the next Home (the
 * next start, or Home opened again from the top bar) draws at once from it while the server's answers replace it row
 * by row (desktop Nixie's HomeCache; DESIGN-RULES 1). Titles, ids and picture addresses only, no token: the pictures'
 * addresses carry none. A page saved under other age limits ([key]'s `limits`) is not used. A profile removed from the
 * device (its server too) takes its page along ([forget]).
 */
@Singleton
class SavedHome internal constructor(
    private val folder: File,
    accounts: Flow<AccountState>?,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(@ApplicationContext context: Context, accounts: AccountRepository) : this(
        File(context.cacheDir, "nixie-home"),
        accounts.state,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    @Volatile
    private var memory: Pair<String, SavedHomePage>? = null

    init {
        accounts?.let { state -> scope.launch { forgetRemoved(state) } }
    }

    /** The page saved under [key], from memory or else the device; null when there is none. */
    fun read(key: String): SavedHomePage? {
        memory?.let { (saved, page) -> if (saved == key) return page }
        return runCatching {
            val file = File(folder, fileName(key))
            if (!file.exists()) return null
            val (first, rest) = file.readText().split('\n', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            // The key is the first line: a page saved under other limits (or a clash of names) is not this one.
            if (first != key) return null
            Codec.decode(json.decodeFromString(Page.serializer(), rest))
        }.onFailure { Log.w(TAG, "Reading the saved Home failed", it) }.getOrNull()?.also { memory = key to it }
    }

    /** Keeps [page] for [key], in memory at once and on the device. */
    fun write(key: String, page: SavedHomePage) {
        memory = key to page
        runCatching {
            folder.mkdirs()
            val file = File(folder, fileName(key))
            val partial = File(folder, file.name + ".part")
            partial.writeText(key + "\n" + json.encodeToString(Page.serializer(), Codec.encode(page)))
            if (!partial.renameTo(file)) {
                file.delete()
                partial.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Saving Home failed", it) }
    }

    /** Reads [key]'s page into memory in the background, for the Home about to open (a start). */
    fun prefetch(key: String) {
        scope.launch { read(key) }
    }

    /**
     * A profile that leaves the accounts (removed from the device, or with its server) takes its saved page along.
     * Only a removal seen happen counts: a profile missing from the first state read decides nothing (DESIGN-RULES 4).
     */
    private suspend fun forgetRemoved(accounts: Flow<AccountState>) {
        var known: Set<String>? = null
        accounts.map { state -> state.users.mapTo(HashSet()) { profileOf(it.serverId, it.userId) } }
            .distinctUntilChanged()
            .collect { now ->
                known?.let { before -> (before - now).forEach(::forget) }
                known = now
            }
    }

    /** Deletes [profile]'s page (`server/user`), from memory and the device. */
    fun forget(profile: String) {
        if (memory?.first?.substringBefore(LIMITS_SEPARATOR) == profile) memory = null
        runCatching { File(folder, fileName(profile)).delete() }
            .onSuccess { if (it) Log.i(TAG, "A removed profile's saved Home deleted") }
    }

    /** One file per profile, named by a hash of its server and user. */
    private fun fileName(key: String): String {
        val profile = key.substringBefore(LIMITS_SEPARATOR)
        return MessageDigest.getInstance("SHA-256").digest(profile.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".json"
    }

    companion object {
        private const val TAG = "SavedHome"
        private const val LIMITS_SEPARATOR = '|'
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** The key of [user]'s page, under that profile's age limits and whether it has a PIN. */
        fun key(user: StoredUser): String = key(user.serverId, user.userId, "${user.protection}|${user.pin != null}")

        /** The key of [serverId]/[userId]'s page under the profile's age limits ([limits], any text that names them). */
        fun key(
            serverId: String,
            userId: String,
            limits: String,
        ): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(limits.toByteArray())
                .take(LIMITS_HASH_BYTES).joinToString("") { "%02x".format(it) }
            return "$serverId/$userId$LIMITS_SEPARATOR$hash"
        }

        private const val LIMITS_HASH_BYTES = 8

        private fun profileOf(serverId: String, userId: String) = "$serverId/$userId"
    }
}

/**
 * The saved page. Version 2 (tv-home-two-rows) keeps Home's two rows and the top bar's libraries only; a version 1 page
 * (with "New in", favourites, albums and the hero) is read for those parts, the rest of it ignored.
 */
@Serializable
private data class Page(
    val version: Int = 2,
    val libraries: List<SavedLibrary> = emptyList(),
    val continueWatching: List<SavedItem> = emptyList(),
    val upNext: List<SavedItem> = emptyList(),
    val liveTv: Boolean = false,
)

@Serializable
private data class SavedLibrary(
    val id: String,
    val name: String,
    val kind: String,
    val count: Int? = null,
    val image: String? = null,
)

@Serializable
private data class SavedItem(
    val id: String,
    val kind: String,
    val title: String,
    val sortName: String? = null,
    val year: Int? = null,
    val endYear: Int? = null,
    val ongoing: Boolean = false,
    val communityRating: Float? = null,
    val officialRating: String? = null,
    val runtimeMinutes: Int? = null,
    val runtimeMs: Long? = null,
    val genres: List<String> = emptyList(),
    val overview: String? = null,
    val parentTitle: String? = null,
    val album: String? = null,
    val seriesId: String? = null,
    val artistId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeNumberEnd: Int? = null,
    val progress: Float? = null,
    val remainingMinutes: Int? = null,
    val resumePositionMs: Long = 0,
    val unwatchedCount: Int? = null,
    val isFavorite: Boolean = false,
    val played: Boolean = false,
    val childCount: Int? = null,
    val uhd: Boolean? = null,
    val hdr: String? = null,
    val posterUrl: String? = null,
    val thumbUrl: String? = null,
    val showThumbUrl: String? = null,
    val backdropUrl: String? = null,
    val ageLocked: Boolean = false,
)

private object Codec {
    fun encode(page: SavedHomePage): Page = with(page.content) {
        Page(
            libraries = libraries.map { it.saved() },
            continueWatching = continueWatching.map { it.saved() },
            upNext = upNext.map { it.saved() },
            liveTv = page.liveTv,
        )
    }

    fun decode(page: Page): SavedHomePage = SavedHomePage(
        content = HomeContent(
            libraries = page.libraries.mapNotNull { it.library() },
            continueWatching = page.continueWatching.mapNotNull { it.item() },
            upNext = page.upNext.mapNotNull { it.item() },
            latest = emptyList(),
        ),
        liveTv = page.liveTv,
    )

    private fun Library.saved() = SavedLibrary(id.toString(), name, kind.name, itemCount, imageUrl)

    private fun SavedLibrary.library(): Library? = Library(
        id = uuid(id) ?: return null,
        name = name,
        kind = LibraryKind.entries.firstOrNull { it.name == kind } ?: return null,
        itemCount = count,
        imageUrl = image,
    )

    private fun MediaItem.saved() = SavedItem(
        id = id.toString(),
        kind = kind.name,
        title = title,
        sortName = sortName,
        year = year,
        endYear = endYear,
        ongoing = ongoing,
        communityRating = communityRating,
        officialRating = officialRating,
        runtimeMinutes = runtimeMinutes,
        runtimeMs = runtimeMs,
        genres = genres,
        overview = overview,
        parentTitle = parentTitle,
        album = album,
        seriesId = seriesId?.toString(),
        artistId = artistId?.toString(),
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeNumberEnd = episodeNumberEnd,
        progress = progress,
        remainingMinutes = remainingMinutes,
        resumePositionMs = resumePositionMs,
        unwatchedCount = unwatchedCount,
        isFavorite = isFavorite,
        played = played,
        childCount = childCount,
        uhd = quality?.uhd,
        hdr = quality?.hdr?.name,
        posterUrl = posterUrl,
        thumbUrl = thumbUrl,
        showThumbUrl = showThumbUrl,
        backdropUrl = backdropUrl,
        ageLocked = ageLocked,
    )

    private fun SavedItem.item(): MediaItem? = MediaItem(
        id = uuid(id) ?: return null,
        kind = ItemKind.entries.firstOrNull { it.name == kind } ?: ItemKind.Other,
        title = title,
        sortName = sortName,
        year = year,
        endYear = endYear,
        ongoing = ongoing,
        communityRating = communityRating,
        officialRating = officialRating,
        runtimeMinutes = runtimeMinutes,
        runtimeMs = runtimeMs,
        genres = genres,
        overview = overview,
        parentTitle = parentTitle,
        album = album,
        seriesId = seriesId?.let(::uuid),
        artistId = artistId?.let(::uuid),
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeNumberEnd = episodeNumberEnd,
        progress = progress,
        remainingMinutes = remainingMinutes,
        resumePositionMs = resumePositionMs,
        unwatchedCount = unwatchedCount,
        isFavorite = isFavorite,
        played = played,
        childCount = childCount,
        quality = uhd?.let { VideoQuality(it, HdrFormat.entries.firstOrNull { format -> format.name == hdr }) },
        posterUrl = posterUrl,
        thumbUrl = thumbUrl,
        showThumbUrl = showThumbUrl,
        backdropUrl = backdropUrl,
        ageLocked = ageLocked,
    )

    private fun uuid(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()
}
