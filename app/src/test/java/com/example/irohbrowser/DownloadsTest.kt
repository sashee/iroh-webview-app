package com.example.irohbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Handing a download to the system downloader.
 *
 * Two things here have teeth: the host rewrite, without which the download
 * silently fails because nothing outside Chromium resolves `*.localhost`, and
 * the filename, which comes from a header a peer controls.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadsTest {

    private val label = "a1b2c3d4e5f60718"
    private val port = 24680

    @Test
    fun `our own url is rewritten to loopback on the same port`() {
        assertEquals(
            "http://127.0.0.1:$port/export.csv",
            Downloads.systemUrl("http://$label.localhost:$port/export.csv", label, port),
        )
    }

    @Test
    fun `the path and query survive the rewrite`() {
        assertEquals(
            "http://127.0.0.1:$port/a/b/c.csv?from=1&to=2",
            Downloads.systemUrl("http://$label.localhost:$port/a/b/c.csv?from=1&to=2", label, port),
        )
    }

    @Test
    fun `a url that is not ours is refused`() {
        // The download is triggered by the page. A page from an arbitrary peer
        // must not be able to point the system downloader wherever it likes.
        assertNull(Downloads.systemUrl("https://example.com/payload.exe", label, port))
        assertNull(Downloads.systemUrl("http://other.localhost:$port/x", label, port))
        assertNull(Downloads.systemUrl("file:///etc/hosts", label, port))
        assertNull(Downloads.systemUrl(null, label, port))
    }

    @Test
    fun `the filename comes from the content disposition`() {
        assertEquals(
            "report.csv",
            Downloads.fileName("attachment; filename=\"report.csv\"", null),
        )
    }

    @Test
    fun `an extended filename is understood`() {
        assertEquals(
            "report.csv",
            Downloads.fileName("attachment; filename*=UTF-8''report.csv", null),
        )
    }

    @Test
    fun `the url supplies a name when the header does not`() {
        assertEquals(
            "metrics.json",
            Downloads.fileName(null, "http://$label.localhost:$port/data/metrics.json"),
        )
    }

    @Test
    fun `a path in the filename is stripped`() {
        // DownloadManager will accept a name with separators in it, and the
        // header is written by whichever peer's ticket was pasted.
        assertEquals(
            "passwd",
            Downloads.fileName("attachment; filename=\"../../../../etc/passwd\"", null),
        )
        assertEquals(
            "evil.sh",
            Downloads.fileName("attachment; filename=\"..\\\\..\\\\evil.sh\"", null),
        )
    }

    @Test
    fun `there is always a name`() {
        assertEquals("download", Downloads.fileName(null, null))
        assertEquals("download", Downloads.fileName("attachment", null))
        assertEquals("download", Downloads.fileName("attachment; filename=\"\"", null))
        assertEquals("download", Downloads.fileName(null, "http://$label.localhost:$port/"))
    }
}
