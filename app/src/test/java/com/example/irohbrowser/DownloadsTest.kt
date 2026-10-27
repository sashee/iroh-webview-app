package com.example.irohbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `a name a page gives is saved without a folder or control characters`() {
        // The download bridge's names come straight from the page.
        assertEquals("evil.sh", Downloads.cleanName("../../evil.sh"))
        assertEquals("evil.sh", Downloads.cleanName("..\\..\\evil.sh"))
        assertEquals("ab.mp3", Downloads.cleanName(" a\nb\u0000.mp3 "))
        assertEquals("01 Säntis – Ünïcødé.flac", Downloads.cleanName("01 Säntis – Ünïcødé.flac"))
    }

    @Test
    fun `a name that is no name becomes download`() {
        listOf(null, "", "   ", ".", "..", "a/", "\u0007").forEach {
            assertEquals("download", Downloads.cleanName(it))
        }
    }

    @Test
    fun `a long name is cut, keeping its extension and whole characters`() {
        val cut = Downloads.cleanName("ü".repeat(300) + ".mp3")
        assertTrue(cut.endsWith("ü.mp3"))
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= 240)

        // Four bytes each, as two chars: never split.
        val emoji = Downloads.cleanName("😀".repeat(100) + ".txt")
        assertTrue(emoji.removeSuffix(".txt").codePoints().allMatch { it == "😀".codePointAt(0) })
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= 240)

        // No extension to keep: an ending too long to be one is cut with the rest.
        assertEquals(240, Downloads.cleanName("a".repeat(150) + "." + "b".repeat(150)).length)
    }
}
