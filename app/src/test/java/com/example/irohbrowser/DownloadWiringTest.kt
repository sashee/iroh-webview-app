package com.example.irohbrowser

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
    private var controller: ActivityController<MainActivity>? = null

    private val installer get() = harness.downloadBridge
    private val target get() = harness.downloads

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

    /** Answers come back through the main thread's queue: let it run. */
    private fun answered() = shadowOf(Looper.getMainLooper()).idle()

    private fun start(name: String, size: Int) {
        installer.post(JSONObject().put("id", 1).put("start", JSONObject().put("name", name).put("size", size)).toString())
        answered()
    }

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
    fun `switching endpoints, a new page and closing the app each discard a file half saved`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 0)
        val activity = launch()

        start("one", 4)
        activity.selectEndpoint(1)
        assertEquals("a switch discards it", listOf("one"), target.discarded)

        start("two", 4)
        val webView = activity.findViewById<WebView>(R.id.web_view)
        shadowOf(webView).webViewClient.onPageStarted(webView, Origins.url(activity.binding!!.label, activity.binding!!.port), null)
        assertEquals("a new page discards it", listOf("one", "two"), target.discarded)

        start("three", 4)
        controller!!.pause().stop().destroy()
        controller = null
        assertEquals("closing the app discards it", listOf("one", "two", "three"), target.discarded)
        assertTrue(target.saved.isEmpty())
    }
}
