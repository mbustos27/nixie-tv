// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// The scene is drawn from coordinates and shares of the screen: those numbers are the art, not magic.
@file:Suppress("MagicNumber", "MatchingDeclarationName")

package io.github.mbustos27.nixie.tv.theme.drivein

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * Drive-in's dusk, drawn at run time from a few shapes (no image, no blur): a sky from night to a coral horizon, faint
 * stars, the sun low behind the lot, and two layers of mesas. Everything is in dp on the theme's 1920-wide grid, so
 * one drawing fits every TV.
 */
internal object Dusk {
    private const val STAR_COUNT = 70

    /** Stars as (x share, y share of the sky, radius dp, alpha), fixed so the sky never shuffles between frames. */
    private val stars: List<FloatArray> = Random(66).let { r ->
        List(STAR_COUNT) {
            floatArrayOf(
                r.nextFloat(),
                r.nextFloat() * r.nextFloat(),
                0.7f + r.nextFloat() * 1.1f,
                0.2f + r.nextFloat() * 0.5f,
            )
        }
    }

    /** The far mesas: (x share, height above the horizon in dp). */
    private val farRidge = listOf(
        0f to 10f, 0.05f to 26f, 0.08f to 30f, 0.1f to 58f, 0.17f to 60f, 0.19f to 32f, 0.27f to 28f, 0.31f to 16f,
        0.43f to 14f, 0.47f to 38f, 0.48f to 70f, 0.56f to 72f, 0.58f to 42f, 0.63f to 36f, 0.71f to 20f, 0.81f to 18f,
        0.85f to 46f, 0.87f to 50f, 0.94f to 48f, 0.96f to 24f, 1f to 22f,
    )

    /** The near ridge, lower and darker. */
    private val nearRidge = listOf(
        0f to 18f, 0.12f to 14f, 0.2f to 22f, 0.22f to 36f, 0.3f to 38f, 0.32f to 20f, 0.5f to 12f, 0.66f to 16f,
        0.69f to 30f, 0.76f to 32f, 0.78f to 14f, 1f to 10f,
    )

    /** The sky down to [horizon] (px), with the sun's glow centred at [sunX] (share of the width). */
    fun DrawScope.sky(horizon: Float, sunX: Float) {
        drawRect(
            Brush.verticalGradient(
                0f to DriveInPalette.Night2,
                0.4f to Color(0xFF2E0A1C),
                0.72f to Color(0xFF6A1736),
                1f to Color(0xFFD0475E),
                endY = horizon,
            ),
            size = Size(size.width, horizon),
        )
        // The afterglow, widest at the horizon under the sun.
        drawRect(
            Brush.radialGradient(
                0f to DriveInPalette.Coral.copy(alpha = 0.55f),
                0.5f to DriveInPalette.Rose.copy(alpha = 0.16f),
                1f to Color.Transparent,
                center = Offset(size.width * sunX, horizon),
                radius = size.width * 0.42f,
            ),
            size = Size(size.width, horizon),
        )
        val sky = horizon * 0.62f
        for (s in stars) {
            drawCircle(
                DriveInPalette.NeonCore.copy(alpha = s[3]),
                radius = s[2].dp.toPx(),
                center = Offset(s[0] * size.width, s[1] * sky),
            )
        }
        // The sun, half set.
        val r = 150.dp.toPx()
        drawCircle(
            Brush.verticalGradient(
                listOf(DriveInPalette.Sun, DriveInPalette.Coral),
                startY = horizon - r,
                endY = horizon,
            ),
            radius = r,
            center = Offset(size.width * sunX, horizon + r * 0.35f),
        )
    }

    /** Both ridges and the dark ground below [horizon] (px) to the bottom. */
    fun DrawScope.land(horizon: Float) {
        drawPath(ridge(farRidge, horizon), Color(0xFF3A0B1E))
        drawPath(ridge(nearRidge, horizon), Color(0xFF1E0610))
        drawRect(DriveInPalette.Night2, Offset(0f, horizon), Size(size.width, size.height - horizon))
    }

    private fun DrawScope.ridge(points: List<Pair<Float, Float>>, horizon: Float) = Path().apply {
        moveTo(0f, horizon + 1f)
        for ((x, h) in points) lineTo(x * size.width, horizon - h.dp.toPx())
        lineTo(size.width, horizon + 1f)
        close()
    }

    /** The page ground: night sky, the last of the glow low down, a few stars. */
    fun DrawScope.ground() {
        drawRect(Brush.verticalGradient(listOf(DriveInPalette.Night2, DriveInPalette.Night)))
        drawRect(
            Brush.radialGradient(
                0f to DriveInPalette.Crimson.copy(alpha = 0.22f),
                1f to Color.Transparent,
                center = Offset(size.width * 0.7f, size.height * 1.05f),
                radius = size.width * 0.6f,
            ),
        )
        val sky = size.height * 0.4f
        for (s in stars) {
            drawCircle(
                DriveInPalette.NeonCore.copy(alpha = s[3] * 0.6f),
                radius = s[2].dp.toPx(),
                center = Offset(s[0] * size.width, s[1] * sky),
            )
        }
    }
}

