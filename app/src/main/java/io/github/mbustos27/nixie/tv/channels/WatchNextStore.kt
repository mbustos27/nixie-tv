// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.channels

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.BaseColumns
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import androidx.tvprovider.media.tv.WatchNextProgram
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeScreenContent
import io.github.mbustos27.nixie.tv.data.watchnext.WatchNextOwner

/**
 * Nixie (tv-watchnext): the internal id of a Watch Next row names the profile it belongs to and
 * the title, so a row can never pass from one profile to another. Rows Glacier wrote (the bare
 * item id) count as nobody's and go at the next write.
 */
internal object WatchNextIds {
    private const val PREFIX = "nixie:"
    private const val SEPARATOR = '|'

    fun of(owner: String, itemId: String): String = "$PREFIX$owner$SEPARATOR$itemId"

    fun owner(content: HomeScreenContent): String = WatchNextOwner.key(content.serverId, content.userId)

    fun ownerOf(internalId: String?): String? {
        if (internalId == null || !internalId.startsWith(PREFIX)) return null
        return internalId.substringAfter(PREFIX).substringBefore(SEPARATOR, "").ifEmpty { null }
    }
}

/**
 * Nixie (tv-watchnext): row ids by internal id, as the reconcile at app start and this process's writes
 * left them (only this app writes its rows, so a title missing here has no row), so the playing title's
 * row is written by its id without reading the table. A stale id is harmless: the row is read by that
 * id first, and one naming another title writes nothing.
 */
internal object WatchNextRowIds {
    private val ids = HashMap<String, Long>()

    @Synchronized
    operator fun get(internalId: String): Long? = ids[internalId]

    @Synchronized
    operator fun set(internalId: String, id: Long) {
        ids[internalId] = id
    }

    @Synchronized
    fun remove(internalId: String) {
        ids.remove(internalId)
    }

    @Synchronized
    fun replace(all: Map<String, Long>) {
        ids.clear()
        ids.putAll(all)
    }
}

/**
 * Nixie (tv-watchnext): this app's rows in Android's Watch Next table. The system's provider
 * hands an app its own rows only and writes only those, so nothing here can touch another app's.
 * Nothing leaves the TV: Watch Next is a local system table that the home screen reads.
 */
// The program builders' setters and getters sit on a library-restricted base class (as in HomeChannels).
@SuppressLint("RestrictedApi")
internal class WatchNextStore(private val resolver: ContentResolver) {

