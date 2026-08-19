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
            ?: "download"
        return candidate.substringAfterLast('/').substringAfterLast('\\').ifBlank { "download" }
    }

    private val FILENAME = Regex("""filename\*?=(?:UTF-8'')?([^;]+)""", RegexOption.IGNORE_CASE)
}
