package com.example.irohbrowser

import android.net.Uri

/**
 * Handing a download off to the system's download manager.
 *
 * The one wrinkle is the hostname. `DownloadManager` runs in another process and
 * resolves names through the system resolver, which knows nothing about
 * `*.localhost` — that is a Chromium special case, not a system one. So the URL
 * is rewritten to `127.0.0.1` on the same port, which every resolver handles and
 * which reaches the same proxy: the proxy never reads the `Host` header, so the
 * name it is asked for makes no difference to where the bytes come from.
 *
 * Rewriting the host does mean the request leaves the WebView's cookie jar
 * behind, so the caller has to carry the cookies over explicitly — see
 * [downloadRequest] in `MainActivity`.
 */
object Downloads {

    /**
     * The URL to hand to `DownloadManager` for a download the WebView reported.
     *
     * Returns null when the url is not one of ours: a download is triggered by
     * the page, and a page from an arbitrary peer should not be able to point
     * the system downloader at anything it likes.
     */
    fun systemUrl(url: String?, label: String, port: Int): String? {
        if (!Origins.isOwnOrigin(url, label, port)) return null
        val uri = Uri.parse(url)
        return uri.buildUpon().encodedAuthority("127.0.0.1:$port").build().toString()
    }

    /**
     * A filename for the saved file.
     *
     * `DownloadManager` will happily be told a name containing a path, so the
     * separators are stripped: a `Content-Disposition` comes from the server,
     * and this one is reachable by any peer whose ticket was pasted.
     */
    fun fileName(contentDisposition: String?, url: String?): String {
        val fromHeader = contentDisposition
            ?.let { FILENAME.find(it)?.groupValues?.getOrNull(1) }
            ?.trim('"', ' ')
        val fromUrl = url?.let { runCatching { Uri.parse(it).lastPathSegment }.getOrNull() }
        val candidate = fromHeader?.takeIf { it.isNotBlank() }
            ?: fromUrl?.takeIf { it.isNotBlank() }
        return cleanName(candidate)
    }

    /**
     * A name a page or a peer gave a file, made safe to save under: no folder
     * in it, no control characters, at most [MAX_NAME_BYTES] of UTF-8 with the
     * extension kept, and never empty.
     */
    fun cleanName(name: String?): String {
        val bare = (name ?: "")
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filterNot { it.isISOControl() }
            .trim()
        if (bare.isEmpty() || bare == "." || bare == "..") return "download"
        val dot = bare.lastIndexOf('.').takeIf { it > 0 && bare.length - it <= MAX_EXTENSION }
        val (stem, extension) = if (dot == null) bare to "" else bare.substring(0, dot) to bare.substring(dot)
        val room = MAX_NAME_BYTES - extension.utf8Size()
        // Whole code points only: a surrogate pair cut in two is not a name.
        val kept = stem.codePoints().toArray()
            .runningFold(0) { size, point -> size + String(Character.toChars(point)).utf8Size() }
            .indexOfLast { it <= room }
        return stem.offsetByCodePoints(0, kept).let { stem.substring(0, it) }.trimEnd() + extension
    }

    /**
     * Room for a name: Android's limit is 255 bytes, and Downloads makes a
     * taken name unique by adding " (1)".
     */
    private const val MAX_NAME_BYTES = 240

    /** The longest ending, dot included, kept as an extension when a name is cut. */
    private const val MAX_EXTENSION = 16

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

    private val FILENAME = Regex("""filename\*?=(?:UTF-8'')?([^;]+)""", RegexOption.IGNORE_CASE)
}