/** Where the outdoor screen stands on the hero's 1920 x 680 grid; other heights scale it, right edge fixed. */
private object Screen {
    const val HERO_HEIGHT = 680
    const val RIGHT = 1800
    const val TOP = 136
    const val HEIGHT = 414
    const val HORIZON = 600
}

/**
 * Home's hero and the item pages' backdrop in Drive-in: dusk over the lot, the feature on the outdoor screen at the
 * right, and the text over the dark sky at the left. [height] is the region's height in dp (Home's hero is 680); the
 * screen scales with it. The projector's beam is a faint wedge, never a blur.
 */
@Composable
internal fun BoxScope.DriveInHero(
    imageUrl: String?,
    artwork: @Composable (String?, Modifier) -> Unit,
    height: Int = Screen.HERO_HEIGHT,
    /** Fade the foot into the page (the item pages; Home's hero has Glacier's own fade). */
    fadeFoot: Boolean = false,
) {
    val k = height / Screen.HERO_HEIGHT.toFloat()
    val screenHeight = Screen.HEIGHT * k
    val screenWidth = screenHeight * 16f / 9f
    val screenLeft = Screen.RIGHT - screenWidth
    val screenTop = Screen.TOP * k
    val horizonAt = Screen.HORIZON * k
    Canvas(Modifier.matchParentSize()) {
        val horizon = horizonAt.dp.toPx()
        with(Dusk) {
            sky(horizon, sunX = 0.965f)
            land(horizon)
        }
        val left = screenLeft.dp.toPx()
        val top = screenTop.dp.toPx()
        val w = screenWidth.dp.toPx()
        val h = screenHeight.dp.toPx()
        // The posts and the frame.
        val post = 18.dp.toPx() * k
        listOf(left + w * 0.2f, left + w * 0.8f - post).forEach { x ->
            drawRect(Color(0xFF1C0810), Offset(x, top + h), Size(post, horizon - top - h + 6.dp.toPx()))
        }
        val frame = 12.dp.toPx() * k
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF3A1824), Color(0xFF1C0810))),
            Offset(left - frame, top - frame),
            Size(w + 2 * frame, h + 2 * frame),
            CornerRadius(4.dp.toPx()),
        )
        // The beam from the booth, low at the left of the lot.
        val booth = Offset(left - 260.dp.toPx() * k, horizon + 40.dp.toPx() * k)
        drawPath(
            Path().apply {
                moveTo(booth.x, booth.y)
                lineTo(left, top)
                lineTo(left, top + h)
                close()
            },
            Brush.linearGradient(
                listOf(DriveInPalette.NeonCore.copy(alpha = 0.0f), DriveInPalette.NeonCore.copy(alpha = 0.07f)),
                start = booth,
                end = Offset(left, top + h / 2),
            ),
        )
        // Shade at the left, where the text sits, so it reads at 4.5:1 or better over any sky.
        drawRect(
            Brush.horizontalGradient(
                0f to DriveInPalette.Night.copy(alpha = 0.86f),
                0.32f to DriveInPalette.Night.copy(alpha = 0.6f),
                0.52f to Color.Transparent,
            ),
        )
        if (fadeFoot) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to DriveInPalette.Night,
                    startY = horizon,
                    endY = size.height,
                ),
                Offset(0f, horizon),
                Size(size.width, size.height - horizon),
            )
        }
    }
    Box(
        Modifier
            .offset(screenLeft.dp, screenTop.dp)
            .size(screenWidth.dp, screenHeight.dp)
            .clip(RoundedCornerShape(2.dp)),
    ) {
        artwork(imageUrl, Modifier.fillMaxSize())
    }
    // The screen's lit edge.
    Canvas(
        Modifier
            .offset(screenLeft.dp, screenTop.dp)
            .size(screenWidth.dp, screenHeight.dp),
    ) {
        drawRect(Color(0x8CFFF4EE), style = Stroke(1.dp.toPx()))
    }
}

/** Behind the setup and sign-in screens: the lot at dusk, low along the bottom. */
@Composable
internal fun BoxScope.DriveInLot() {
    Canvas(Modifier.matchParentSize()) {
        val horizon = size.height * 0.86f
        with(Dusk) {
            ground()
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color(0xFF7A1F3A).copy(alpha = 0.55f),
                    startY = horizon - 220.dp.toPx(),
                    endY = horizon,
                ),
                Offset(0f, horizon - 220.dp.toPx()),
                Size(size.width, 220.dp.toPx()),
            )
            land(horizon)
        }
    }
}
