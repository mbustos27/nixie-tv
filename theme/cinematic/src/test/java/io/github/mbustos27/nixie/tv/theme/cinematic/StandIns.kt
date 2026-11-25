// SPDX-FileCopyrightText: Glacier contributors
// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.cinematic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusFrame
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusScale
import io.github.mbustos27.nixie.tv.designsystem.NavEntryLook
import io.github.mbustos27.nixie.tv.designsystem.NixieTheme

/*
 * Stand-ins for Glacier's sign-in, top bar and Home, at their geometry (SetupLayout, TopNav, Spotlight, MediaRow),
 * calling the theme through NixieTheme.slots exactly where Glacier's `// Nixie:` hooks do. The real screens live in
 * :app, which a theme module cannot depend on; these show what the slots and tokens do on those screens.
 */

/** Quick Connect: SetupLayout's two columns over the setup backdrop, the code on the theme's own display. */
@Composable
fun SignInStandIn(code: String?) {
    val newCode = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(GlacierColors.Void)) {
        NixieTheme.slots.setupBackdrop?.invoke(this)
        Row(
            Modifier.fillMaxSize().padding(horizontal = 130.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(110.dp),
        ) {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                GlacierMark(size = 62)
                Text(
                    "Quick Connect",
                    style = GlacierText.display(60).copy(lineHeight = 63.sp),
                    color = GlacierColors.Ice,
                )
                Text(
                    "Sign in from a phone or a computer where you are already signed in.",
                    style = GlacierText.body(23).copy(lineHeight = 34.sp),
                    color = GlacierColors.Mist,
                    modifier = Modifier.widthIn(max = 490.dp),
                )
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    StepLine(1, "Open Jellyfin on another device.")
                    StepLine(2, "Go to Profile → Quick Connect.")
                    StepLine(3, "Enter the code shown here.")
                }
            }
            Column(
                Modifier.weight(1f).widthIn(max = 880.dp).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(30.dp),
            ) {
                Text(
                    "CODE FOR NIXIE QA",
                    style = GlacierText.body(16).copy(letterSpacing = 0.08.em),
                    color = GlacierColors.Mist,
                    modifier = Modifier.padding(start = 4.dp),
                )
                NixieTheme.slots.quickConnectCode?.invoke(code, 6)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(PillShape).background(GlacierColors.GlassFill2)) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.7f)
                                .fillMaxHeight()
                                .clip(PillShape)
                                .background(LocalAccent.current.main),
                        )
                    }
                    Text("Waiting for approval …  4:12", style = GlacierText.body(19), color = GlacierColors.Mist)
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton("New code", onClick = {}, primary = true, modifier = Modifier.focusRequester(newCode))
                    PillButton("Use password", onClick = {})
                }
            }
        }
    }
    LaunchedEffect(Unit) { newCode.requestFocus() }
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

/** TopNav: the bar's row in the theme's chrome, its entries in the theme's looks. */
@Composable
fun TopBarStandIn(
    focused: String?,
    active: String = "Home",
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .then(NixieTheme.slots.topBarChrome?.let { Modifier.it() } ?: Modifier)
            .padding(horizontal = 7.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NavEntry("Search", focused, active, icon = true)
        Spacer(Modifier.width(6.dp))
        listOf("Home", "Movies", "Shows", "Music", "Favorites").forEach { NavEntry(it, focused, active) }
        Spacer(Modifier.width(6.dp))
        NavEntry("Settings", focused, active, icon = true)
        Spacer(Modifier.width(6.dp))
        NavEntry("Profile", focused, active, icon = true)
    }
}

@Composable
private fun NavEntry(
    name: String,
    focusedName: String?,
    activeName: String,
    icon: Boolean = false,
) {
    val focused = name == focusedName
    val look = NixieTheme.slots.topBarEntry?.invoke(focused, name == activeName)
        ?: NavEntryLook(Color.Transparent, GlacierColors.Mist, Color.Transparent)
    val accent = LocalAccent.current
    Box(
        Modifier
            .focusScale(focused)
            .height(54.dp)
            .defaultMinSize(minWidth = 54.dp)
            .clip(PillShape)
            .background(look.background)
            .border(2.dp, look.border, PillShape)
            .drawBehind {
                if (look.underline.isSpecified) {
                    val h = 3.dp.toPx()
                    val inset = 14.dp.toPx()
                    val top = Offset(inset, size.height - h - 5.dp.toPx())
                    drawRect(look.underline, top, Size(size.width - 2 * inset, h))
                }
            }
            .padding(horizontal = if (icon) 15.dp else 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (name) {
            "Search" -> Icon(GlacierIcons.Search, name, tint = look.foreground, modifier = Modifier.size(22.dp))

            "Settings" -> Icon(GlacierIcons.Settings, name, tint = look.foreground, modifier = Modifier.size(22.dp))

            "Profile" -> Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(accent.main, accent.deep))),
                contentAlignment = Alignment.Center,
            ) {
                Text("M", style = GlacierText.display(16), color = GlacierColors.Void)
            }

            else -> Text(name, style = GlacierText.body(19, FontWeight.SemiBold), color = look.foreground)
        }
    }
}

