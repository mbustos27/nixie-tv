// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Nixie's looks. Nixie for TV is Glacier's app with Nixie's themes laid over it: a theme is a set of tokens (colours,
 * shapes, focus) that Glacier's design system reads, and a set of layout slots that Glacier's screens call through
 * small hooks marked `// Nixie:`. Anything a theme leaves null stays Glacier's own, so upstream screens keep working
 * and keep merging.
 *
 * Drive-in is the default. Cinematic and Editorial join in phase 3 as two more [NixieThemeSpec]s on the same tokens
 * and slots; [Glacier] is upstream's own look, kept for comparison and as the fallback.
 */
enum class NixieThemeId { DriveIn, Cinematic, Editorial, Glacier }

/** One theme: its tokens and its slots. */
@Immutable
class NixieThemeSpec(
    val id: NixieThemeId,
    val tokens: NixieTokens,
    val slots: NixieSlots,
)

/** Upstream's own look: no token and no slot replaced. */
val GlacierLook = NixieThemeSpec(NixieThemeId.Glacier, NixieTokens(), NixieSlots())

/**
 * The theme in use. [current] is snapshot state, so every composable and drawing that read a token while it was
 * shown is redrawn when it changes: switching themes at run time needs no restart (Settings gets the picker in
 * phase 3). The theme modules register themselves from the app; nothing here knows them.
 */
object NixieTheme {
    private val themes = mutableMapOf(NixieThemeId.Glacier to GlacierLook)

    var current: NixieThemeSpec by mutableStateOf(GlacierLook)
        private set

    val tokens: NixieTokens get() = current.tokens
    val slots: NixieSlots get() = current.slots

    /** Makes [spec] available under its id. */
    fun register(spec: NixieThemeSpec) {
        themes[spec.id] = spec
    }

    /** Wears [id]; a theme that was never registered falls back to Glacier's look. */
    fun use(id: NixieThemeId) {
        current = themes[id] ?: GlacierLook
    }

    /** The registered themes, in the picker's order. */
    val available: List<NixieThemeId> get() = NixieThemeId.entries.filter { it in themes }
}

/**
 * The brand, apart from the theme: Nixie's mark and loading tube replace Glacier's diamond in every theme. A flag
 * rather than a deleted body, so Glacier's own drawing stays in place for upstream merges.
 */
object NixieBrand {
    // Not const: Glacier's drawing behind the flag would read as unreachable code.
    @Suppress("MayBeConstant")
    val ownMark: Boolean = true
}

/** Nixie's behaviour changes to Glacier's screens that are not about the look. */
object NixieFeatures {
    /** Sign-in opens Quick Connect first when the server offers it; the password form is the fallback. */
    @Suppress("MayBeConstant") // a switch kept as a value, like NixieBrand.ownMark
    val quickConnectFirst: Boolean = true
}
