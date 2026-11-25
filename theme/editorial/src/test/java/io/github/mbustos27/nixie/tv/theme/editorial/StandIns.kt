// SPDX-FileCopyrightText: Glacier contributors
// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.editorial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.ActionButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierMark
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.mbustos27.nixie.tv.designsystem.NavEntryLook
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme

/*
 * Stand-ins for Glacier's screens around each hook, copied from the app's layout (sizes, paddings, styles), so the
 * theme's slots can be drawn in this module's JVM tests: the app module, where the real screens live, cannot be a
 * dependency of a library. Each mirrors one file: SetupLayout and QuickConnectScreen, TopNav's bar and NavItem,
 * Spotlight and MediaRow. When Roborazzi is on main, the app's own screenshot tests replace them.
 */

/** Glacier's frame: the theme, the 1920 grid and the page ground. */
@Composable
fun Frame(content: @Composable () -> Unit) {
    GlacierTheme { GlacierBackground { content() } }
}

/** SetupLayout + QuickConnectScreen, with a code from the server. */
@Composable
fun QuickConnectStandIn(code: String?) {
    Box(Modifier.fillMaxSize()) {
        NixieTheme.slots.setupBackdrop?.invoke(this)
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 130.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(110.dp),
        ) {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) { QuickConnectIntro() }
            Column(Modifier.weight(1f).widthIn(max = 880.dp).padding(horizontal = 24.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(30.dp)) {
                    Text(
                        "Your code for Living Room Jellyfin".uppercase(),
                        style = GlacierText.body(16).copy(letterSpacing = 0.08.em),
                        color = GlacierColors.Mist,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    NixieTheme.slots.quickConnectCode?.invoke(code, 6) ?: Text("no code slot")
                    Validity(0.62f)
                    val newCode = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focusAfterAFrame(newCode) }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ActionButton(
                            onClick = {},
                            label = "New code",
                            primary = true,
                            modifier = Modifier.focusRequester(newCode),
                        )
                        ActionButton(onClick = {}, label = "Sign in with password")
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.QuickConnectIntro() {
    GlacierMark(size = 62)
    Text("Quick Connect", style = GlacierText.display(60).copy(lineHeight = 63.sp), color = GlacierColors.Ice)
    Text(
        "Sign in without a password: approve the code on a device where you are already signed in.",
        style = GlacierText.body(23).copy(lineHeight = 34.sp),
        color = GlacierColors.Mist,
        modifier = Modifier.widthIn(max = 490.dp),
    )
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        listOf(
            "Open Jellyfin on your phone or computer",
            "Profile → Quick Connect",
            "Enter the code and authorize it",
        ).forEachIndexed { i, step -> StepLine(i + 1, step) }
    }
}

@Composable
private fun StepLine(number: Int, text: String) {
    val accent = LocalAccent.current.main
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f))
                .border(1.dp, accent.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = GlacierText.body(18, FontWeight.SemiBold), color = GlacierColors.Ice)
        }
        Text(
            text,
            style = GlacierText.body(21).copy(lineHeight = 31.sp),
            color = GlacierColors.Mist,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Validity(fraction: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(PillShape).background(GlacierColors.GlassFill2)) {
            Box(
                Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(PillShape).background(LocalAccent.current.main),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(LocalAccent.current.main))
            Text(
                "Waiting for approval …",
                style = GlacierText.body(18),
                color = GlacierColors.Mist,
                modifier = Modifier.weight(1f),
            )
            Text("Valid 3:06", style = GlacierText.mono(18), color = GlacierColors.Mist)
        }
    }
}

/** One top-bar entry as TopNav's NavItem draws it, with the theme's look for [focused] and [active]. */
sealed interface Entry {
    data class Label(val text: String) : Entry
    data object Search : Entry
    data object Settings : Entry
    data object Profile : Entry
}

