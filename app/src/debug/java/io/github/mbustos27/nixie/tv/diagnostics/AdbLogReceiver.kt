// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.diagnostics.Diagnostics
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug and staging builds only (src/debug, which the staging build also takes; never a release or store build): the
 * log for the PC without a step on the TV. `tools/pull-tv-log.ps1` sends [ACTION] over adb; this writes the report
 * Download log serves ([Diagnostics.fullReport]: the device summary, then the log with server addresses and names, user
 * names and tokens replaced) to [PATH] in the app's own storage, which the script reads through [AdbLogProvider] and
 * then deletes. Nothing leaves the box by itself: no network, no upload. Only a sender holding DUMP (the shell, the
 * system) reaches the receiver (src/debug/AndroidManifest.xml).
 */
class AdbLogReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun diagnostics(): Diagnostics
    }

    @Suppress("TooGenericExceptionCaught") // Any failure is a line in the log; the script says no file came.
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val diagnostics = EntryPointAccessors.fromApplication<Dependencies>(app).diagnostics()
                // A background broadcast may run a minute; the script waits for the file, not for this.
                withTimeoutOrNull(WRITE_LIMIT_MS) { write(app.filesDir, diagnostics) }
                    ?: Log.w(TAG, "Writing the log for adb took too long")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Writing the log for adb failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "io.github.mbustos27.nixie.tv.debug.WRITE_LOG"

        /** Under the app's files directory; the script reads `files/diagnostics/nixie-tv-log.txt`. */
        const val PATH = "diagnostics/nixie-tv-log.txt"

        private const val TAG = "AdbLog"
        private const val WRITE_LIMIT_MS = 50_000L

        /**
         * Writes the report to [PATH] under [filesDir] in one piece (a whole file appears or none: the script takes
         * the file as soon as it is there) and returns it. As after Download log, the last crash counts as reported.
         */
        suspend fun write(filesDir: File, diagnostics: Diagnostics): File {
            val bytes = diagnostics.fullReport().encodeToByteArray()
            val file = File(filesDir, PATH)
            val dir = checkNotNull(file.parentFile)
            dir.mkdirs()
            val part = File(dir, "${file.name}.part")
            part.writeBytes(bytes)
            Files.move(
                part.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            Log.crashReported()
            Log.i(TAG, "Log written for adb: ${bytes.size} bytes")
            return file
        }
    }
}
