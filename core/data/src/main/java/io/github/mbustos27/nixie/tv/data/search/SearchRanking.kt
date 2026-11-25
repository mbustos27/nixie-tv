// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data.search

import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem

/** The kinds search shows, each with its chip, in the chips' order (ties between kinds go this way too). */
enum class SearchKind(val itemKind: ItemKind) {
    Shows(ItemKind.Series),
    Movies(ItemKind.Movie),
    Episodes(ItemKind.Episode),
    Collections(ItemKind.Collection),
    Albums(ItemKind.Album),

    /** A person opens their titles. */
    People(ItemKind.Person),
    ;

    companion object {
        fun of(kind: ItemKind): SearchKind? = entries.firstOrNull { it.itemKind == kind }
    }
}

/**
 * An item with its match and its place in the server's answer (the last tie-break). [folded]: an episode that waits
 * behind "Show N more" because its show leads the results or another episode of the show already shows.
 */
data class RankedItem(
    val item: MediaItem,
    val kind: SearchKind,
    val match: SearchMatch,
    val order: Int,
    val folded: Boolean = false,
) {
    /** Shown at once: a strong match not folded behind its show. */
    val shown: Boolean get() = match.strong && !folded

    /**
     * Among the first results: a show, film, collection, album or person whose whole title or its start matches, or
     * an episode whose whole title does. An episode found by the start of its title comes after them (bug hunt M4:
     * "Bri" filled the first rows with episodes called "Bridge").
     */
    val leads: Boolean
        get() = if (kind == SearchKind.Episodes) match.tier == SearchTier.Whole else match.tier <= SearchTier.Start
}

/**
 * Search results ranked on the box, the same on every server (desktop Nixie 1.5): best match first, weak matches folded
 * behind "Show N more", one chip per kind found. When nothing matches strongly, everything shows.
 */
class RankedResults(val query: String, val items: List<RankedItem>) {

    /** The chips after "All": the kinds found, in [SearchKind] order. */
    val chips: List<SearchKind> get() = SearchKind.entries.filter { kind -> items.any { it.kind == kind } }

    private fun of(chip: SearchKind?) = if (chip == null) items else items.filter { it.kind == chip }

    /**
     * What the grid shows for [chip] (null: all): the strong matches but the folded episodes; once [expanded], the rest
     * after them, so what showed keeps its place.
     */
    fun visible(chip: SearchKind?, expanded: Boolean): List<MediaItem> {
        val all = of(chip)
        val shown = all.filter { it.shown }
        if (shown.isEmpty()) return all.map { it.item }
        if (!expanded) return shown.map { it.item }
        return (shown + all.filterNot { it.shown }).map { it.item }
    }

    /** How many wait behind "Show N more" for [chip]; 0 once [expanded] or when nothing is shown at once. */
    fun hidden(chip: SearchKind?, expanded: Boolean): Int {
        val all = of(chip)
        val shown = all.count { it.shown }
        if (expanded || shown == 0) return 0
        return all.size - shown
    }

    /** With titles found another way (Seerr's library hits) added as weak matches, unless already there. */
    fun withExtra(extra: List<MediaItem>): RankedResults {
        val known = items.mapTo(HashSet()) { it.item.id }
        val added = extra.filter { known.add(it.id) }.mapIndexedNotNull { i, item ->
            SearchKind.of(item.kind)?.let { RankedItem(item, it, SearchMatch.Nothing, items.size + i) }
        }
        return RankedResults(query, (items + added).sortedWith(SearchRanking.order))
    }

    companion object {
        val Empty = RankedResults("", emptyList())
    }
}

object SearchRanking {

    /** The leading results first ([RankedItem.leads]), then best match, the kind's order and the server's. */
    val order: Comparator<RankedItem> = compareBy<RankedItem> { !it.leads }
        .thenBy { it.match }
        .thenBy { it.kind.ordinal }
        .thenBy { it.order }

    /**
     * [found] ranked for [query], best first: by match, then kind, then the server's order. Items of no searched kind are
     * left out, repeats too. [weak]: ids found for another reason (a person in them, their genre), never strong.
     */
    fun rank(
        query: String,
        found: List<MediaItem>,
        weak: Set<java.util.UUID> = emptySet(),
    ): RankedResults {
        val q = SearchQuery(query)
        if (q.isEmpty) return RankedResults(query, emptyList())
        val seen = HashSet<java.util.UUID>()
        val ranked = found.mapIndexedNotNull { index, item ->
            val kind = SearchKind.of(item.kind) ?: return@mapIndexedNotNull null
            if (!seen.add(item.id)) return@mapIndexedNotNull null
            val match = if (item.id in weak) SearchMatch.Nothing else q.match(item.title, item.sortName)
            RankedItem(item, kind, match, index)
        }
        return RankedResults(query, foldEpisodes(ranked.sortedWith(order)))
    }

    /**
     * Many episodes of one show collapse (bug hunt M4): all of them when the show itself is among the strong matches,
     * else all but the best one. They wait behind "Show N more".
     */
    private fun foldEpisodes(sorted: List<RankedItem>): List<RankedItem> {
        val shows = sorted.filter { it.kind == SearchKind.Shows && it.match.strong }
            .mapTo(HashSet<Any>()) { it.item.id }
        val seen = HashSet<Any>()
        return sorted.map { ranked ->
            val show: Any? = ranked.item.seriesId ?: ranked.item.parentTitle
            if (ranked.kind != SearchKind.Episodes || !ranked.match.strong || show == null) return@map ranked
            ranked.copy(folded = show in shows || !seen.add(show))
        }
    }
}