/** TopNav's bar: the chrome slot around search, the middle entries, settings and the avatar. */
@Composable
fun TopBarStandIn(
    active: String,
    focused: String,
    modifier: Modifier = Modifier,
) {
    val entries = listOf(
        "search" to Entry.Search,
        "Home" to Entry.Label("Home"),
        "Movies" to Entry.Label("Movies"),
        "Shows" to Entry.Label("Shows"),
        "Music" to Entry.Label("Music"),
        "Favorites" to Entry.Label("Favorites"),
        "settings" to Entry.Settings,
        "profile" to Entry.Profile,
    )
    Row(
        modifier
            .then(NixieTheme.slots.topBarChrome?.let { Modifier.it() } ?: Modifier)
            .padding(horizontal = 7.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entries.forEach { (key, entry) -> NavEntry(entry, focused = key == focused, active = key == active) }
    }
}

@Composable
private fun NavEntry(
    entry: Entry,
    focused: Boolean,
    active: Boolean,
) {
    val accent = LocalAccent.current
    val look = NixieTheme.slots.topBarEntry?.invoke(focused, active)
        ?: NavEntryLook(Color.Transparent, GlacierColors.Mist, Color.Transparent)
    val iconOnly = entry !is Entry.Label
    // focusScale: 1.07 when focused.
    val scale = if (focused) 1.07f else 1f
    Box(
        Modifier
            .height((54 * scale).dp)
            .clip(PillShape)
            .background(look.background)
            .border(2.dp, look.border, PillShape)
            .drawBehind {
                if (look.underline.isSpecified) {
                    val h = 3.dp.toPx()
                    val inset = 14.dp.toPx()
                    drawRect(
                        look.underline,
                        Offset(inset, size.height - h - 5.dp.toPx()),
                        Size(size.width - 2 * inset, h),
                    )
                }
            }
            .padding(horizontal = if (iconOnly) 15.dp else 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (entry) {
            is Entry.Label -> Text(entry.text, style = GlacierText.body(22, FontWeight.Medium), color = look.foreground)

            Entry.Search -> Icon(GlacierIcons.Search, "Search", tint = look.foreground, modifier = Modifier.size(22.dp))

            Entry.Settings -> Icon(
                GlacierIcons.Settings,
                "Settings",
                tint = look.foreground,
                modifier = Modifier.size(22.dp),
            )

            Entry.Profile -> Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(accent.main, accent.deep))),
                contentAlignment = Alignment.Center,
            ) {
                Text("M", style = GlacierText.display(16), color = GlacierColors.Void)
            }
        }
    }
}

/** Kinds of test picture: a sunset poster, or pure white as the worst case for light text. */
enum class Art { Sunset, White, Lighthouse, Planet }

/** A picture drawn in place of a server image. */
@Composable
fun TestArt(art: Art, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        when (art) {
            Art.White -> drawRect(Color.White)

            Art.Sunset -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF3B1A4A), Color(0xFFB4506A), Color(0xFFF2A07A))))
                drawCircle(Color(0xFFFFD58A), size.height * 0.26f, Offset(size.width * 0.62f, size.height * 0.66f))
                drawRect(Color(0xFF2A0F2A), Offset(0f, size.height * 0.74f), Size(size.width, size.height * 0.26f))
                repeat(4) { i ->
                    drawRect(
                        Color(0xFFF2A07A).copy(alpha = 0.5f),
                        Offset(size.width * 0.5f, size.height * (0.6f + i * 0.035f)),
                        Size(size.width * 0.24f, size.height * 0.008f),
                    )
                }
            }

            Art.Lighthouse -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF0E3A46), Color(0xFF2C7A7A))))
                drawCircle(Color(0xFFF4E3B0), size.minDimension * 0.12f, Offset(size.width * 0.5f, size.height * 0.42f))
                drawRect(Color(0xFF0A2228), Offset(0f, size.height * 0.7f), Size(size.width, size.height * 0.3f))
            }

            Art.Planet -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF1C1238), Color(0xFF5E3A6E))))
                drawCircle(Color(0xFFE8A060), size.minDimension * 0.3f, Offset(size.width * 0.5f, size.height * 0.44f))
                drawRect(Color(0xFF140A22), Offset(0f, size.height * 0.78f), Size(size.width, size.height * 0.22f))
            }
        }
    }
}

const val SPOTLIGHT_HEIGHT = 680

/** Spotlight: the backdrop slot, Glacier's bottom fade, the info at the bottom left and the position marks. */
@Composable
fun HeroStandIn(
    art: Art,
    withInfo: Boolean = true,
    focusPlay: Boolean = true,
) {
    Box(Modifier.fillMaxWidth().height(SPOTLIGHT_HEIGHT.dp).clipToBounds()) {
        val backdrop = NixieTheme.slots.heroBackdrop
        if (backdrop != null) {
            backdrop("test://backdrop") { _, artModifier -> TestArt(art, artModifier) }
        } else {
            TestArt(art, Modifier.fillMaxSize())
        }
        val fade = NixieTheme.slots.heroFade
        if (fade != null) {
            Canvas(Modifier.matchParentSize()) { fade() }
        } else {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, GlacierColors.Void))),
            )
        }
        if (withInfo) {
            HeroInfo(
                focusPlay,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 80.dp, bottom = 74.dp)
                    .width(900.dp),
            )
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 80.dp, bottom = 78.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                listOf(true, false, false, false).forEach { Dot(it) }
            }
        }
    }
}

