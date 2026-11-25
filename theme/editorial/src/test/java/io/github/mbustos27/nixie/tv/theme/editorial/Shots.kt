// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.theme.editorial

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalInputModeManager
import java.awt.image.BufferedImage
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** Keyboard input mode, as on a TV box with its remote. */
private object DpadMode : InputModeManager {
    override val inputMode: InputMode = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
}

/** A plain full-screen activity to host a composition: no action bar, no insets. */
class ShotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar_Fullscreen)
        super.onCreate(savedInstanceState)
    }
}

/**
 * Draws a composition at Glacier's 1920 x 1080 grid under Robolectric's native graphics and keeps it as a PNG. A
 * stand-in until Roborazzi is on main; the folder comes from the build (-PnixieShotsDir).
 */
object Shots {
    const val WIDTH = 1920
    const val HEIGHT = 1080

    private val dir = File(System.getProperty("nixie.shots.dir") ?: "build/editorial-shots")

    /**
     * [focus], when given, is asked for after the first settle and again until [isFocused] says it landed (a test's
     * `onFocusChanged`), so a shot that checks focus never depends on when the window took it.
     *
     * The composition runs on a recomposer of its own, made for this render on the current main looper and driven
     * by [settle]'s frames. Compose's default one is made once per JVM, on the first test's looper; Robolectric gives
     * every later test a fresh looper, so under it effects never started and nothing recomposed after the first test
     * (a focus frame, an animation, a LaunchedEffect): what made a focus test depend on which test ran first.
     */
    fun render(
        focus: FocusRequester? = null,
        isFocused: () -> Boolean = { true },
        content: @Composable () -> Unit,
    ): Bitmap {
        // A TV has no touch mode: out of it, a button takes focus (Compose's clickables are focusable only then).
        // Set before the window exists, which takes the mode when it is made; then the window gets focus.
        instrumentation().setInTouchMode(false)
        val controller = Robolectric.buildActivity(ShotActivity::class.java).setup().windowFocusChanged(true)
        val activity = controller.get()
        val clock = BroadcastFrameClock()
        val context = Handler(Looper.getMainLooper()).asCoroutineDispatcher() + clock
        val recomposer = Recomposer(context)
        val running = CoroutineScope(context).launch { recomposer.runRecomposeAndApplyChanges() }
        try {
            val view = ComposeView(activity).apply {
                setParentCompositionContext(recomposer)
                // And D-pad mode for Compose itself, as the app's ThemeShotsTest, whatever the window's mode.
                setContent { CompositionLocalProvider(LocalInputModeManager provides DpadMode) { content() } }
            }
            activity.setContentView(view)
            settle(clock)
            if (focus != null) {
                repeat(FOCUS_TRIES) {
                    if (!isFocused()) {
                        runCatching { focus.requestFocus() }
                        settle(clock)
                    }
                }
                check(isFocused()) { "focus did not land after $FOCUS_TRIES tries" }
            }
            val root = activity.window.decorView
            check(root.width == WIDTH && root.height == HEIGHT) { "Window is ${root.width} x ${root.height}" }
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            return bitmap
        } finally {
            controller.pause().stop().destroy()
            recomposer.cancel()
            running.cancel()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun instrumentation(): Instrumentation {
        val thread = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null)
        return thread.javaClass.getMethod("getInstrumentation").invoke(thread) as Instrumentation
    }

    /**
     * Lets the composition, its layout, its focus and its animations run out: 1.2 s of 16 ms frames on Robolectric's
     * clock, each with the snapshot changes sent, so the recomposer sees them.
     */
    private fun settle(clock: BroadcastFrameClock) {
        repeat(SETTLE_FRAMES) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))
            clock.sendFrame(SystemClock.uptimeMillis() * NANOS_PER_MILLI)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    fun save(bitmap: Bitmap, name: String): File {
        dir.mkdirs()
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, w, h, pixels, 0, w)
        val file = File(dir, "$name.png")
        ImageIO.write(image, "png", file)
        return file
    }
}

/** WCAG 2 contrast. */
object Contrast {
    fun luminance(argb: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(argb shr 16 and 0xFF) +
            0.7152 * channel(argb shr 8 and 0xFF) +
            0.0722 * channel(argb and 0xFF)
    }

    fun luminance(color: Color): Double = luminance(color.toArgb())

    fun ratio(a: Double, b: Double): Double = (max(a, b) + 0.05) / (min(a, b) + 0.05)

    /** [text] over [ground], with any alpha in [ground] laid over [under] first. */
    fun ratio(
        text: Color,
        ground: Color,
        under: Color = EditorialPalette.Ink,
    ): Double =
        ratio(luminance(text.compositeOver(ground.compositeOver(under))), luminance(ground.compositeOver(under)))

    /** The brightest pixel's luminance in a rectangle of [bitmap]. */
    fun brightest(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Double {
        var most = 0.0
        for (y in top until bottom) for (x in left until right) most = max(most, luminance(bitmap.getPixel(x, y)))
        return most
    }
}

/**
 * Focus as Glacier's screens take it: after a frame, once the target is laid out; asked again on the next frames
 * (asking again is harmless once it has landed), so a shot never depends on when the window got focus.
 */
suspend fun focusAfterAFrame(target: FocusRequester) {
    repeat(FOCUS_TRIES) {
        withFrameNanos { }
        runCatching { target.requestFocus() }
    }
}

private const val FOCUS_TRIES = 10
private const val SETTLE_FRAMES = 75
private const val FRAME_MS = 16L
private const val NANOS_PER_MILLI = 1_000_000L
