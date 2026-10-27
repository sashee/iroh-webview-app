package com.example.irohbrowser

import com.example.irohbrowser.testing.FakeDownloadTarget
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The download bridge's side in the app: a page's file, saved piece by piece,
 * and every way it can end unsaved -- each of which must leave nothing behind.
 */
@RunWith(RobolectricTestRunner::class)
class PageDownloadsTest {

    private val site = ProxyBinding(label = "a1b2c3d4e5f60718", port = 24680)
    private val origin = Origins.origin(site.label, site.port)
    private val target = FakeDownloadTarget()
    private val events = mutableListOf<DownloadEvent>()

    /** Work for the io thread, run when the test says, to see what happens meanwhile. */
    private val held = ArrayDeque<Runnable>()

    private fun downloads(io: Executor = Executor { it.run() }) =
        PageDownloads(target, io, main = { it.run() }, tell = { events += it })

    /** Every answer the page got, in order. */
    private val replies = mutableListOf<JSONObject>()

    private fun PageDownloads.text(message: String, from: String = origin) =
        receive(message, from, site) { replies += JSONObject(it) }

    private fun PageDownloads.start(name: String, size: Long, id: Int = 1) =
        text(JSONObject().put("id", id).put("start", JSONObject().put("name", name).put("size", size)).toString())

    private fun PageDownloads.end(id: Int = 1) = text("""{"id":$id,"end":true}""")

    private fun PageDownloads.piece(bytes: ByteArray, from: String = origin) = receive(bytes, from, site)

    @Test
    fun `a file is written piece by piece, each answered, and saved`() {
        val downloads = downloads()
        downloads.start("song.mp3", 5)
        downloads.piece(byteArrayOf(1, 2, 3))
        downloads.piece(byteArrayOf(4, 5))
        downloads.end()

        assertEquals(listOf(0L, 3L, 5L), replies.take(3).map { it.getLong("received") })
        assertEquals("song.mp3", replies.last().getString("saved"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), target.saved.getValue("song.mp3"))
        assertEquals(listOf(DownloadEvent.Started("song.mp3"), DownloadEvent.Saved("song.mp3")), events)
    }

    @Test
    fun `the page is told the name the file was saved as`() {
        val downloads = downloads()
        repeat(2) {
            downloads.start("song.mp3", 1, id = it)
            downloads.piece(byteArrayOf(1))
            downloads.end(id = it)
        }
        assertEquals("song.mp3 (1)", replies.last().getString("saved"))
    }

    @Test
    fun `an empty file is saved`() {
        val downloads = downloads()
        downloads.start("empty.txt", 0)
        downloads.end()
        assertArrayEquals(byteArrayOf(), target.saved.getValue("empty.txt"))
    }

    @Test
    fun `the name is cleaned before anything is made`() {
        downloads().start("../../evil.sh", 1)
        assertTrue("evil.sh" in target.open)
    }

    @Test
    fun `a page of another origin is ignored`() {
        val downloads = downloads()
        downloads.text("""{"id":1,"start":{"name":"x","size":1}}""", from = "http://other.localhost:${site.port}")
        assertTrue(replies.isEmpty())
        assertTrue(target.open.isEmpty())

        downloads.start("x", 1)
        downloads.piece(byteArrayOf(9), from = "http://other.localhost:${site.port}")
        assertEquals(listOf(0L), replies.map { it.getLong("received") })
    }

    @Test
    fun `messages that make no sense are ignored`() {
        val downloads = downloads()
        listOf(
            "not json",
            """{"start":{"name":"x","size":1}}""",
            """{"id":-1,"start":{"name":"x","size":1}}""",
            """{"id":1,"start":{"name":"x"}}""",
            """{"id":1,"start":{"name":"x","size":-1}}""",
            """{"id":1,"start":{"name":"x","size":1.5}}""",
            """{"id":1,"end":"yes"}""",
        ).forEach { downloads.text(it) }
        assertTrue(replies.isEmpty())
        assertTrue(target.open.isEmpty())
    }

