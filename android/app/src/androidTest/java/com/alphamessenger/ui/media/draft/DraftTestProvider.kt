package com.alphamessenger.ui.media.draft

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * A provider for tests only. The real FileProvider always gives both name and size,
 * so it cannot show how the factory behaves when a provider withholds them, when the
 * MIME type is generic and when the file is gone.
 *
 * URI: content://com.alphamessenger.draft.test/<case>/<file name in cacheDir>
 * where case: full | noname | nosize | generic | gone
 */
class DraftTestProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun caseOf(uri: Uri): String = uri.pathSegments.getOrElse(0) { CASE_FULL }

    private fun fileOf(uri: Uri): File =
        File(requireNotNull(context).cacheDir, uri.pathSegments.drop(1).joinToString("/"))

    override fun getType(uri: Uri): String? = when (caseOf(uri)) {
        CASE_GENERIC -> "application/octet-stream"
        CASE_GONE -> null
        else -> mimeByExtension(fileOf(uri).name)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val case = caseOf(uri)
        if (case == CASE_GONE) return null
        val file = fileOf(uri)
        val columns = buildList {
            if (case != CASE_NO_NAME) add(OpenableColumns.DISPLAY_NAME)
            if (case != CASE_NO_SIZE) add(OpenableColumns.SIZE)
        }
        if (columns.isEmpty()) return MatrixCursor(emptyArray())
        val cursor = MatrixCursor(columns.toTypedArray())
        cursor.addRow(
            columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    else -> file.length()
                }
            }.toTypedArray()
        )
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (caseOf(uri) == CASE_GONE) throw java.io.FileNotFoundException("no such URI: $uri")
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    private fun mimeByExtension(name: String): String? =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    companion object {
        const val AUTHORITY = "com.alphamessenger.draft.test"
        const val CASE_FULL = "full"
        const val CASE_NO_NAME = "noname"
        const val CASE_NO_SIZE = "nosize"
        const val CASE_GENERIC = "generic"
        const val CASE_GONE = "gone"

        fun uriFor(case: String, file: File): Uri =
            Uri.parse("content://$AUTHORITY/$case/${file.name}")
    }
}
