package com.example.irohbrowser

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap

/**
 * Files saved into the shared Downloads folder, as the system downloader saves
 * them: no permission needed (scoped storage), and listed in the Files app.
 *
 * A file is pending while it is written, so nothing sees half of it, and is
 * deleted if it is never finished. One left behind by a crash stays hidden,
 * and expires: the system's maintenance deletes old pending files.
 */
class MediaStoreDownloads(private val resolver: ContentResolver) : DownloadTarget {

    override fun create(name: String): DownloadFile? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, typeOf(name))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val out = runCatching { resolver.openOutputStream(uri) }.getOrNull()
        if (out == null) {
            resolver.delete(uri, null, null)
            return null
        }
        return object : DownloadFile {
            override fun write(bytes: ByteArray) = out.write(bytes)

            override fun finish(): String {
                out.close()
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return nameOf(uri) ?: name
            }

            override fun discard() {
                runCatching { out.close() }
                resolver.delete(uri, null, null)
            }
        }
    }

    /** The name the file was given: Downloads numbers a name that is taken. */
    private fun nameOf(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    /**
     * The type its extension says. Downloads gives a file whose type and
     * extension disagree a second extension, so the page's own type is not
     * used: the name is what the user sees.
     */
    private fun typeOf(name: String): String =
        name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
}
