// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.designsystem

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * The screen regions a theme may lay out its own way. Glacier's screens call each through a hook marked
 * `// Nixie:`; a null slot keeps Glacier's own drawing. Slots take only plain values and callbacks, never a
 * repository or a ViewModel, so a theme can neither fetch data nor own durable state, and a theme switch keeps
 * every ViewModel, the back stack and the focus.
 *
 * The contract grows by adding slots with a null default; an existing slot's signature does not change without a
 * note to every theme's owner.
 */
@Immutable
class NixieSlots(
    /**
     * The ground behind pages without a picture (`GlacierBackground`: Glacier draws an accent glow over near black).
     * Drawn behind the page's content, every frame it is invalidated, so keep it to a few shapes.
     */
    val pageGround: (DrawScope.() -> Unit)? = null,

    /**
     * The top bar's container (`TopNav`): Glacier's is a floating glass pill. Receives the bar's row and returns it
     * with the theme's chrome (clip, glass, edge, any marquee bulbs), keeping the row's own focus behaviour.
     */
    val topBarChrome: (Modifier.() -> Modifier)? = null,

    /** One top-bar entry's colours and its "this page" mark (`TopNav`'s NavItem). */
    val topBarEntry: ((focused: Boolean, active: Boolean) -> NavEntryLook)? = null,

    /** A row's title over its cards (`MediaRow`: Home's rows and the detail pages' rows). */
    val rowHeader: (@Composable (title: String, subtitle: String?, modifier: Modifier) -> Unit)? = null,

    /**
     * A row's title with the row's position on its page, from 0 (Home's rows; the hero is not counted). Takes
     * precedence over [rowHeader] where a page knows the position; rows without one use [rowHeader]. Added
     * 2026-11-21 for Editorial's numbered sections ("01 ...").
     */
    val rowHeaderAt:
    (@Composable (index: Int, title: String, subtitle: String?, modifier: Modifier) -> Unit)? = null,

    /**
     * Home's hero backdrop (`Spotlight`), behind the title, facts and buttons, which stay at the bottom left over it.
     * [artwork] draws the current title's backdrop image, with Glacier's slow zoom, wherever the theme places it.
     */
    val heroBackdrop: (
        @Composable BoxScope.(
            imageUrl: String?,
            artwork: @Composable (imageUrl: String?, modifier: Modifier) -> Unit,
        ) -> Unit
    )? = null,

    /**
     * Home's hero: the fade at its foot into the page, drawn over the backdrop (Glacier: 260 dp from clear to the
     * page ground). A theme that keeps its text off the picture draws its own, or nothing with `{}`. Added
     * 2026-11-21 for Editorial's framed picture.
     */
    val heroFade: (DrawScope.() -> Unit)? = null,

    /**
     * Home's hero: the marks that say which of [count] titles shows ([index], from 0), in place of Glacier's dots at
     * the bottom right. [progress] is the time to the next title (0..1), null while the hero does not move on;
     * [thumbnailUrls] are the titles' stills, which [artwork] draws. Added 2026-11-21 for Cinematic's pager.
     */
    val heroPositionMarks: (
        @Composable BoxScope.(
            count: Int,
            index: Int,
            progress: () -> Float?,
            thumbnailUrls: List<String?>,
            artwork: @Composable (imageUrl: String?, modifier: Modifier) -> Unit,
        ) -> Unit
    )? = null,

    /**
     * Home's hero: the small line above the title while it resumes ([text]: Glacier's "Continue watching"), in place
     * of Glacier's chip. Added 2026-11-21 for Cinematic's tracked capitals.
     */
    val heroKicker: (@Composable (text: String) -> Unit)? = null,

    /**
     * A 16:9 still card (`ContinueCard`: Continue Watching and episode shelves) inside Glacier's focus frame and click
     * handling: the art ([artwork] draws [imageUrl]), [title], its second [line], [progress] (null when unstarted) and
     * whether it has focus. The theme draws everything the card shows; Glacier's lock and hold-OK marks stay over it.
     * Added 2026-11-21 for Cinematic's title over the art.
     */
    val stillCard: (
        @Composable (
            imageUrl: String?,
            title: String,
            line: String,
            progress: Float?,
            focused: Boolean,
            artwork: @Composable (imageUrl: String?, modifier: Modifier) -> Unit,
        ) -> Unit
    )? = null,

    /** Sign-in: Quick Connect's code, [length] characters; null [code] while the server has not sent one. */
    val quickConnectCode: (@Composable (code: String?, length: Int) -> Unit)? = null,

    /** The setup and sign-in screens' backdrop, behind their two columns (`SetupLayout`). */
    val setupBackdrop: (@Composable BoxScope.() -> Unit)? = null,

    /** Phase 2: the library pages. */
    val browse: BrowseSlots = BrowseSlots(),

    /** Phase 2: the item pages. */
    val item: ItemSlots = ItemSlots(),

    /** Phase 2: the player's chrome. */
    val player: PlayerSlots = PlayerSlots(),
)

/**
 * A top-bar entry: its fill, text and icon colour, border, and the underline that marks the page you are on. The
 * plan's rule: the bar's "this page" mark is an underline, never a pill. [underline] Unspecified draws none.
 */
@Immutable
data class NavEntryLook(
    val background: Color,
    val foreground: Color,
    val border: Color,
    val underline: Color = Color.Unspecified,
)
