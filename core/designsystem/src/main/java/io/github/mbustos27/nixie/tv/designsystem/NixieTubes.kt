// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.round

/**
 * Nixie tubes, drawn at run time: each digit is a cathode of a few wire strokes in a 10 x 16 box (the same wires as
 * Nixie for Windows' Drive-in readout), drawn as a stroke stack (a wide faint halo, a glow, a bright core) with no
 * image and no blur, so a readout stays crisp at any size and costs no GPU effect. Any theme may use them; Drive-in
 * shows Quick Connect's code on them, and the brand mark is one tube.
 */
object NixieGlyphs {
    const val BOX_WIDTH = 10f
    const val BOX_HEIGHT = 16f

    private val wires: Map<Char, String> = mapOf(
        '0' to "M5,0.9 C8.3,0.9 9.1,3.9 9.1,8 C9.1,12.1 8.3,15.1 5,15.1 C1.7,15.1 0.9,12.1 0.9,8 " +
            "C0.9,3.9 1.7,0.9 5,0.9 Z",
        '1' to "M2.9,3.7 L5.7,0.9 L5.7,15.1",
        '2' to "M1.3,4.3 C1.4,2.2 3,0.9 5,0.9 C7.2,0.9 8.7,2.3 8.7,4.4 C8.7,6.5 7.4,7.8 5.6,9.5 L1.1,15.1 L9,15.1",
        '3' to "M1.4,0.9 L8.5,0.9 L4.4,6.3 C7.2,6.2 9,8.1 9,10.7 C9,13.4 7.1,15.1 4.9,15.1 C3,15.1 1.6,14.2 1,12.7",
        '4' to "M7,15.1 L7,0.9 L1,11 L9.2,11",
        '5' to "M8.4,0.9 L2.1,0.9 L1.5,7 C2.4,6.2 3.6,5.8 5,5.8 C7.4,5.8 9,7.7 9,10.4 C9,13.2 7.2,15.1 4.8,15.1 " +
            "C2.9,15.1 1.6,14.3 1,12.8",
        '6' to "M7.9,1.6 C7.1,1.1 6.2,0.9 5.3,0.9 C2.5,0.9 1,3.6 1,8.4 L1,10.6 C1,13.4 2.6,15.1 5,15.1 " +
            "C7.4,15.1 9,13.4 9,10.6 C9,7.9 7.4,6.2 5,6.2 C3,6.2 1.6,7.4 1,9",
        '7' to "M1,0.9 L9,0.9 L3.7,15.1",
        '8' to "M5,7.3 C3,7.3 1.6,6 1.6,4.1 C1.6,2.2 3,0.9 5,0.9 C7,0.9 8.4,2.2 8.4,4.1 C8.4,6 7,7.3 5,7.3 Z " +
            "M5,7.3 C2.6,7.3 1,8.8 1,11.2 C1,13.6 2.6,15.1 5,15.1 C7.4,15.1 9,13.6 9,11.2 C9,8.8 7.4,7.3 5,7.3 Z",
        '9' to "M2.1,14.4 C2.9,14.9 3.8,15.1 4.7,15.1 C7.5,15.1 9,12.4 9,7.6 L9,5.4 C9,2.6 7.4,0.9 5,0.9 " +
            "C2.6,0.9 1,2.6 1,5.4 C1,8.1 2.6,9.8 5,9.8 C7,9.8 8.4,8.6 9,7",
        '-' to "M3,8.4 L7,8.4",
        '−' to "M3,8.4 L7,8.4",
        // Separators as wire dots: a round-capped stub draws a dot at any size.
        ':' to "M5,5.3 L5,5.5 M5,11.3 L5,11.5",
        '.' to "M5,14.3 L5,14.5",
        // The brand's N: the same wire as the launcher icon's tube.
        'N' to "M2,15.1 L2,0.9 L8,15.1 L8,0.9",
    )

    private val paths = HashMap<Char, Path>()

    /** The wire for [c] in box units, or null for a character the tubes cannot show. */
    fun wire(c: Char): Path? {
        val key = c.uppercaseChar()
        val data = wires[key] ?: return null
        return paths.getOrPut(key) { PathParser().parsePathString(data).toPath() }
    }

    fun canShow(c: Char): Boolean = wires.containsKey(c.uppercaseChar())
}

/** The tube's colours (the desktop's concept kit): lit #FFC9A8 over a #FF7A50 glow, the core near white. */
object NixieTubeColors {
    val Core = Color(0xFFFFE6D2)
    val Lit = Color(0xFFFFC9A8)
    val Glow = Color(0xFFFF7A50)
    val Halo = Color(0xFFFF5A28)
    val Rest = Color(0xFFB39890)
    val Ghost = Color(0x1CFFC8AA)
    val Glass = Color(0xFF14050A)
}

/** Width of one tube for a tube [height]. */
fun nixieTubeWidth(height: Dp): Dp = height * 0.66f