@Composable
private fun HeroInfo(focusPlay: Boolean, modifier: Modifier) {
    val accent = LocalAccent.current.main
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        NixieTheme.slots.heroKicker?.invoke("Continue watching") ?: Row(
            Modifier
                .height(38.dp)
                .clip(PillShape)
                .background(accent.copy(alpha = 0.18f))
                .border(1.dp, accent.copy(alpha = 0.4f), PillShape)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(GlacierIcons.Play, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
            Text(
                "CONTINUE WATCHING",
                style = GlacierText.body(15, FontWeight.Bold).copy(letterSpacing = 0.09.em),
                color = accent,
            )
        }
        Text(
            "Burning Horizons",
            style = GlacierText.display(76)
                .copy(lineHeight = 78.sp, shadow = Shadow(Color(0x99000000), blurRadius = 30f)),
            color = GlacierColors.Ice,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text("1997  ·  1 h 58 min  ·  Thriller", style = GlacierText.body(20), color = GlacierColors.Mist)
        Text(
            "A courier with one day left on her licence takes the long road across the desert, and the road " +
                "takes something back.",
            style = GlacierText.body(21).copy(lineHeight = 34.sp),
            color = GlacierColors.Ice,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(820.dp),
        )
        Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(PillShape).background(GlacierColors.GlassFill2)) {
                Box(Modifier.fillMaxWidth(0.38f).fillMaxHeight().clip(PillShape).background(accent))
            }
            Text("72 min left", style = GlacierText.body(17), color = GlacierColors.Mist)
        }
        val play = remember { FocusRequester() }
        if (focusPlay) LaunchedEffect(Unit) { focusAfterAFrame(play) }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton(
                onClick = {},
                label = "Resume",
                icon = GlacierIcons.Play,
                primary = true,
                modifier = Modifier.focusRequester(play),
            )
            ActionButton(onClick = {}, label = "More info")
            ActionButton(onClick = {}, icon = GlacierIcons.Heart, contentDescription = "Favourite")
        }
    }
}

@Composable
private fun Dot(active: Boolean) {
    val accent = LocalAccent.current.main
    Box(
        Modifier
            .height(26.dp)
            .width(if (active) 34.dp else 12.dp)
            .drawBehind {
                val bar = Size(size.width, 12.dp.toPx())
                val top = Offset(0f, (size.height - bar.height) / 2)
                val radius = CornerRadius(if (NixieTheme.tokens.pill != null) 2.dp.toPx() else bar.height / 2)
                drawRoundRect(if (active) accent else Color(0x4DE8F4F7), top, bar, radius)
            },
    )
}

/** MediaRow: the header slot over a row of cards (drawn simply: picture, title, a line of facts). */
@Composable
fun RowStandIn(
    title: String,
    subtitle: String?,
    cards: List<Pair<String, Art>>,
    poster: Boolean,
    index: Int? = null,
) {
    // MediaRow's 12 above; below, the cards' room for focus (26 - 8) and the row's bottom (46 - 26).
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 38.dp)) {
        val headerAt = NixieTheme.slots.rowHeaderAt.takeIf { index != null }
        val header = NixieTheme.slots.rowHeader
        val edge = Modifier.padding(start = 80.dp, end = 80.dp)
        if (headerAt != null && index != null) {
            headerAt(index, title, subtitle, edge)
        } else if (header != null) {
            header(title, subtitle, edge)
        } else {
            Text(title, style = GlacierText.display(28), color = GlacierColors.Ice, modifier = edge)
        }
        Row(
            Modifier.padding(start = 80.dp, top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            cards.forEach { (name, art) ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
                    val size = if (poster) Modifier.size(216.dp, 324.dp) else Modifier.size(316.dp, 178.dp)
                    Box(size.clip(shape).border(1.dp, GlacierColors.GlassBorder, shape)) {
                        TestArt(art, Modifier.fillMaxSize())
                        if (!poster) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth(0.45f)
                                    .height(5.dp)
                                    .background(LocalAccent.current.main),
                            )
                        }
                    }
                    Text(name, style = GlacierText.body(19, FontWeight.Medium), color = GlacierColors.Ice)
                }
            }
        }
    }
}

/** Home: the hero, two rows, and the top bar floating over the hero. */
@Composable
fun HomeStandIn(art: Art) {
    Box(Modifier.fillMaxSize().background(GlacierColors.Void)) {
        // As Home's list: the rows run on below the screen's edge at their full size.
        Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true)) {
            HeroStandIn(art)
            RowStandIn(
                "Continue watching",
                "9 titles",
                listOf(
                    "Iron Lighthouses" to Art.Lighthouse,
                    "The Iron Circus" to Art.Planet,
                    "Copper Rivers" to Art.Sunset,
                    "A Winter Apart" to Art.Lighthouse,
                    "Under the Neon" to Art.Planet,
                ),
                poster = false,
                index = 0,
            )
            RowStandIn(
                "Recently added in Movies",
                null,
                listOf(
                    "Glass Canyon" to Art.Lighthouse,
                    "Orbit of Ellery" to Art.Planet,
                    "Starlight Drive" to Art.Sunset,
                    "Neon Pines" to Art.Planet,
                    "Cinder Coast" to Art.Sunset,
                    "Avalon Heights" to Art.Lighthouse,
                    "Ash and Ember" to Art.Sunset,
                ),
                poster = true,
                index = 1,
            )
        }
        TopBarStandIn(
            active = "Home",
            focused = "",
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp),
        )
    }
}
