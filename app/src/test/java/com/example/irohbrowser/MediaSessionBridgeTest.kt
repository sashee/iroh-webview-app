package com.example.irohbrowser

import android.media.session.PlaybackState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The media session bridge's messages: what a page may say it plays, the
 * buttons sent back, and what Android is shown.
 *
 * A page can post to the bridge without the script, so what is read has to
 * hold on its own.
 */
@RunWith(RobolectricTestRunner::class)
class MediaSessionBridgeTest {

    private val alpha = ProxyBinding("alpha", 21000)
    private val own = Origins.origin(alpha.label, alpha.port)

    private fun parse(message: String, from: String = own): MediaReport? = MediaSessionBridge.parse(message, from, alpha)

    private fun media(message: String): PageMedia = (parse(message) as MediaReport.Current).media

    private val song = """{"metadata": {"title": "Song", "artist": "Band", "album": "Album"}, "playing": true,
        "actions": ["play", "nexttrack", "seekto", "skipad", 7], "position": {"duration": 200.5, "position": 12.25, "rate": 1.5}}"""

    @Test
    fun `what the page plays is read, times in milliseconds`() {
        assertEquals(
            PageMedia(
                title = "Song",
                artist = "Band",
                album = "Album",
                playing = true,
                actions = setOf(MediaAction.Play, MediaAction.Next, MediaAction.SeekTo),
                positionMs = 12250,
                durationMs = 200500,
                speed = 1.5f,
            ),
            media(song),
        )
    }

    @Test
    fun `only the endpoint's own page is heard`() {
        assertNull(parse(song, from = Origins.origin("beta", alpha.port)))
        assertNull(parse(song, from = Origins.origin(alpha.label, alpha.port + 1)))
        assertNull(parse(song, from = "https://example.com"))
    }

    @Test
    fun `no metadata means nothing plays, and garbage means nothing at all`() {
        assertEquals(MediaReport.Nothing, parse("""{"metadata": null}"""))
        assertEquals(MediaReport.Nothing, parse("{}"))
        assertNull(parse("not json"))
        assertNull(parse("""{"metadata": "Song"}"""))
        assertNull(parse("[]"))
    }

    @Test
    fun `text is cut, and loses control characters`() {
        val long = "x".repeat(MediaSessionBridge.MAX_TEXT + 50)
        val read = media("""{"metadata": {"title": "$long", "artist": "Ba\nnd\u0007", "album": 42}}""")
        assertEquals(MediaSessionBridge.MAX_TEXT, read.title.length)
        assertEquals("Band", read.artist)
        assertEquals("", read.album)
    }

    @Test
    fun `times that are not times are unknown, and the position stays within the track`() {
        val read = media("""{"metadata": {"title": "Song"}, "position": {"duration": 10, "position": 99, "rate": 0}}""")
        assertEquals(10_000L, read.positionMs)
        assertEquals(1f, read.speed)
        val odd = media("""{"metadata": {"title": "Song"}, "position": {"duration": -1, "position": "12"}}""")
        assertNull(odd.durationMs)
        assertNull(odd.positionMs)
        val none = media("""{"metadata": {"title": "Song"}}""")
        assertEquals(false, none.playing)
        assertNull(none.positionMs)
        assertEquals(emptySet<MediaAction>(), none.actions)
    }

    @Test
    fun `a button goes back by the name the page knows it by`() {
        assertEquals("""{"action":"nexttrack"}""", MediaSessionBridge.command(MediaAction.Next))
        assertEquals("""{"action":"previoustrack"}""", MediaSessionBridge.command(MediaAction.Previous))
        val seek = JSONObject(MediaSessionBridge.command(MediaAction.SeekTo, 42_500))
        assertEquals("seekto", seek.getString("action"))
        assertEquals(42.5, seek.getDouble("seekTime"), 0.0)
        assertEquals(MediaAction.entries.toList(), MediaAction.entries.map { MediaAction.of(it.web) })
    }

    @Test
    fun `play and pause are always offered, the rest only with the page's handler`() {
        val always = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        val bare = MediaSessionBridge.sessionState(media("""{"metadata": {"title": "Song"}}"""))
        assertEquals(always, bare.actions)
        assertEquals(PlaybackState.STATE_PAUSED, bare.state)
        assertEquals(PlaybackState.PLAYBACK_POSITION_UNKNOWN, bare.positionMs)

        val full = MediaSessionBridge.sessionState(media(song))
        assertEquals(PlaybackState.STATE_PLAYING, full.state)
        assertEquals(12250L, full.positionMs)
        assertEquals(1.5f, full.speed)
        assertEquals(always or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SEEK_TO, full.actions)
    }

    @Test
    fun `seeking needs a duration to seek within`() {
        val state = MediaSessionBridge.sessionState(media("""{"metadata": {"title": "Live"}, "actions": ["seekto", "previoustrack", "stop", "seekbackward", "seekforward"]}"""))
        assertEquals(0L, state.actions and PlaybackState.ACTION_SEEK_TO)
        listOf(PlaybackState.ACTION_SKIP_TO_PREVIOUS, PlaybackState.ACTION_STOP, PlaybackState.ACTION_REWIND, PlaybackState.ACTION_FAST_FORWARD)
            .forEach { assertTrue(state.actions and it != 0L) }
    }
}