/**
 * One tube showing [char]. [lit] false shows the digit at rest (no glow), as for a value not yet known. The other
 * cathodes stay faintly visible behind it, as in a real tube.
 */
@Composable
fun NixieTube(
    char: Char,
    height: Dp,
    modifier: Modifier = Modifier,
    lit: Boolean = true,
) {
    Canvas(modifier.size(nixieTubeWidth(height), height)) { drawNixieTube(char, lit) }
}

/** Several tubes in a row, read aloud as [text]. Characters the tubes cannot show are skipped. */
@Composable
fun NixieReadout(
    text: String,
    height: Dp,
    modifier: Modifier = Modifier,
    lit: Boolean = true,
    spokenAs: String = text,
) {
    Row(
        modifier.clearAndSetSemantics { contentDescription = spokenAs },
        horizontalArrangement = Arrangement.spacedBy(max(2f, round(height.value * 0.11f)).dp),
    ) {
        text.filter(NixieGlyphs::canShow).forEach { NixieTube(it, height, lit = lit) }
    }
}

/** Draws a tube filling this scope: glass body, rim, anode mesh, the unlit cathodes, then [char]'s wire. */
fun DrawScope.drawNixieTube(char: Char, lit: Boolean) {
    val h = size.height
    val w = size.width
    val radius = when {
        h < 30.dp.toPx() -> 4.dp.toPx()
        h < 48.dp.toPx() -> 6.dp.toPx()
        else -> 8.dp.toPx()
    }
    drawRoundRect(
        Brush.radialGradient(
            0f to Color(0xFF4A1A14),
            0.7f to Color(0xFF1E0808),
            1f to NixieTubeColors.Glass,
            center = Offset(w / 2, h * 0.3f),
            radius = max(w, h) * 0.8f,
        ),
        cornerRadius = CornerRadius(radius),
    )
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0x5AFFDCC8), Color(0x28FFA078), Color(0x18FFA078))),
        cornerRadius = CornerRadius(radius),
        style = Stroke(1.dp.toPx()),
    )
    if (h >= 48.dp.toPx()) drawMesh(w, h)

    val glyphHeight = h * 0.58f
    val k = glyphHeight / NixieGlyphs.BOX_HEIGHT
    val x = (w - NixieGlyphs.BOX_WIDTH * k) / 2
    val y = (h - glyphHeight) / 2
    val core = h * 0.07f
    val ghost = NixieTubeColors.Ghost.copy(alpha = if (lit) 0.11f else 0.05f)
    NixieGlyphs.wire('8')?.let { drawWire(it, x, y, k, ghost, core) }
    val wire = NixieGlyphs.wire(char) ?: return
    if (!lit) {
        drawWire(wire, x, y, k, NixieTubeColors.Rest, core)
        return
    }
    drawWire(wire, x, y, k, NixieTubeColors.Halo.copy(alpha = 0.12f), core * 3.8f)
    drawWire(wire, x, y, k, NixieTubeColors.Glow.copy(alpha = 0.42f), core * 2.4f)
    drawWire(wire, x, y, k, NixieTubeColors.Core, core)
}

private fun DrawScope.drawWire(
    wire: Path,
    x: Float,
    y: Float,
    k: Float,
    color: Color,
    width: Float,
) {
    withTransform({
        translate(x, y)
        scale(k, k, pivot = Offset.Zero)
    }) {
        drawPath(wire, color, style = Stroke(width / k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The anode's honeycomb: two sets of fine diagonals at 60 degrees, faint, inside the glass. */
private fun DrawScope.drawMesh(w: Float, h: Float) {
    val inset = 3.dp.toPx()
    val step = max(3.5f, h / 11f)
    val run = h * 0.577f
    clipRect(inset, inset, w - inset, h - inset) {
        var start = -h
        while (start < w + h) {
            val color = Color(0x0FFFBE96)
            drawLine(color, Offset(start, 0f), Offset(start + run, h), strokeWidth = 0.6f)
            drawLine(color, Offset(start, h), Offset(start + run, 0f), strokeWidth = 0.6f)
            start += step
        }
    }
}

/** Nixie's mark: one tube with a glowing N. It replaces Glacier's diamond in the setup screens. */
@Composable
fun NixieMark(size: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.size((size * 0.66f).dp, size.dp)) { drawNixieTube('N', lit = true) }
}

/** The loading mark: a tube counting through its cathodes, in place of Glacier's turning diamond. */
@Composable
fun NixieSpinner(size: Int, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "nixieSpinner")
    val step by transition.animateFloat(
        initialValue = 0f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(tween(2_000, easing = LinearEasing)),
        label = "digit",
    )
    Canvas(modifier.size((size * 0.66f).dp, size.dp)) {
        drawNixieTube('0' + step.toInt().coerceIn(0, 9), lit = true)
    }
}
