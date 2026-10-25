package com.example.irohbrowser

import android.webkit.WebView
import com.example.irohbrowser.testing.TestHarness
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Where the activity offers the media session bridge, what a page's report
 * shows, the buttons that come back, and when it all ends. The playback
 * itself is faked (`FakeMediaPlayback`); a page that never reports, as every
 * page without a Media Session, changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
class MediaWiringTest {

    private lateinit var harness: TestHarness
    private var controller: ActivityController<MainActivity>? = null

    private val installer get() = harness.mediaBridge
    private val media get() = harness.media

    @Before
    fun setUp() {
        harness = TestHarness()
        harness.install()
    }

    @After
    fun tearDown() {
        controller?.close()
        AppContainer.install(null)
    }

    private fun launch(): MainActivity {
        val created = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller = created
        return created.get()
    }

    private fun MainActivity.origin(): String = binding!!.let { Origins.origin(it.label, it.port) }

    private fun report(title: String = "Song", playing: Boolean = true): String =
        JSONObject()
            .put("metadata", JSONObject().put("title", title))
            .put("playing", playing)
            .put("actions", org.json.JSONArray(listOf("nexttrack", "seekto")))
            .put("position", JSONObject().put("duration", 200).put("position", 3))
            .toString()

    @Test
    fun `the bridge is offered to the running endpoint's origin, and moves with a switch`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 0)
        val activity = launch()
        val alpha = activity.origin()
        activity.selectEndpoint(1)
        val beta = activity.origin()

        assertEquals(listOf("install:$alpha", "uninstall:$alpha", "install:$beta"), installer.events)
    }

    @Test
    fun `a page that never reports shows nothing`() {
        harness.seed("ticket-alpha")
        launch()
        assertTrue(media.shown.isEmpty())
        assertFalse(media.active)
    }

    @Test
    fun `what a page plays is shown, and its buttons come back to it`() {
        harness.seed("ticket-alpha")
        launch()
        val toPage = installer.post(report())

        assertEquals(listOf("Song"), media.shown.map { it.title })
        assertTrue(media.active)

        media.pressButton(MediaAction.Next)
        media.pressButton(MediaAction.SeekTo, 61_000)
        assertEquals(
            listOf("""{"action":"nexttrack"}""", """{"action":"seekto","seekTime":61}"""),
            toPage,
        )
    }

    @Test
    fun `another origin's report is ignored`() {
        harness.seed("ticket-alpha")
        launch()
        installer.post(report(), from = "http://other.localhost:1234")
        assertTrue(media.shown.isEmpty())
    }

    @Test
    fun `a page that stops reporting metadata ends it`() {
        harness.seed("ticket-alpha")
        launch()
        installer.post(report())
        installer.post("""{"metadata": null}""")
        assertFalse(media.active)
        assertEquals(1, media.ends)
    }

    @Test
    fun `switching endpoints, a new page and closing the app each end it`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 0)
        val activity = launch()

        installer.post(report())
        activity.selectEndpoint(1)
        assertFalse("a switch ends it", media.active)

        installer.post(report())
        val webView = activity.findViewById<WebView>(R.id.web_view)
        shadowOf(webView).webViewClient.onPageStarted(webView, Origins.url(activity.binding!!.label, activity.binding!!.port), null)
        assertFalse("a new page ends it", media.active)

        installer.post(report())
        controller!!.pause().stop().destroy()
        controller = null
        assertFalse("closing the app ends it", media.active)
        assertEquals(3, media.ends)
    }

    @Test
    fun `back on the first page goes to the background while something plays, and closes the app otherwise`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        installer.post(report())
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        assertFalse("playing: the app stays", activity.isFinishing)
        assertTrue(media.active)

        installer.post("""{"metadata": null}""")
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        assertTrue("nothing playing: back closes it, as before", activity.isFinishing)
    }

    @Test
    fun `autoplay is off unless the endpoint allows it, and follows the switch in settings`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 0)
        harness.store.save(harness.store.load().setAutoplay(1, true))
        val activity = launch()
        val settings = activity.findViewById<WebView>(R.id.web_view).settings

        assertTrue("a tap is needed by default", settings.mediaPlaybackRequiresUserGesture)
        activity.selectEndpoint(1)
        assertFalse("the endpoint allows it", settings.mediaPlaybackRequiresUserGesture)
        activity.selectEndpoint(0)
        assertTrue(settings.mediaPlaybackRequiresUserGesture)
    }
}