/** A page without a picture (Settings, a library): the page ground under the bar, an entry focused. */
@Composable
fun StagePageStandIn() {
    GlacierBackground {
        TopBarStandIn(focused = "Movies", modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp))
        Column(
            Modifier.padding(start = 80.dp, top = 190.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Settings", style = GlacierText.display(44), color = GlacierColors.Ice)
            Text(
                "Appearance, playback, subtitles and your account.",
                style = GlacierText.body(21),
                color = GlacierColors.Mist,
            )
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton("Appearance", onClick = {})
                PillButton("Playback", onClick = {})
                PillButton("Account", onClick = {})
            }
        }
    }
}

/** Home: the Spotlight (680 high) under the bar, Continue watching beneath it, one card focused. */
@Composable
fun HomeStandIn(art: @Composable (Modifier) -> Unit) {
    val play = remember { FocusRequester() }
    GlacierBackground {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(680.dp).clipToBounds()) {
                NixieTheme.slots.heroBackdrop?.invoke(this, "backdrop") { _, artModifier -> art(artModifier) }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, GlacierColors.Void))),
                )
                HeroInfo(
                    play,
                    Modifier.align(Alignment.BottomStart).padding(start = 80.dp, bottom = 74.dp).width(900.dp),
                )
                // Spotlight's hook: the theme's marks, given the titles' stills and an artwork drawer.
                NixieTheme.slots.heroPositionMarks
                    ?.invoke(this, 5, 0, { 0.62f }, listOf("2", "0", "3", "1", "4"), Stills)
                    ?: PositionMarks(Modifier.align(Alignment.BottomEnd).padding(end = 80.dp, bottom = 78.dp))
            }
            RowStandIn("Continue watching", "5 titles")
        }
        TopBarStandIn(focused = null, modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp))
    }
    LaunchedEffect(Unit) { play.requestFocus() }
}

@Composable
private fun HeroInfo(play: FocusRequester, modifier: Modifier) {
    val accent = LocalAccent.current.main
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // Spotlight's hook: the theme's kicker while resuming, Glacier's chip otherwise.
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("★ 7.9", style = GlacierText.body(20, FontWeight.Bold), color = accent)
            Text("1997  ·  1 h 58 min  ·  Thriller", style = GlacierText.body(20), color = GlacierColors.Mist)
        }
        Text(
            "A courier with one day left on her licence takes the long road across the desert, and the road takes " +
                "something back.",
            style = GlacierText.body(21).copy(lineHeight = 34.sp),
            color = GlacierColors.Ice,
            maxLines = 3,
            modifier = Modifier.width(820.dp),
        )
        Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(PillShape).background(GlacierColors.GlassFill2)) {
                Box(Modifier.fillMaxWidth(0.38f).fillMaxHeight().clip(PillShape).background(accent))
            }
            Text("72 min left", style = GlacierText.body(17), color = GlacierColors.Mist)
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton(
                onClick = {},
                label = "Resume",
                icon = GlacierIcons.Play,
                primary = true,
                modifier = Modifier.focusRequester(play),
            )
            ActionButton(onClick = {}, label = "More info")
            ActionButton(onClick = {}, icon = GlacierIcons.Heart, contentDescription = "Favorite")
        }
    }
}

/** Spotlight's position marks: square-cornered in a theme with its own pill (Glacier's own hook). */
@Composable
private fun PositionMarks(modifier: Modifier) {
    val accent = LocalAccent.current.main
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(5) { i ->
            Box(
                Modifier
                    .height(26.dp)
                    .width(if (i == 0) 34.dp else 12.dp)
                    .drawBehind {
                        val bar = Size(size.width, 12.dp.toPx())
                        drawRoundRect(
                            if (i == 0) accent else Color(0x4DE8F4F7),
                            Offset(0f, (size.height - bar.height) / 2),
                            bar,
                            androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                        )
                    },
            )
        }
    }
}

