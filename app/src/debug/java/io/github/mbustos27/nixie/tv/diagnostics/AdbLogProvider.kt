// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Debug and staging builds only (src/debug, which the staging build also takes; never a release or store build): hands
 * `tools/pull-tv-log.ps1` the file [AdbLogReceiver] wrote, through adb's `content` command. `run-as` reads the app's
 * storage only on a debuggable build, and the release-like staging build is not one. Only a caller holding DUMP (the
 * shell, the system) reaches it (src/debug/AndroidManifest.xml); it serves that one file and nothing else.
 *
 * `content query --uri content://<package>.adblog/log` gives the file's name and size (no row while there is none),
 * `content read` its bytes and `content delete` removes it (and a `.part` a cut-off write left).
 */
class AdbLogProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun log(): File = File(checkNotNull(context).filesDir, AdbLogReceiver.PATH)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        val file = log()
        if (file.isFile) cursor.addRow(arrayOf<Any>(file.name, file.length()))
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("The log is read only")
        val file = log()
        if (!file.isFile) throw FileNotFoundException("No log written for adb")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val file = log()
        return listOf(file, File(file.path + ".part")).count { it.delete() }
    }

    override fun getType(uri: Uri): String = "text/plain"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** The provider's authority after the application id: `content://<package>.adblog/log`. */
        const val AUTHORITY_SUFFIX = ".adblog"
    }
}
