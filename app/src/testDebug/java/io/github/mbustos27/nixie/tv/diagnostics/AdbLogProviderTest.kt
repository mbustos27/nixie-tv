// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import android.app.Application
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Integration 1: the release-like staging build is not debuggable, so `run-as` can't read the log AdbLogReceiver wrote;
 * AdbLogProvider hands it to adb's `content` command instead (tools/pull-tv-log.ps1). It serves that one file, read
 * only: its size while there is one, its bytes, and its removal with the `.part` a cut-off write left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdbLogProviderTest {

    private val app = RuntimeEnvironment.getApplication()
    private val authority = app.packageName + AdbLogProvider.AUTHORITY_SUFFIX
    private val uri = Uri.parse("content://$authority/log")
    private val provider = Robolectric.buildContentProvider(AdbLogProvider::class.java).create(authority).get()
    private val log = File(app.filesDir, AdbLogReceiver.PATH)

    @Test
    fun `the script reads the written log's size and bytes, then removes it`() {
        val bytes = "Nixie for TV 0.1.1\nline two\n".encodeToByteArray()
        log.parentFile?.mkdirs()
        log.writeBytes(bytes)
        File(log.path + ".part").writeBytes(byteArrayOf(1))

        provider.query(uri, null, null, null, null).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("nixie-tv-log.txt", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(bytes.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        val read = ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { it.readBytes() }
        assertArrayEquals(bytes, read)

        assertEquals(2, provider.delete(uri, null, null))
        assertFalse(log.exists())
        assertFalse(File(log.path + ".part").exists())
    }

    @Test
    fun `with no log written there is no row and nothing to read`() {
        log.delete()
        provider.query(uri, null, null, null, null).use { assertEquals(0, it.count) }
        assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "r") }
    }

    @Test
    fun `the log is read only`() {
        log.parentFile?.mkdirs()
        log.writeText("log")
        assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "w") }
        assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "rw") }
        assertEquals(null, provider.insert(uri, null))
        assertEquals(0, provider.update(uri, null, null, null))
        assertEquals("log", log.readText())
    }
}