    @Test
    fun `a piece with no file being saved is dropped`() {
        downloads().piece(byteArrayOf(1))
        assertTrue(replies.isEmpty())
    }

    @Test
    fun `a second file while one is saved is refused, and the first goes on`() {
        val downloads = downloads()
        downloads.start("first", 1, id = 1)
        downloads.start("second", 1, id = 2)
        assertEquals(2, replies.last().getInt("id"))
        assertTrue(replies.last().has("error"))

        downloads.piece(byteArrayOf(1))
        downloads.end(id = 1)
        assertTrue("first" in target.saved)
        assertTrue("second" !in target.open)
    }

    @Test
    fun `more bytes than the file has end it unsaved`() {
        val downloads = downloads()
        downloads.start("x", 2)
        downloads.piece(byteArrayOf(1, 2, 3))
        assertTrue(replies.last().has("error"))
        assertEquals(listOf("x"), target.discarded)
        assertEquals(DownloadEvent.Failed("x"), events.last())
    }

    @Test
    fun `a piece larger than the bridge takes ends the file`() {
        val downloads = downloads()
        downloads.start("x", DownloadBridge.MAX_PIECE + 1L)
        downloads.piece(ByteArray(DownloadBridge.MAX_PIECE + 1))
        assertTrue(replies.last().has("error"))
        assertEquals(listOf("x"), target.discarded)
    }

    @Test
    fun `a piece sent before the last was written ends the file`() {
        // One piece in flight at most: otherwise a page could fill the app's
        // memory faster than the disk empties it.
        val downloads = downloads(io = { held.addLast(it) })
        downloads.start("x", 4)
        held.removeFirst().run()
        downloads.piece(byteArrayOf(1, 2))
        downloads.piece(byteArrayOf(3, 4))
        while (held.isNotEmpty()) held.removeFirst().run()

        assertTrue(replies.last().has("error"))
        assertEquals(listOf("x"), target.discarded)
    }

    @Test
    fun `an end before the whole file came discards it`() {
        val downloads = downloads()
        downloads.start("x", 3)
        downloads.piece(byteArrayOf(1))
        downloads.end()
        assertTrue(replies.last().has("error"))
        assertEquals(listOf("x"), target.discarded)
        assertTrue(target.saved.isEmpty())
    }

    @Test
    fun `a page that cannot read on discards its file, unanswered`() {
        val downloads = downloads()
        downloads.start("x", 3)
        downloads.text("""{"id":1,"abort":true}""")
        assertEquals(1, replies.size)
        assertEquals(listOf("x"), target.discarded)
    }

    @Test
    fun `a page that goes away discards its file`() {
        val downloads = downloads()
        downloads.start("x", 3)
        downloads.piece(byteArrayOf(1))
        downloads.cancel()
        assertEquals(listOf("x"), target.discarded)
        assertEquals(DownloadEvent.Failed("x"), events.last())

        // And the next page can save again.
        downloads.start("y", 0, id = 2)
        downloads.end(id = 2)
        assertTrue("y" in target.saved)
    }

    @Test
    fun `a cancel while the file is being made still discards it`() {
        val downloads = downloads(io = { held.addLast(it) })
        downloads.start("x", 3)
        downloads.cancel()
        while (held.isNotEmpty()) held.removeFirst().run()

        assertEquals(listOf("x"), target.discarded)
        assertTrue(replies.isEmpty())
    }

    @Test
    fun `a file that cannot be made is refused`() {
        target.refuseToCreate = true
        downloads().start("x", 3)
        assertTrue(replies.single().has("error"))
        assertEquals(DownloadEvent.Failed("x"), events.last())
    }

    @Test
    fun `a write that fails ends the file unsaved`() {
        val downloads = downloads()
        downloads.start("x", 3)
        target.failWrites = true
        downloads.piece(byteArrayOf(1))
        assertTrue(replies.last().has("error"))
        assertEquals(listOf("x"), target.discarded)
        assertNull(target.saved["x"])
    }
}
