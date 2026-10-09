package com.example.irohbrowser

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.irohbrowser.testing.TestHarness
import com.example.irohbrowser.testing.request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * The WebView never sends a request to a loopback port the proxy is not
 * holding.
 *
 * Cookies are keyed by host, not port, and any app on the device can listen on
 * a free loopback port. So a request from this WebView to `<label>.localhost`
 * on the wrong port hands that endpoint's session to whoever is listening
 * there. History and restored pages are how the WebView could be sent there,
 * which is why the check is on every request rather than on link clicks.
 */
@RunWith(RobolectricTestRunner::class)
class LoopbackGuardTest {

    private lateinit var harness: TestHarness
    private val controllers = mutableListOf<ActivityController<MainActivity>>()

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

    private fun launch(saved: Bundle? = null): ActivityController<MainActivity> {
        val builder = Robolectric.buildActivity(MainActivity::class.java)
        return (if (saved == null) builder.setup() else builder.setup(saved)).also { controllers += it }
    }

    private fun MainActivity.webView(): WebView = findViewById(R.id.web_view)
    private fun MainActivity.client(): WebViewClient = shadowOf(webView()).webViewClient
    private fun MainActivity.origin(): String = binding!!.let { Origins.origin(it.label, it.port) }

    private fun MainActivity.intercept(url: String) = client().shouldInterceptRequest(webView(), request(url))

    // --- the requests themselves ---

    @Test
    fun `requests to the running proxy go through`() {
        harness.seed("ticket-alpha")
        val activity = launch().get()
        assertNull(activity.intercept(activity.origin() + "/api/me"))
    }

    @Test
    fun `the same host on another port is refused before anything is sent`() {
        harness.seed("ticket-alpha")
        val activity = launch().get()
        val binding = activity.binding!!

        val refusal = activity.intercept(Origins.origin(binding.label, binding.port + 1) + "/")

        assertNotNull(refusal)
        assertEquals(403, refusal!!.statusCode)
        assertEquals("no-store", refusal.responseHeaders["Cache-Control"])
    }

    @Test
    fun `after a switch, the previous endpoint's origin is refused`() {
        // Its proxy has stopped, so its port is free for anyone to take.
        harness.seed("ticket-alpha", "ticket-beta", selected = 0)
        val activity = launch().get()
        val alpha = activity.origin()

        activity.selectEndpoint(1)

        assertNotNull(activity.intercept("$alpha/"))
        assertNull(activity.intercept(activity.origin() + "/"))
    }

    @Test
    fun `other loopback addresses are refused`() {
        harness.seed("ticket-alpha")
        val activity = launch().get()
        val port = activity.binding!!.port

        assertNotNull(activity.intercept("http://127.0.0.1:$port/"))
        assertNotNull(activity.intercept("http://localhost:$port/"))
    }

    @Test
    fun `with nothing running, every loopback request is refused`() {
        harness.proxy.failures["ticket-alpha"] = ProxyError.StartFailed
        harness.seed("ticket-alpha")
        val activity = launch().get()
        assertNotNull(activity.intercept(Origins.origin(harness.proxy.labelFor("ticket-alpha"), 40000) + "/"))
    }

    @Test
    fun `service worker requests are checked the same way`() {
        // They bypass the WebViewClient entirely.
        harness.seed("ticket-alpha")
        val activity = launch().get()
        val check = harness.serviceWorkers.check!!
        val binding = activity.binding!!

        assertNull(check(activity.origin() + "/sw.js"))
        assertNotNull(check(Origins.origin(binding.label, binding.port + 1) + "/"))
    }

    @Test
    fun `the service worker route is removed with the activity`() {
        harness.seed("ticket-alpha")
        val controller = launch()
        controller.pause().stop().destroy()
        controllers.remove(controller)
        assertNull(harness.serviceWorkers.check)
    }

    // --- history after a switch ---

    @Test
    fun `switching drops the history once the new endpoint's page has loaded`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 0)
        val activity = launch().get()
        val alpha = activity.origin()
        activity.selectEndpoint(1)
        val beta = activity.origin()

        // A late callback for the page being left must not count.
        activity.client().onPageFinished(activity.webView(), "$alpha/")
        assertFalse(shadowOf(activity.webView()).wasClearHistoryCalled())

        activity.client().onPageFinished(activity.webView(), "$beta/")
        assertTrue(shadowOf(activity.webView()).wasClearHistoryCalled())
    }

    // --- restoring after the process was killed ---

    private fun savedAfterBrowsingTo(path: String): Bundle {
        val first = launch()
        val activity = first.get()
        // Robolectric's WebView keeps no history of its own.
        shadowOf(activity.webView()).pushEntryToHistory(activity.origin() + "/")
        shadowOf(activity.webView()).pushEntryToHistory(activity.origin() + path)
        return Bundle().also { first.saveInstanceState(it) }
    }

    @Test
    fun `a restored page starts loading only after the proxy runs and the passkey script is in`() {
        // The WebView loads a restored page at once, on its own thread, and the
        // request is checked against the running proxy. Restored first, it was
        // refused: on a Pixel the page came back as the refusal. And a page
        // restored before the passkey script was installed would not get it.
        harness.proxy.bindPreferredPorts = true
        harness.seed("ticket-alpha")
        val saved = savedAfterBrowsingTo("/deep/page")
        val builder = Robolectric.buildActivity(MainActivity::class.java)
        fun history() = builder.get().findViewById<WebView>(R.id.web_view).copyBackForwardList().size
        val seen = mutableListOf<String>()
        harness.proxy.onStart = { seen += "start with ${history()} pages" }
        harness.passkeys.installer.onInstall = { seen += "install with ${history()} pages" }

        controllers += builder.setup(saved)

        assertEquals(listOf("start with 0 pages", "install with 0 pages"), seen)
        assertEquals(2, history())
    }

    @Test
    fun `a restored page is kept when the proxy got its port back`() {
        harness.proxy.bindPreferredPorts = true
        harness.seed("ticket-alpha")
        val saved = savedAfterBrowsingTo("/deep/page")

        val restored = launch(saved).get()

        assertEquals(restored.origin() + "/deep/page", shadowOf(restored.webView()).lastLoadedUrl)
        restored.client().onPageFinished(restored.webView(), restored.origin() + "/deep/page")
        assertFalse(shadowOf(restored.webView()).wasClearHistoryCalled())
    }

    @Test
    fun `a restored page on a port the proxy no longer holds is replaced by the front page`() {
        // The fake hands every start a new port: the fallback taken when
        // something else holds the port the page was saved on.
        harness.seed("ticket-alpha")
        val saved = savedAfterBrowsingTo("/deep/page")

        val restored = launch(saved).get()

        val front = Origins.url(restored.binding!!.label, restored.binding!!.port)
        assertEquals(front, shadowOf(restored.webView()).lastLoadedUrl)
        restored.client().onPageFinished(restored.webView(), front)
        assertTrue(shadowOf(restored.webView()).wasClearHistoryCalled())
    }
}
