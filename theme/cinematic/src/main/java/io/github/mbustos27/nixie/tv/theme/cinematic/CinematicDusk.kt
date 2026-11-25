// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.cinematic

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.max

/** The dusk still behind sign-in, drawn as vectors: no image file, no motion, decoration only. */
@Composable
internal fun DuskStill(modifier: Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) { drawDusk() }
}

/**
 * Dusk on the road: a plum sky that warms only near the horizon, the sun setting at the end of the road on the far
 * right (beyond the setup screens' columns), a mesa at the left, a far butte, a saguaro in the gutter between the
 * two columns, and a vignette. The sky stays dark wherever the setup screens put text (CinematicContrastTest).
 *
 * Drawn on a 1920 x 1080 design grid, uniform to fill, anchored at the foot and the middle.
 */
internal fun DrawScope.drawDusk() {
    drawRect(CinematicPalette.Night)
    val k = max(size.width / DUSK_WIDTH, size.height / DUSK_HEIGHT)
    withTransform({
        translate((size.width - DUSK_WIDTH * k) / 2, size.height - DUSK_HEIGHT * k)
        scale(k, k, pivot = Offset.Zero)
    }) { drawDuskDesign(DuskPaths.value) }
    // A soft vignette at the edges, so the middle reads as the light's centre.
    drawRect(
        Brush.radialGradient(
            *VignetteStops,
            center = Offset(size.width * VIGNETTE_X, size.height * VIGNETTE_Y),
            radius = max(size.width, size.height) * VIGNETTE_REACH,
        ),
    )
}