    /** This app's rows, oldest first. */
    fun rows(): List<WatchNextProgram> =
        resolver.query(WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(WatchNextProgram.fromCursor(cursor)) }
        }.orEmpty().sortedBy { it.id }

    /**
     * The table holds [programs] of [owner] and nothing else: a title already there is updated in
     * place, a second row of one title goes, and every other row goes; every row when [owner] is null.
     * Anyone else's rows (their ids name another profile, [WatchNextIds]) go first, before anything is
     * written, so a write cut short (a crash, a provider error) never leaves them beside the owner's.
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition") // One pass per table, each case spelled out.
    fun replaceAll(owner: String?, programs: List<WatchNextProgram>) {
        val kept = LinkedHashMap<String, WatchNextProgram>()
        for (row in rows()) {
            val id = row.internalProviderId
            if (owner == null || id == null || WatchNextIds.ownerOf(id) != owner || id in kept) {
                delete(row.id)
            } else {
                kept[id] = row
            }
        }
        val written = HashMap<String, Long>()
        if (owner == null) {
            WatchNextRowIds.replace(written)
            return
        }
        for (program in programs.distinctBy { it.internalProviderId }) {
            if (WatchNextIds.ownerOf(program.internalProviderId) != owner) continue
            val old = kept.remove(program.internalProviderId)
            // Removed by the user on the home screen (Glacier's rule): back once it was watched further. Only the
            // system may set a row browsable again, so the row is written anew, which the home screen shows.
            val shownAgain =
                old != null && !old.isBrowsable && old.lastPlaybackPositionMillis != program.lastPlaybackPositionMillis
            if (old != null && shownAgain) delete(old.id)
            if (old == null || shownAgain) {
                val uri = resolver.insert(WatchNextPrograms.CONTENT_URI, program.values())
                uri?.let { written[program.internalProviderId] = ContentUris.parseId(it) }
            } else {
                written[program.internalProviderId] = old.id
                resolver.update(TvContractCompat.buildWatchNextProgramUri(old.id), program.values(), null, null)
            }
        }
        kept.values.forEach { delete(it.id) }
        WatchNextRowIds.replace(written)
    }

    /** Deletes the rows of [itemId] of [owner]; the first as it was, for [putBack]. */
    fun remove(owner: String, itemId: String): WatchNextProgram? {
        val id = WatchNextIds.of(owner, itemId)
        val rows = rows().filter { it.internalProviderId == id }
        rows.forEach { delete(it.id) }
        WatchNextRowIds.remove(id)
        return rows.firstOrNull()
    }

    /** A row [remove] took, back, unless its title has a row again by now. */
    fun putBack(program: WatchNextProgram): Boolean {
        if (rows().any { it.internalProviderId == program.internalProviderId }) return false
        val uri = resolver.insert(WatchNextPrograms.CONTENT_URI, program.values()) ?: return false
        WatchNextRowIds[program.internalProviderId] = ContentUris.parseId(uri)
        return true
    }

    /**
     * The row of [itemId] of [owner] takes the position the server just took: it is under way,
     * engaged with at [now]. Written by the row's id from [WatchNextRowIds], after reading that one
     * row by its id; never by reading the table, and never with a selection (the system's provider
     * refuses them). False when the title has no row.
     */
    fun progress(
        owner: String,
        itemId: String,
        positionMs: Long,
        now: Long,
    ): Boolean {
        val internalId = WatchNextIds.of(owner, itemId)
        val id = WatchNextRowIds[internalId] ?: return false
        if (internalIdOf(id) != internalId) {
            WatchNextRowIds.remove(internalId)
            return false
        }
        val values = ContentValues().apply {
            put(
                WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                positionMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            )
            put(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, now)
            put(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
        }
        return resolver.update(TvContractCompat.buildWatchNextProgramUri(id), values, null, null) > 0
    }

    /** The internal id of the row with [id], read from that row alone. */
    private fun internalIdOf(id: Long): String? = resolver.query(
        TvContractCompat.buildWatchNextProgramUri(id),
        arrayOf(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID),
        null,
        null,
        null,
    )?.use { if (it.moveToFirst()) it.getString(0) else null }

    /**
     * Once at app start, with the owner known: rows of another profile go, rows a crash between a switch and its
     * emptying left behind; the owner's are remembered by id. Rows of nobody (Glacier's, from before an update) stay
     * until the next write replaces them (tv-home-start: no empty row on the launcher meanwhile). How many went.
     */
    fun reconcile(owner: String?): Int {
        val others = rows().filter { row ->
            val of = WatchNextIds.ownerOf(row.internalProviderId)
            of != null && of != owner
        }
        others.forEach { delete(it.id) }
        val mine = rows().filter { owner != null && WatchNextIds.ownerOf(it.internalProviderId) == owner }
        WatchNextRowIds.replace(mine.associate { it.internalProviderId to it.id })
        return others.size
    }

    /** tv-home-start: the rows of [owner], a profile known to be gone, go. How many went. */
    fun removeOwner(owner: String): Int {
        val rows = rows().filter { WatchNextIds.ownerOf(it.internalProviderId) == owner }
        rows.forEach { delete(it.id) }
        return rows.size
    }

    /** Every row of this app; how many went. */
    fun clear(): Int = rows().onEach { delete(it.id) }.size.also { WatchNextRowIds.replace(emptyMap()) }

    private fun delete(id: Long) {
        resolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
    }

    /** What an app may write: never the row id or the owning package, which a row read back carries. */
    private fun WatchNextProgram.values(): ContentValues = toContentValues().apply {
        remove(BaseColumns._ID)
        remove(TvContractCompat.BaseTvColumns.COLUMN_PACKAGE_NAME)
    }
}
