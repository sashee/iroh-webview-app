package com.example.irohbrowser

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.ProxyConfig
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
 * Nothing the WebView does reaches the network except through the tunnel.
 *
 * The page comes from an arbitrary peer, and whatever it names on another
 * host would otherwise be fetched straight from the internet. See
 * [Confinement] for the three layers; the proxy override and the script are
 * the WebView's to enforce, so what is checked here is that they are what
 * they should be and are in place before anything loads.
 */
@RunWith(RobolectricTestRunner::class)
class ConfinementTest {

    private lateinit var harness: TestHarness
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        harness = TestHarness()
        harness.install()
        harness.seed("ticket-alpha")
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

    private fun MainActivity.webView(): WebView = findViewById(R.id.web_view)

    private fun MainActivity.intercept(url: String) =
        shadowOf(webView()).webViewClient.shouldInterceptRequest(webView(), request(url))

    // --- the request check ---

    @Test
    fun `a request off the device is refused before anything is sent`() {
        val activity = launch()
        listOf(
            "https://example.com/style.css",
            "https://fonts.example.com/font.woff2",
            "http://192.168.1.1/",
        ).forEach {
            val refusal = activity.intercept(it)
            assertNotNull(it, refusal)
            assertEquals(403, refusal!!.statusCode)
        }
    }

    @Test
    fun `the endpoint's own pages, and what the page made itself, go through`() {
        val activity = launch()
        val binding = activity.binding!!
        assertNull(activity.intercept(Origins.origin(binding.label, binding.port) + "/app.js"))
        assertNull(activity.intercept("data:image/gif;base64,R0lGODlhAQABAAAAACw="))
    }

    @Test
    fun `a service worker cannot reach off the device either`() {
        launch()
        assertNotNull(harness.serviceWorkers.check!!("https://example.com/sw-import.js"))
    }

    // --- the proxy override ---

    @Test
    fun `everything but the endpoints' hosts goes to a proxy that is not there`() {
        val config = Confinement.proxyConfig()

        val rule = config.proxyRules.single()
        assertEquals(Confinement.NOWHERE, rule.url)
        assertEquals(ProxyConfig.MATCH_ALL_SCHEMES, rule.schemeFilter)
        assertFalse(config.isReverseBypassEnabled)
    }

    @Test
    fun `only localhost names bypass it, and the order is what makes them`() {
        // `<-loopback>` drops Chromium's own exceptions for loopback and
        // link-local. Later rules override earlier ones, so `*.localhost` has
        // to come after it, or the tunnel itself goes to the dead proxy.
        assertEquals(listOf("<-loopback>", "*.localhost"), Confinement.proxyConfig().bypassRules)
    }

    @Test
    fun `the dead proxy is on a port no app can listen on`() {
        val (host, port) = Confinement.NOWHERE.split(":")
        assertEquals("127.0.0.1", host)
        assertTrue(port.toInt() in 1 until 1024)
    }

    // --- WebRTC ---

    @Test
    fun `the script that removes webrtc is in before the first page loads`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val script = context.assets.open(Confinement.SCRIPT_ASSET).bufferedReader().use { it.readText() }
        var installedAtStart: List<String>? = null
        harness.proxy.onStart = { installedAtStart = harness.pageScripts.installed.toList() }

        launch()

        assertEquals(listOf(script), installedAtStart)
        assertEquals(listOf(script), harness.pageScripts.installed)
    }
}
