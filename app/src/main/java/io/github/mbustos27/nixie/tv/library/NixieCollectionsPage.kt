// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.library

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryScope
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusScale
import io.github.glacier_jellyfin.androidtv.library.LibraryViewModel
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget

/**
 * Nixie (tv-home-two-rows, TV-S11; the owner: "an easier way to access collections"): the top bar's Collections page,
 * every collection on the server (box sets) A to Z, in Glacier's library grid ([LibraryQuery.nixieCollections]). Its
 * rules, which Glacier's LibraryViewModel and LibraryScreen call through hooks marked "// Nixie".
 */
object NixieCollectionsPage {
    /**
     * The page's first request: three rows of seven posters (or eight, compact), what its first screen shows; the next
     * pages follow at once. On the throwaway real Jellyfin (492 collections): 190-250 ms for 21, 280-430 ms for 70.
     */
    const val FIRST_PAGE = 24

    /** The test tag of the page's order button ([NixieOrderButton]). */
    const val ORDER_TAG = "nixie-collections-order"

    /** The page's query, or null for any other library page. */
    fun query(collections: Boolean): LibraryQuery? = if (collections) LibraryQuery.nixieCollections() else null

    /** The page's tabs: Collections only. Null for any other library page. */
    fun scopes(query: LibraryQuery): List<LibraryScope>? =
        listOf(LibraryScope.Collections).takeIf { query.nixieAllCollections }

    /** The top bar's entry for the page opened from it. */
    fun navTarget(collections: Boolean, kind: LibraryKind): NavTarget =
        if (collections) NavTarget.Collections else NavTarget.Library(kind)

    /**
     * tv-1.0-r1 (the owner, 2026-11-23: "we should be able to reverse the direction of the sort in collections"): the
     * words on the order button for the list's direction, whatever the sort menu chose: "A to Z" or "Z to A" by name,
     * "Newest first" or "Oldest first" by date added or year, and so on.
     */
    @StringRes
    fun orderLabel(sort: LibrarySort, descending: Boolean): Int = when (sort) {
        LibrarySort.Title, LibrarySort.Artist, LibrarySort.Album ->
            if (descending) R.string.nixie_order_z_to_a else R.string.nixie_order_a_to_z

        LibrarySort.DateAdded, LibrarySort.Year ->
            if (descending) R.string.nixie_order_newest else R.string.nixie_order_oldest

        LibrarySort.Rating -> if (descending) R.string.nixie_order_highest else R.string.nixie_order_lowest

        LibrarySort.Runtime -> if (descending) R.string.nixie_order_longest else R.string.nixie_order_shortest
    }
}

/**
 * tv-1.0-r1: the Collections page's order button in Glacier's toolbar, before the sort button and drawn like it: the
 * list's direction in words with its arrow (up going up, as the sort button's), flipped by one press of OK ([onFlip]:
 * the sort menu's own flip, so it is kept with the page's sort). Shown on the Collections page only.
 */
@Composable
fun NixieOrderButton(
    query: LibraryQuery,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!query.nixieAllCollections) return
    val descending = query.orderDescending
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val foreground = if (focused) GlacierColors.Void else GlacierColors.Ice
    Row(
        modifier
            .testTag(NixieCollectionsPage.ORDER_TAG)
            .focusScale(focused)
            .height(52.dp)
            .clip(PillShape)
            .background(if (focused) accent else GlacierColors.GlassFill2)
            .border(2.dp, if (focused) accent else GlacierColors.GlassBorder2, PillShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onFlip)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            GlacierIcons.ArrowDown,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(19.dp).rotate(if (descending) 0f else 180f),
        )
        Text(
            stringResource(NixieCollectionsPage.orderLabel(query.sort, descending)),
            style = GlacierText.body(19, FontWeight.SemiBold),
            color = foreground,
        )
    }
}

/** How many titles this query's first request asks for: [NixieCollectionsPage.FIRST_PAGE] on the page, else a page. */
val LibraryQuery.nixieFirstPage: Int
    get() = if (nixieAllCollections) NixieCollectionsPage.FIRST_PAGE else LibraryViewModel.PAGE_SIZE

/** Where a library page keeps its toggles: the Collections page its own key, never the movies'; else its kind's. */
val LibraryRoute.nixieFilterKey: String
    get() = if (collections) LibraryQuery.NIXIE_COLLECTIONS_KEY else kind
