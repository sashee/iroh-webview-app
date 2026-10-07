package com.example.irohbrowser

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.example.irohbrowser.testing.TestHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * What the activity does, driven through a fake proxy.
 *
 * The acceptance list in the handover document is mostly here: switching
 * endpoints must not carry a session across, external links must leave the
 * WebView, and cookies must be flushed before the app can be killed.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityBehaviorTest {

    private lateinit var harness: TestHarness
    private var controller: ActivityController<MainActivity>? = null

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

    private fun MainActivity.webView() = findViewById<WebView>(R.id.web_view)
    private fun MainActivity.entry() = findViewById<LinearLayout>(R.id.entry)
    private fun MainActivity.entryError() = findViewById<TextView>(R.id.entry_error)
    private fun MainActivity.loadedUrl() = shadowOf(webView()).lastLoadedUrl

    @Test
    fun `first run shows the ticket field and starts nothing`() {
        val activity = launch()

        assertEquals(View.VISIBLE, activity.entry().visibility)
        assertEquals(View.GONE, activity.webView().visibility)
        assertTrue(harness.proxy.startedTickets.isEmpty())
        assertNull(activity.binding)
    }

    @Test
    fun `connecting starts the proxy and loads its origin`() {
        val activity = launch()
        activity.findViewById<EditText>(R.id.ticket_field).setText("ticket-alpha")
        activity.findViewById<Button>(R.id.connect_button).performClick()

        assertEquals(listOf("ticket-alpha"), harness.proxy.startedTickets)
        val binding = activity.binding!!
        assertEquals(Origins.url(binding.label, binding.port), activity.loadedUrl())
        assertEquals(View.VISIBLE, activity.webView().visibility)
        assertEquals(View.GONE, activity.entry().visibility)
    }

    @Test
    fun `a pasted ticket is trimmed before it is saved`() {
        // Tickets arrive by copy-paste and often bring a newline with them.
        val activity = launch()
        activity.addEndpoint("  ticket-alpha\n ")

        assertEquals(listOf("ticket-alpha"), harness.proxy.startedTickets)
        assertEquals("ticket-alpha", activity.endpoints.selected?.ticket)
    }

    @Test
    fun `an empty ticket is refused without starting anything`() {
        val activity = launch()
        activity.addEndpoint("   ")

        assertTrue(harness.proxy.startedTickets.isEmpty())
        assertEquals(View.VISIBLE, activity.entryError().visibility)
    }

    @Test
    fun `a saved endpoint is reopened on the next launch`() {
        harness.seed("ticket-alpha")
        val activity = launch()

        assertEquals(listOf("ticket-alpha"), harness.proxy.startedTickets)
        assertEquals(View.VISIBLE, activity.webView().visibility)
    }

    @Test
    fun `switching endpoints stops the old proxy before starting the new one`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 0)
        val activity = launch()
        harness.proxy.calls.clear()

        activity.selectEndpoint(1)

        // The order is the point: a proxy left running would keep serving the
        // previous endpoint on a port the WebView may still hold open.
        assertEquals(listOf("stop", "start:ticket-beta"), harness.proxy.calls)
    }

    @Test
    fun `relaunching keeps the session`() {
        // The regression this file exists for: clearing used to happen on every
        // open, including a cold start, so every relaunch asked for the password
        // again. Nothing may be cleared just by starting up.
        harness.seed("ticket-alpha")

        launch()

        assertEquals(emptyList<String>(), harness.siteData.cleared)
    }

    @Test
    fun `switching endpoints keeps both sessions`() {
        // Each endpoint has its own `<label>.localhost` origin, so the browser
        // stores them separately; clearing would only throw away a session the
        // user still wants.
        harness.seed("ticket-alpha", "ticket-beta", selected = 0)
        val activity = launch()

        activity.selectEndpoint(1)
        activity.selectEndpoint(0)

        assertEquals(emptyList<String>(), harness.siteData.cleared)
    }

    @Test
    fun `adding an endpoint clears nothing`() {
        val activity = launch()
        activity.addEndpoint("ticket-alpha")

        assertEquals(emptyList<String>(), harness.siteData.cleared)
    }

    @Test
    fun `each endpoint gets its own origin`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 0)
        val activity = launch()
        val first = activity.loadedUrl()

        activity.selectEndpoint(1)
        val second = activity.loadedUrl()

        assertNotEquals(first, second)
        // Different hostnames, not merely different ports: ports are not part of
        // a cookie's key, so only the label actually separates the jars.
        val firstHost = android.net.Uri.parse(first).host
        val secondHost = android.net.Uri.parse(second).host
        assertNotEquals(firstHost, secondHost)
    }

    @Test
    fun `removing the last endpoint returns to the ticket field`() {
        harness.seed("ticket-alpha")
        val activity = launch()

        activity.removeEndpoint(0)

        assertEquals(View.VISIBLE, activity.entry().visibility)
        assertEquals(View.GONE, activity.webView().visibility)
        assertNull(activity.binding)
        assertEquals("stop", harness.proxy.calls.last())
    }

    @Test
    fun `removing an endpoint clears that origin and only that origin`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 1)
        val activity = launch()
        val removed = activity.binding!!

        activity.removeEndpoint(1)

        assertEquals(
            listOf(Origins.origin(removed.label, removed.port)),
            harness.siteData.cleared,
        )
    }

    @Test
    fun `removing an endpoint leaves the remaining one logged in`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 1)
        val activity = launch()
        val removedOrigin = activity.binding!!.let { Origins.origin(it.label, it.port) }

        activity.removeEndpoint(1)

        // Whatever is now on screen must not be among the origins cleared.
        val surviving = activity.binding!!.let { Origins.origin(it.label, it.port) }
        assertFalse(harness.siteData.cleared.contains(surviving))
        assertTrue(harness.siteData.cleared.contains(removedOrigin))
    }

    @Test
    fun `removing one of several opens the one that remains`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 1)
        val activity = launch()
        harness.proxy.calls.clear()

        activity.removeEndpoint(1)

        assertEquals(listOf("stop", "start:ticket-alpha"), harness.proxy.calls)
    }

    @Test
    fun `a failed start shows an error and keeps the endpoint saved`() {
        // A peer that is unreachable now is not a peer that was typed wrong.
        harness.proxy.failures["ticket-alpha"] = ProxyError.StartFailed
        val activity = launch()
        activity.addEndpoint("ticket-alpha")

        assertEquals(View.VISIBLE, activity.entryError().visibility)
        assertEquals(View.GONE, activity.webView().visibility)
        assertNull(activity.binding)
        assertEquals(listOf(Endpoint("ticket-alpha")), activity.endpoints.all)
    }

    @Test
    fun `a rejected ticket says so`() {
        harness.proxy.failures["nonsense"] = ProxyError.BadTicket
        val activity = launch()
        activity.addEndpoint("nonsense")

        val expected = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.error_bad_ticket)
        assertEquals(expected, activity.entryError().text.toString())
    }

    @Test
    fun `a rejected ticket is not saved`() {
        // It names no endpoint, so it could never open; saving it would only
        // leave an entry that fails every time it is chosen.
        harness.proxy.failures["nonsense"] = ProxyError.BadTicket
        val activity = launch()
        activity.addEndpoint("nonsense")

        assertTrue(activity.endpoints.all.isEmpty())
        assertTrue(harness.proxy.startedTickets.isEmpty())
    }

    @Test
    fun `pausing flushes cookies`() {
        // Persistent cookies are written lazily and an app killed from the
        // recents screen never gets another chance.
        harness.seed("ticket-alpha")
        val created = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller = created
        val before = harness.siteData.flushes

        created.pause()

        assertEquals(before + 1, harness.siteData.flushes)
    }

    @Test
    fun `our own pages stay in the webview`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        val binding = activity.binding!!

        assertFalse(activity.handleNavigation(Origins.url(binding.label, binding.port) + "page"))
    }

    @Test
    fun `an external link opens the real browser`() {
        harness.seed("ticket-alpha")
        val activity = launch()

        assertTrue(activity.handleNavigation("https://example.com/docs"))

        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.com/docs", started.data.toString())
    }

    @Test
    fun `an intent url starts nothing at all`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        // Drain anything the launch itself queued.
        while (shadowOf(application).nextStartedActivity != null) Unit

        assertTrue(activity.handleNavigation("intent://evil#Intent;scheme=http;end"))

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `navigation is refused while nothing is running`() {
        val activity = launch()
        assertTrue(activity.handleNavigation("http://anything.localhost:1/"))
    }
}
