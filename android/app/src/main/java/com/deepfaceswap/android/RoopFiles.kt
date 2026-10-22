package com.deepfaceswap.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Minimal file provider.
 *
 * Android refuses to hand a `file://` URI to another app, and adding AndroidX just
 * for `FileProvider` would break this project's no-dependency rule. This serves
 * finished results out of the app's own output directory and nothing else: the
 * path is resolved against a fixed root, so a crafted URI cannot escape it.
 */
class RoopFiles : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = resolve(uri) ?: return null
        if (!file.isFile) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        val name = resolve(uri)?.name ?: return "application/octet-stream"
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "gif" -> "image/gif"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolve(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(file?.name ?: "")
                OpenableColumns.SIZE -> row.add(file?.length() ?: 0L)
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0

    /** Resolve a content URI against the fixed output root. */
    private fun resolve(uri: Uri): File? {
        val ctx = context ?: return null
        val root = File(ctx.getExternalFilesDir(null), "outputs")
        val name = uri.lastPathSegment ?: return null
        // Reject anything that is not a plain file name inside the root.
        if (name.contains('/') || name.contains("..")) return null
        return File(root, name)
    }

    companion object {
        const val AUTHORITY = "com.deepfaceswap.android.files"

        fun uriFor(context: android.content.Context, file: File): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(file.name).build()
    }
}
