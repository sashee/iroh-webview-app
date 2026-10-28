package com.example.irohbrowser

import android.content.Intent
import android.os.Looper
import android.webkit.WebView
import com.example.irohbrowser.testing.TestHarness
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Where the activity offers the download bridge, and what becomes of a file
 * a page saves: kept once it is whole, discarded when the page or its
 * endpoint goes first.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadWiringTest {

    private lateinit var harness: TestHarness
    private val controllers = mutableListOf<ActivityController<MainActivity>>()

    private val installer get() = harness.downloadBridge
    private val target get() = harness.downloads

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

    private fun MainActivity.origin(): String = binding!!.let { Origins.origin(it.label, it.port) }

    /** Answers come back through the main thread's queue: let it run. */
    private fun answered() = shadowOf(Looper.getMainLooper()).idle()

    private fun start(name: String, size: Int) {
        installer.post(JSONObject().put("id", 1).put("start", JSONObject().put("name", name).put("size", size)).toString())
        answered()
    }

    @Test
    fun `each card offers the bridge to its own origin`() {
        harness.seed("ticket-alpha", "ticket-beta")
        val alpha = launch(harness.cardOf("ticket-alpha")).origin()
        val beta = launch(harness.cardOf("ticket-beta")).origin()

        assertEquals(listOf("install:$alpha", "install:$beta"), installer.events)
    }

    @Test
    fun `a page's file is saved in Downloads`() {
        harness.seed("ticket-alpha")
        launch()

        start("song.mp3", 4)
        installer.postBytes(byteArrayOf(1, 2))
        answered()
        installer.postBytes(byteArrayOf(3, 4))
        answered()
        installer.post("""{"id":1,"end":true}""")
        answered()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), target.saved.getValue("song.mp3"))
    }

    @Test
    fun `a new page, closing the card and removing its endpoint each discard a file half saved`() {
        harness.seed("ticket-alpha", "ticket-beta")
        val activity = launch(harness.cardOf("ticket-alpha"))

        start("one", 4)
        val webView = activity.findViewById<WebView>(R.id.web_view)
        shadowOf(webView).webViewClient.onPageStarted(webView, Origins.url(activity.binding!!.label, activity.binding!!.port), null)
        assertEquals("a new page discards it", listOf("one"), target.discarded)

        start("two", 4)
        close(activity)
        assertEquals("closing the card discards it", listOf("one", "two"), target.discarded)

        val beta = launch(harness.cardOf("ticket-beta"))
        start("three", 4)
        beta.removeEndpoint(1)
        assertEquals("removing its endpoint discards it", listOf("one", "two", "three"), target.discarded)
        assertTrue(target.saved.isEmpty())
    }
}