private fun DrawScope.drawDuskDesign(paths: DuskShapes) {
    drawRect(
        Brush.verticalGradient(*SkyStops, startY = 0f, endY = HORIZON),
        size = Size(DUSK_WIDTH, HORIZON),
    )
    drawPath(paths.sparkles, SparkleColor)
    // The sun's glow: wide and soft, warmest just over the horizon, kept to the sky.
    drawRect(
        Brush.radialGradient(*SunGlowStops, center = Offset(SUN_X, HORIZON), radius = SUN_GLOW),
        topLeft = Offset(SUN_X - SUN_GLOW, HORIZON - SUN_GLOW),
        size = Size(SUN_GLOW * 2, SUN_GLOW),
    )
    // A haze along the horizon.
    drawRect(
        Brush.verticalGradient(*HazeStops, startY = HORIZON - HAZE, endY = HORIZON),
        topLeft = Offset(0f, HORIZON - HAZE),
        size = Size(DUSK_WIDTH, HAZE),
    )
    // The sun, its lower half cut by gaps that widen towards the ground.
    drawPath(paths.sun, Brush.verticalGradient(*SunStops, startY = HORIZON - SUN_RADIUS, endY = HORIZON))
    drawPath(paths.butte, ButteColor)
    drawPath(paths.mesa, MesaColor)
    drawRect(
        Brush.verticalGradient(*GroundStops, startY = HORIZON, endY = DUSK_HEIGHT),
        topLeft = Offset(0f, HORIZON),
        size = Size(DUSK_WIDTH, DUSK_HEIGHT - HORIZON),
    )
    // The road out to the sun, its lit edges and its centre line, dashes that grow towards the viewer.
    drawPath(paths.road, RoadColor)
    drawPath(paths.roadEdges, RoadEdgeColor, style = Stroke(width = ROAD_EDGE))
    drawPath(paths.dashes, Brush.verticalGradient(*DashStops, startY = HORIZON, endY = DUSK_HEIGHT))
    // The saguaro's shadow on the ground, so it stands, and the saguaro: one stroke family, rounded elbows.
    drawOval(
        Brush.radialGradient(*ShadowStops, center = Offset(CACTUS_X, CACTUS_FOOT), radius = SHADOW_RX),
        topLeft = Offset(CACTUS_X - SHADOW_RX, CACTUS_FOOT - SHADOW_RY / 2),
        size = Size(SHADOW_RX * 2, SHADOW_RY),
    )
    drawPath(
        paths.cactus,
        CactusColor,
        style = Stroke(width = CACTUS_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** The design grid and the still's landmarks on it. */
internal const val DUSK_WIDTH = 1920f
internal const val DUSK_HEIGHT = 1080f
private const val HORIZON = 905f
private const val SUN_X = 1790f
private const val SUN_RADIUS = 132f
private const val SUN_GLOW = 460f
private const val HAZE = 30f
private const val ROAD_EDGE = 2.5f
private const val CACTUS_X = 745f
private const val CACTUS_FOOT = 1050f
private const val CACTUS_STROKE = 28f
private const val SHADOW_RX = 80f
private const val SHADOW_RY = 28f
private const val VIGNETTE_X = 0.5f
private const val VIGNETTE_Y = 0.45f
private const val VIGNETTE_REACH = 0.75f

/** Deep plum overhead, rose only in the last band above the horizon. */
private val SkyStops = arrayOf(
    0f to Color(0xFF14040C),
    0.30f to Color(0xFF1F0715),
    0.60f to Color(0xFF330C22),
    0.85f to Color(0xFF5C1D36),
    1f to Color(0xFF742C44),
)
private val SunGlowStops = arrayOf(
    0f to CinematicPalette.Peach.copy(alpha = 0.32f),
    0.25f to CinematicPalette.Coral.copy(alpha = 0.10f),
    1f to Color.Transparent,
)
private val HazeStops = arrayOf(0f to Color.Transparent, 1f to CinematicPalette.Peach.copy(alpha = 0.14f))
private val SunStops = arrayOf(0f to Color(0xFFFFE6BE), 0.55f to Color(0xFFFCC89A), 1f to Color(0xFFF5A88E))
private val GroundStops = arrayOf(0f to Color(0xFF4A1428), 0.12f to Color(0xFF300C1C), 1f to Color(0xFF14040C))
private val DashStops = arrayOf(0f to Color(0xFFB7826E), 1f to Color(0xFFE0B49A))
private val ShadowStops = arrayOf(0f to Color(0x66080206), 1f to Color.Transparent)
private val VignetteStops = arrayOf(0.6f to Color.Transparent, 1f to CinematicPalette.Ground.copy(alpha = 0.5f))
private val SparkleColor = CinematicPalette.Peach.copy(alpha = 0.7f)
private val ButteColor = Color(0xFF4E1529)
private val MesaColor = Color(0xFF3E1022)
private val RoadColor = Color(0xFF210913)
private val RoadEdgeColor = Color(0xFF7E3648)
private val CactusColor = Color(0xFF250815)

/** The still's shapes as path data on the design grid (the horizon at y 905), parsed once when first drawn. */
private class DuskShapes {
    val sparkles = path(
        "M318,94 Q319.5,102.5 328,104 Q319.5,105.5 318,114 Q316.5,105.5 308,104 Q316.5,102.5 318,94 Z " +
            "M1046,56 Q1047.2,62.8 1054,64 Q1047.2,65.2 1046,72 Q1044.8,65.2 1038,64 Q1044.8,62.8 1046,56 Z " +
            "M1760,99 Q1761.95,110.05 1773,112 Q1761.95,113.95 1760,125 Q1758.05,113.95 1747,112 " +
            "Q1758.05,110.05 1760,99 Z " +
            "M1850,311 Q1851.05,316.95 1857,318 Q1851.05,319.05 1850,325 Q1848.95,319.05 1843,318 " +
            "Q1848.95,316.95 1850,311 Z",
    )
    val sun: Path = Path.combine(
        PathOperation.Difference,
        path("M1658,905 A132,132 0 0 1 1922,905 Z"),
        path("M0,855 H1920 V860 H0 Z M0,872 H1920 V880 H0 Z M0,890 H1920 V901 H0 Z"),
    )
    val butte = path("M1480,905 C1494,888 1506,870 1518,862 L1610,862 C1622,870 1636,888 1650,905 Z")
    val mesa = path("M40,905 C66,873 90,840 108,812 L360,812 C376,826 410,858 438,880 C474,894 530,900 590,905 Z")
    val road = path("M1782,905 L1798,905 L2350,1080 L1230,1080 Z")
    val roadEdges = path("M1782,905 L1230,1080 M1798,905 L2350,1080")
    val dashes = path(
        "M1788.35,918 H1791.65 V924 H1788.35 Z M1787.6,936 H1792.4 V946 H1787.6 Z " +
            "M1786.5,962 H1793.5 V978 H1786.5 Z M1784.9,998 H1795.1 V1024 H1784.9 Z M1783,1050 H1797 V1080 H1783 Z",
    )
    val cactus = path(
        "M745,1048 L745,660 " +
            "M745,830 C745,842 739,846 725,846 L717,846 C705,846 701,838 701,826 L701,770 " +
            "M745,780 C745,792 751,796 765,796 L773,796 C785,796 789,788 789,776 L789,712",
    )

    private fun path(data: String): Path = PathParser().parsePathString(data).toPath()
}

private val DuskPaths = lazy { DuskShapes() }
