// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.ui.FilterChip
import io.github.glacier_jellyfin.androidtv.ui.LocalCardSizes
import io.github.mbustos27.nixie.tv.data.play.PlayLabels
import io.github.mbustos27.nixie.tv.data.search.SearchKind

@StringRes
fun SearchKind.labelRes(): Int = when (this) {
    SearchKind.Shows -> R.string.nixie_search_shows
    SearchKind.Movies -> R.string.nixie_search_movies
    SearchKind.Episodes -> R.string.nixie_search_episodes
    SearchKind.Collections -> R.string.nixie_search_collections
    SearchKind.Albums -> R.string.nixie_search_albums
    SearchKind.People -> R.string.nixie_search_people
}

/** "All", then one chip per kind found; nothing when only one kind was found. */
@Composable
fun SearchChips(
    chips: List<SearchKind>,
    active: SearchKind?,
    onPick: (SearchKind?) -> Unit,
) {
    if (chips.size < 2) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterChip(
            stringResource(R.string.nixie_search_all),
            active = active == null,
            onClick = { onPick(null) },
            height = 48,
        )
        chips.forEach { kind ->
            FilterChip(
                stringResource(kind.labelRes()),
                active = active == kind,
                onClick = { onPick(kind) },
                height = 48,
            )
        }
    }
}

/** The weaker matches' card, at the end of the grid: "Show 4 more". */
@Composable
fun ShowMoreCard(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sizes = LocalCardSizes.current
    val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
    GlacierClickable(
        onClick = onClick,
        shape = shape,
        modifier = modifier,
        unfocusedBorder = GlacierColors.GlassBorder2,
        contentAlignment = Alignment.Center,
    ) { focused ->
        Box(
            Modifier
                .size(sizes.posterWidth.dp, sizes.posterHeight.dp)
                .clip(shape)
                .background(if (focused) LocalAccent.current.main else GlacierColors.GlassFill)
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.nixie_search_show_more, count),
                style = GlacierText.body(22, FontWeight.SemiBold),
                color = if (focused) GlacierColors.Void else GlacierColors.Ice,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A search card's caption when it is not the year (null then): "Person" for a person, and for an episode its show and
 * place in it ("Route 66 · S1 E8"), so episodes with the same name tell themselves apart (bug hunt M4).
 */
@Composable
fun searchCaption(item: MediaItem): String? = when (item.kind) {
    ItemKind.Person -> stringResource(R.string.nixie_search_person)

    ItemKind.Episode -> listOfNotNull(
        item.parentTitle?.takeIf { it.isNotBlank() },
        PlayLabels.code(item)?.let { episodeCodeText(it) },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    else -> null
}
