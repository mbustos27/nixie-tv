// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import android.Manifest
import android.app.Application
import android.content.Intent
import io.github.glacier_jellyfin.androidtv.BuildConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Stream D (tv-log-pull): the receiver that writes the log for adb is in a debug build's merged manifest, reachable
 * only by a sender holding DUMP, and in no other build. AGP runs the JVM tests on the debug build alone (unit tests
 * are off for release), so the release side is checked on what a release build merges: no manifest or source outside
 * a debug or test source set, in any module, names the receiver or its action.
 *
 * Integration 1: the release-like staging build takes the same src/debug files (its receiver and the provider the
 * script reads the file through), and only it besides debug: app/build.gradle.kts names src/debug nowhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdbLogManifestTest {

    private val repo = File("").absoluteFile.let { if (File(it, "settings.gradle.kts").isFile) it else it.parentFile }

    @Test
    fun `a debug build takes the log broadcast, only from a sender holding DUMP`() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue("the JVM tests run on ${BuildConfig.BUILD_TYPE}, not debug", BuildConfig.DEBUG)
        val receivers = app.packageManager.queryBroadcastReceivers(Intent(ACTION).setPackage(app.packageName), 0)
            .map { it.activityInfo }
        assertEquals(listOf(RECEIVER), receivers.map { it.name })
        assertEquals(Manifest.permission.DUMP, receivers.single().permission)
        assertTrue(receivers.single().exported)
    }

    @Test
    fun `a debug build hands the log only to a caller holding DUMP`() {
        val app = RuntimeEnvironment.getApplication()
        val provider = app.packageManager.resolveContentProvider("${app.packageName}.adblog", 0)
        assertEquals(PROVIDER, provider?.name)
        assertEquals(Manifest.permission.DUMP, provider?.readPermission)
        assertEquals(Manifest.permission.DUMP, provider?.writePermission)
        assertTrue(provider!!.exported)
    }

    @Test
    fun `nothing a release build merges names the receiver or its action`() {
        val debugManifest = File(repo, "app/src/debug/AndroidManifest.xml")
        check(debugManifest.isFile) { "the repository was not found from ${File("").absolutePath}" }
        val naming = repo.walk()
            .onEnter { it.name !in SKIPPED_DIRS && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension in TEXT && shipsInRelease(it.relativeTo(repo).invariantSeparatorsPath) }
            .filter { file ->
                file.readText().let {
                    ACTION in it || RECEIVER in it || PROVIDER in it || "class AdbLogReceiver" in it ||
                        "class AdbLogProvider" in it
                }
            }
            .map { it.relativeTo(repo).invariantSeparatorsPath }
            .toList()
        assertEquals(emptyList<String>(), naming)
        assertTrue(ACTION in debugManifest.readText())
        assertTrue(PROVIDER in debugManifest.readText())
    }

    @Test
    fun `only the staging build takes src-debug besides debug, its manifest and its code`() {
        val lines = File(repo, "app/build.gradle.kts").readLines()
        val start = lines.indexOfFirst { it.trim() == "sourceSets.named(\"staging\") {" }
        assertTrue("app/build.gradle.kts gives the staging build no src/debug", start >= 0)
        val end = (start until lines.size).first { lines[it].trim() == "}" }
        val block = lines.subList(start, end).joinToString("\n")
        assertTrue(block, "manifest.srcFile(\"src/debug/AndroidManifest.xml\")" in block)
        assertTrue(block, "kotlin.srcDir(\"src/debug/java\")" in block)
        val elsewhere = lines.withIndex()
            .filter { (index, line) -> index !in start..end && "src/debug" in line && !line.trim().startsWith("//") }
            .map { (index, line) -> "app/build.gradle.kts:${index + 1}: ${line.trim()}" }
        assertEquals(emptyList<String>(), elsewhere)
    }

    /** "app/src/main/..." ships in a release; "app/src/debug/...", "app/src/testDebug/..." and files outside src don't. */
    private fun shipsInRelease(path: String): Boolean {
        val set = path.split('/').zipWithNext().firstOrNull { (dir, _) -> dir == "src" }?.second ?: return false
        return set != "debug" && !set.endsWith("Debug") && !set.startsWith("test") && !set.startsWith("androidTest")
    }

    private companion object {
        val TEXT = setOf("xml", "kt", "java")
        val SKIPPED_DIRS = setOf("build", "node_modules")

        // Not AdbLogReceiver's constants: the class is in no release build.
        const val ACTION = "io.github.mbustos27.nixie.tv.debug.WRITE_LOG"
        const val RECEIVER = "io.github.mbustos27.nixie.tv.diagnostics.AdbLogReceiver"
        const val PROVIDER = "io.github.mbustos27.nixie.tv.diagnostics.AdbLogProvider"
    }
}
