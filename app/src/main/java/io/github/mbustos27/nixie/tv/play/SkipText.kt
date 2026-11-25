// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.play

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.glacier_jellyfin.androidtv.R
import io.github.mbustos27.nixie.tv.data.skips.SegmentSource

/** Where a skip came from, in the Skip button's description. */
@StringRes
fun SegmentSource.descriptionRes(): Int = when (this) {
    SegmentSource.Server -> R.string.nixie_skip_source_server
    SegmentSource.Chapters -> R.string.nixie_skip_source_chapters
    SegmentSource.Online -> R.string.nixie_skip_source_online
    SegmentSource.Learned -> R.string.nixie_skip_source_learned
}

/** The Skip button's description: its label, then where the skip came from ("Skip intro. From the file's chapters"). */
@Composable
fun Modifier.skipSource(@StringRes label: Int, source: SegmentSource): Modifier {
    val description = stringResource(
        R.string.nixie_skip_described,
        stringResource(label),
        stringResource(source.descriptionRes()),
    )
    return semantics { contentDescription = description }
}
