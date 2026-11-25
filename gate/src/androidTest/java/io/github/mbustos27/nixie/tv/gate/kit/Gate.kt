// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.gate.kit

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File

/**
 * The gate's run: the device, the build under test and where screenshots go, from the instrumentation arguments the
 * runner (tools/gate/Run-Gate.ps1) passes.
 */
object Gate {
    /** The app id of the Nixie build under test. */
    const val ARG_PACKAGE = "gate.package"

    /** The theme this run wears: DriveIn, Cinematic or Editorial. */
    const val ARG_THEME = "gate.theme"

    const val DEFAULT_PACKAGE = "io.github.mbustos27.nixie.tv.debug"
    const val DEFAULT_THEME = "DriveIn"

    val device: UiDevice by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    private val args: Bundle get() = InstrumentationRegistry.getArguments()

    val pkg: String get() = args.getString(ARG_PACKAGE) ?: DEFAULT_PACKAGE

    val theme: String get() = args.getString(ARG_THEME) ?: DEFAULT_THEME

    /** Screenshots and window dumps, pulled by the runner afterwards. */
    val out: File by lazy {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(base, "gate").apply { mkdirs() }
    }

    /** A shell command as the shell user (adb's rights), its output. */
    fun shell(command: String): String = device.executeShellCommand(command)
}

/** A failed check: one line that says what the user saw. */
class GateFailure(message: String) : AssertionError(message.lineSequence().joinToString(" ").trim())

/** Fails the check with [message] (one line, what the user saw) unless [condition] holds. */
inline fun check(condition: Boolean, message: () -> String) {
    if (!condition) throw GateFailure(message())
}

/** Fails the check with [message]. */
fun fail(message: String): Nothing = throw GateFailure(message)

/** Polls [condition] every [stepMs] until it holds or [timeoutMs] pass; true when it held. */
fun waitUntil(
    timeoutMs: Long,
    stepMs: Long = 200,
    condition: () -> Boolean,
): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        if (runCatching(condition).getOrDefault(false)) return true
        if (System.currentTimeMillis() >= end) return false
        Thread.sleep(stepMs)
    }
}
