package com.example.irohbrowser

import android.content.Intent
import android.webkit.WebView
import com.example.irohbrowser.testing.TestHarness
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val controllers = mutableListOf<ActivityController<MainActivity>>()

    private val installer get() = harness.mediaBridge
    private val media get() = harness.media

    @Before
    fun setUp() {
        harness = TestHarness()
        harness.install()
    }

    @After
    fun tearDown() {
        controllers.forEach { it.close() }
        AppContainer.install(null)
    }

    private fun launch(card: Intent = harness.lastCard()): MainActivity {
        val created = Robolectric.buildActivity(MainActivity::class.java, card).setup()
        controllers += created
        return created.get()
    }

    /** Close [activity]'s card, as back or the recent apps do. */
    private fun close(activity: MainActivity) {
        val controller = controllers.single { it.get() === activity }
        activity.finish()
        controller.pause().stop().destroy()
        controllers.remove(controller)
    }

    private fun MainActivity.label(): String = binding!!.label

    private fun MainActivity.origin(): String = binding!!.let { Origins.origin(it.label, it.port) }

    private fun report(title: String = "Song", playing: Boolean = true): String =
        JSONObject()
            .put("metadata", JSONObject().put("title", title))
            .put("playing", playing)
            .put("actions", org.json.JSONArray(listOf("nexttrack", "seekto")))
            .put("position", JSONObject().put("duration", 200).put("position", 3))
            .toString()

    @Test
    fun `the bridge is offered to each card's own origin`() {
        harness.seed("ticket-alpha", "ticket-beta")
        val alpha = launch(harness.cardOf("ticket-alpha")).origin()
        val beta = launch(harness.cardOf("ticket-beta")).origin()

        assertEquals(listOf("install:$alpha", "install:$beta"), installer.events)
    }

    @Test
    fun `a page that never reports shows nothing`() {
        harness.seed("ticket-alpha")
        launch()
        assertTrue(media.shown.isEmpty())
        assertNull(media.owner)
    }

    @Test
    fun `what a page plays is shown, and its buttons come back to it`() {
        harness.seed("ticket-alpha")
        launch()
        val toPage = installer.post(report())

        assertEquals(listOf("Song"), media.shown.map { it.title })
        assertEquals(harness.proxy.labelFor("ticket-alpha"), media.owner)

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
        assertNull(media.owner)
        assertEquals(1, media.ends)
    }

    @Test
    fun `a new page and closing the card each end it, and another card's do not`() {
        harness.seed("ticket-alpha", "ticket-beta")
        val alpha = launch(harness.cardOf("ticket-alpha"))
        val beta = launch(harness.cardOf("ticket-beta"))
        val alphaOrigin = alpha.origin()

        installer.post(report(), from = alphaOrigin)
        val webView = beta.findViewById<WebView>(R.id.web_view)
        shadowOf(webView).webViewClient.onPageStarted(webView, Origins.url(beta.label(), beta.binding!!.port), null)
        close(beta)
        assertEquals("another card's new page, and its closing, leave it", alpha.label(), media.owner)

        val own = alpha.findViewById<WebView>(R.id.web_view)
        shadowOf(own).webViewClient.onPageStarted(own, Origins.url(alpha.label(), alpha.binding!!.port), null)
        assertNull("a new page ends it", media.owner)

        installer.post(report(), from = alphaOrigin)
        close(alpha)
        assertNull("closing the card ends it", media.owner)
        assertEquals(2, media.ends)
    }

    @Test
    fun `a page that starts playing takes the session from another card's, and one that doesn't leaves it`() {
        harness.seed("ticket-alpha", "ticket-beta")
        val alpha = launch(harness.cardOf("ticket-alpha"))
        val beta = launch(harness.cardOf("ticket-beta"))
        val toAlpha = installer.post(report("One"), from = alpha.origin())

        installer.post(report("Paused", playing = false), from = beta.origin())
        assertEquals(alpha.label(), media.owner)
        media.pressButton(MediaAction.Next)
        assertEquals(listOf("""{"action":"nexttrack"}"""), toAlpha)

        val toBeta = installer.post(report("Two"), from = beta.origin())
        assertEquals(beta.label(), media.owner)
        media.pressButton(MediaAction.Next)
        assertEquals("the buttons are the new page's", listOf("""{"action":"nexttrack"}"""), toBeta)
        assertEquals(1, toAlpha.size)
    }

    @Test
    fun `back on the first page goes to the background while something plays, and closes the app otherwise`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        installer.post(report())
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        assertFalse("playing: the app stays", activity.isFinishing)
        assertEquals(activity.label(), media.owner)

        installer.post("""{"metadata": null}""")
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        assertTrue("nothing playing: back closes it, as before", activity.isFinishing)
    }

    @Test
    fun `back goes to the background only in the card that plays`() {
        harness.seed("ticket-alpha", "ticket-beta")
        val alpha = launch(harness.cardOf("ticket-alpha"))
        val beta = launch(harness.cardOf("ticket-beta"))
        installer.post(report(), from = alpha.origin())

        @Suppress("DEPRECATION")
        beta.onBackPressed()
        assertTrue("another card's music keeps nothing of this one", beta.isFinishing)
        assertFalse(alpha.isFinishing)
    }

    @Test
    fun `autoplay is off unless the endpoint allows it, and follows the switch in settings`() {
        harness.seed("ticket-alpha", "ticket-beta")
        harness.store.save(harness.store.load().setAutoplay(1, true))
        val alpha = launch(harness.cardOf("ticket-alpha"))
        val beta = launch(harness.cardOf("ticket-beta"))
        fun MainActivity.needsTap() = findViewById<WebView>(R.id.web_view).settings.mediaPlaybackRequiresUserGesture

        assertTrue("a tap is needed by default", alpha.needsTap())
        assertFalse("the endpoint allows it", beta.needsTap())

        alpha.showSettings()
        alpha.findViewById<android.view.View>(R.id.settings_content)
            .findViewWithTag<com.google.android.material.materialswitch.MaterialSwitch>("autoplay:0").isChecked = true
        assertFalse("this card's, at once", alpha.needsTap())

        alpha.findViewById<android.view.View>(R.id.settings_content)
            .findViewWithTag<com.google.android.material.materialswitch.MaterialSwitch>("autoplay:1").isChecked = false
        val betaController = controllers.single { it.get() === beta }
        betaController.pause().resume()
        assertTrue("another card's, when it is next shown", beta.needsTap())
    }
}