/** MediaRow: the theme's row header over 16:9 cards (Comfortable: 316 × 178, gap 26), the second focused. */
@Composable
private fun RowStandIn(title: String, subtitle: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        NixieTheme.slots.rowHeader?.invoke(title, subtitle, Modifier.padding(start = 80.dp, end = 80.dp))
        Row(
            Modifier.offset(y = (-8).dp).padding(horizontal = 80.dp, vertical = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            StillCards.forEachIndexed { i, (name, line) -> Card(name, line, focused = i == 1, palette = i) }
        }
    }
}

private val StillCards = listOf(
    "Iron Lighthouses" to "S3 E4 · 31 min left",
    "The Iron Circus" to "S1 E3 · 44 min left",
    "Copper Rivers" to "S6 E2 · 15 min left",
    "A Winter Apart" to "S1 E6 · 22 min left",
    "Under the Neon" to "S1 E2 · 49 min left",
)

/** Draws a sample still for a "url" that is its palette's number. */
internal val Stills: ArtworkDrawer = { url, modifier -> SampleStill(url?.toIntOrNull() ?: 0, modifier) }

/** ContinueCard's frame: the theme's still card inside Glacier's zoom, focus frame and clip; Glacier's card otherwise. */
@Composable
private fun Card(
    name: String,
    line: String,
    focused: Boolean,
    palette: Int,
) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    val still = NixieTheme.slots.stillCard
    if (still != null) {
        Box(
            Modifier
                .focusScale(focused)
                .size(316.dp, 178.dp)
                .focusFrame(focused, shape)
                .clip(shape),
        ) {
            still(palette.toString(), name, line, if (palette % 2 == 0) 0.55f else 0.3f, focused, Stills)
        }
        return
    }
    Column(Modifier.focusScale(focused), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(316.dp, 178.dp)
                .focusFrame(focused, shape)
                .clip(shape),
        ) {
            SampleStill(palette, Modifier.fillMaxSize())
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(if (palette % 2 == 0) 0.55f else 0.3f)
                    .padding(start = 12.dp, bottom = 10.dp)
                    .height(4.dp)
                    .clip(PillShape)
                    .background(LocalAccent.current.main),
            )
        }
        Column(Modifier.width(316.dp)) {
            Text(name, style = GlacierText.body(19, FontWeight.SemiBold), color = GlacierColors.Ice, maxLines = 1)
            Text(line, style = GlacierText.body(16), color = GlacierColors.Mist, maxLines = 1)
        }
    }
}

/** A sample still for the cards: a sky, a sun and a ridge, in one of five palettes. */
@Composable
fun SampleStill(palette: Int, modifier: Modifier) {
    val (sky, glow, land) = when (palette % 5) {
        0 -> Triple(Color(0xFF0F3B47), Color(0xFFF3E3B0), Color(0xFF0A2128))
        1 -> Triple(Color(0xFF2A1450), Color(0xFFE2B6FF), Color(0xFF140A26))
        2 -> Triple(Color(0xFF6B2A14), Color(0xFFFFD08A), Color(0xFF2B0E06))
        3 -> Triple(Color(0xFF2C4E6E), Color(0xFFFFFFFF), Color(0xFF16283A))
        else -> Triple(Color(0xFF4A1230), Color(0xFFFFB3C8), Color(0xFF1C0612))
    }
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(sky, glow.copy(alpha = 0.6f).compositeOver(sky))))
        drawCircle(glow, radius = size.height * 0.16f, center = Offset(size.width * 0.62f, size.height * 0.55f))
        drawRect(land, topLeft = Offset(0f, size.height * 0.68f), size = Size(size.width, size.height * 0.32f))
    }
}

/** A bright full-bleed backdrop for the hero: a noon desert, much lighter than most art. */
@Composable
fun BrightBackdrop(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF8EC5E8), Color(0xFFF6E7C8))))
        val sun = Offset(size.width * 0.74f, size.height * 0.28f)
        drawCircle(Color(0xFFFFFBEA), radius = size.height * 0.12f, center = sun)
        drawRect(
            Brush.verticalGradient(listOf(Color(0xFFD9A066), Color(0xFF9C5A2E))),
            topLeft = Offset(0f, size.height * 0.66f),
            size = Size(size.width, size.height * 0.34f),
        )
        val dune = Offset(size.width * 0.86f, size.height * 0.96f)
        drawCircle(Color(0xFFB8733F), radius = size.height * 0.4f, center = dune)
    }
}

private fun Color.compositeOver(background: Color): Color = this over background

/** The box every shot is drawn in: the 1080p design grid, one dp per pixel. */
@Composable
fun Screen(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.size(1920.dp, 1080.dp), content = content)
}
