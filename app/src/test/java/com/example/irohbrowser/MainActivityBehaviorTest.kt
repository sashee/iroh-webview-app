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
import com.example.irohbrowser.testing.request
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
 * What the activity does, driven through a fake proxy: the cards, one per
 * endpoint, and what each does with its own.
 *
 * The acceptance list in the handover document is mostly here: one endpoint's
 * card must not carry its session to another's, external links must leave the
 * WebView, and cookies must be flushed before the app can be killed.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityBehaviorTest {

    private lateinit var harness: TestHarness
    private val controllers = mutableListOf<ActivityController<MainActivity>>()
    private val application get() = ApplicationProvider.getApplicationContext<android.app.Application>()

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

    /** What the launcher starts: created only, as Android goes no further with an activity finished in onCreate. */
    private fun fromLauncher(): MainActivity {
        val created = Robolectric.buildActivity(MainActivity::class.java, harness.launcher()).create()
        controllers += created
        return created.get()
    }

    /** The activities started since the last call, in order. */
    private fun started(): List<Intent> =
        generateSequence { shadowOf(application).nextStartedActivity }.toList()

    private fun MainActivity.webView() = findViewById<WebView>(R.id.web_view)
    private fun MainActivity.entry() = findViewById<LinearLayout>(R.id.entry)
    private fun MainActivity.entryError() = findViewById<TextView>(R.id.entry_error)
    private fun MainActivity.loadedUrl() = shadowOf(webView()).lastLoadedUrl
    private fun label(ticket: String) = harness.proxy.labelFor(ticket)

    /** Ask the activity's WebViewClient about a navigation, as the WebView would. */
    private fun MainActivity.navigate(url: String, tapped: Boolean) =
        shadowOf(webView()).webViewClient.shouldOverrideUrlLoading(webView(), request(url, gesture = tapped))

    // --- the launcher, and the entry card ---

    @Test
    fun `the launcher opens the card of the endpoint used last, and goes`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 0)
        val launcher = fromLauncher()

        assertEquals(listOf(harness.cardOf("ticket-alpha").data), started().map { it.data })
        assertTrue(launcher.isFinishing)
        assertTrue("it starts nothing itself", harness.proxy.calls.isEmpty())
    }

    @Test
    fun `with nothing saved the launcher opens the entry card`() {
        fromLauncher()

        val opened = started().single()
        assertNull(EndpointCards.labelOf(opened))
        assertEquals(EndpointCards.entry(application).data, opened.data)
    }

    @Test
    fun `a card's intent opens a task of its own, found again by its endpoint`() {
        val card = harness.cardOf("ticket-alpha")
        assertTrue(card.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT != 0)
        assertEquals(label("ticket-alpha"), EndpointCards.labelOf(card))
        assertEquals(card.data, harness.cardOf("ticket-alpha").data)
        assertNotEquals(card.data, harness.cardOf("ticket-beta").data)
        assertFalse(EndpointCards.isLauncher(card))
    }

    @Test
    fun `the entry card shows the ticket field and starts nothing`() {
        val activity = launch()

        assertEquals(View.VISIBLE, activity.entry().visibility)
        assertEquals(View.GONE, activity.webView().visibility)
        assertTrue(harness.proxy.startedTickets.isEmpty())
        assertNull(activity.binding)
    }

    @Test
    fun `connecting saves the ticket and opens its card, and the entry card goes`() {
        val activity = launch()
        activity.findViewById<EditText>(R.id.ticket_field).setText("ticket-alpha")
        activity.findViewById<Button>(R.id.connect_button).performClick()

        assertEquals(listOf(Endpoint("ticket-alpha")), harness.store.load().all)
        assertEquals(listOf(harness.cardOf("ticket-alpha").data), started().map { it.data })
        assertTrue(activity.isFinishing)
        assertTrue("the card starts it, not the entry", harness.proxy.startedTickets.isEmpty())
    }

    @Test
    fun `a pasted ticket is trimmed before it is saved`() {
        // Tickets arrive by copy-paste and often bring a newline with them.
        val activity = launch()
        activity.addEndpoint("  ticket-alpha\n ")

        assertEquals("ticket-alpha", harness.store.load().selected?.ticket)
    }

    @Test
    fun `an empty ticket is refused without starting anything`() {
        val activity = launch()
        activity.addEndpoint("   ")

        assertTrue(started().isEmpty())
        assertEquals(View.VISIBLE, activity.entryError().visibility)
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

        assertTrue(harness.store.load().all.isEmpty())
        assertTrue(started().isEmpty())
    }

    // --- a card ---

    @Test
    fun `a card starts its endpoint's proxy and loads its origin`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 0)
        val activity = launch(harness.cardOf("ticket-beta"))

        // Stopped first: an earlier activity of the card may have left it running on its port.
        assertEquals(listOf("stop:${label("ticket-beta")}", "start:ticket-beta"), harness.proxy.calls)
        val binding = activity.binding!!
        assertEquals(label("ticket-beta"), binding.label)
        assertEquals(Origins.url(binding.label, binding.port), activity.loadedUrl())
        assertEquals(View.VISIBLE, activity.webView().visibility)
        assertEquals(View.GONE, activity.entry().visibility)
    }

    @Test
    fun `the card in front is the one the launcher opens next`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 0)
        launch(harness.cardOf("ticket-beta"))

        assertEquals(1, harness.store.load().selectedIndex)
    }

    @Test
    fun `a card for an endpoint that is no longer saved shows the ticket field`() {
        harness.seed("ticket-alpha")
        val activity = launch(harness.cardOf("ticket-gone"))

        assertEquals(View.VISIBLE, activity.entry().visibility)
        assertTrue(harness.proxy.startedTickets.isEmpty())
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
    fun `cards run side by side, each with its own origin`() {
        harness.seed("ticket-alpha", "ticket-beta")
        val alpha = launch(harness.cardOf("ticket-alpha"))
        val beta = launch(harness.cardOf("ticket-beta"))

        assertEquals(setOf(alpha.binding, beta.binding), harness.proxy.running().toSet())
        // Different hostnames, not merely different ports: ports are not part of
        // a cookie's key, so only the label actually separates the jars.
        assertNotEquals(android.net.Uri.parse(alpha.loadedUrl()).host, android.net.Uri.parse(beta.loadedUrl()).host)
        assertEquals(emptyList<String>(), harness.siteData.cleared)
    }

    @Test
    fun `opening another endpoint brings its card, and this one stays as it is`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 0)
        val activity = launch(harness.cardOf("ticket-alpha"))
        started()
        harness.proxy.calls.clear()
        activity.showSettings()

        activity.openEndpointAt(1)

        assertEquals(listOf(harness.cardOf("ticket-beta").data), started().map { it.data })
        assertTrue("its own card starts it", harness.proxy.calls.isEmpty())
        assertFalse(activity.isFinishing)
        assertEquals("back on its page for when it is next shown", View.VISIBLE, activity.webView().visibility)
    }

    @Test
    fun `opening its own endpoint only shows the page`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        harness.proxy.calls.clear()
        activity.showSettings()

        activity.openEndpointAt(0)

        assertTrue(harness.proxy.calls.isEmpty())
        assertEquals(View.VISIBLE, activity.webView().visibility)
    }

    @Test
    fun `a card brought back is as it was left`() {
        harness.seed("ticket-alpha")
        val created = Robolectric.buildActivity(MainActivity::class.java, harness.lastCard()).setup()
        controllers += created
        harness.proxy.calls.clear()

        created.newIntent(harness.cardOf("ticket-alpha"))

        assertTrue(harness.proxy.calls.isEmpty())
    }

    @Test
    fun `a card whose endpoint would not start tries again when brought back`() {
        harness.proxy.failures["ticket-alpha"] = ProxyError.StartFailed
        harness.seed("ticket-alpha")
        val created = Robolectric.buildActivity(MainActivity::class.java, harness.lastCard()).setup()
        controllers += created
        harness.proxy.failures.clear()

        created.newIntent(harness.cardOf("ticket-alpha"))

        assertEquals(label("ticket-alpha"), created.get().binding?.label)
    }

    @Test
    fun `a card with nothing on it gives way to the endpoint opened from it`() {
        harness.proxy.failures["ticket-alpha"] = ProxyError.StartFailed
        harness.seed("ticket-alpha", "ticket-beta", selected = 0)
        val activity = launch()
        started()

        activity.openEndpointAt(1)

        assertEquals(listOf(harness.cardOf("ticket-beta").data), started().map { it.data })
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `closing a card stops its proxy, and only its`() {
        harness.seed("ticket-alpha", "ticket-beta")
        val alpha = launch(harness.cardOf("ticket-alpha"))
        val beta = launch(harness.cardOf("ticket-beta"))
        harness.proxy.calls.clear()

        close(alpha)

        assertEquals(listOf("stop:${label("ticket-alpha")}"), harness.proxy.calls)
        assertEquals(listOf(beta.binding), harness.proxy.running())
    }

    @Test
    fun `a card destroyed but not closed keeps its proxy`() {
        // The screen going off, or Android recreating it: coming back would
        // otherwise need a fresh dial for every connection.
        harness.seed("ticket-alpha")
        val controller = Robolectric.buildActivity(MainActivity::class.java, harness.lastCard()).setup()
        harness.proxy.calls.clear()

        controller.pause().stop().destroy()

        assertTrue(harness.proxy.calls.isEmpty())
    }

    // --- removing ---

    @Test
    fun `removing this card's endpoint stops its proxy, clears its origin, and leaves the list`() {
        harness.seed("ticket-alpha", "ticket-beta")
        val activity = launch()
        val removed = activity.binding!!
        activity.showSettings()
        harness.proxy.calls.clear()

        activity.removeEndpoint(1)

        assertEquals(listOf("stop:${removed.label}"), harness.proxy.calls)
        assertEquals(listOf(Origins.origin(removed.label, removed.port)), harness.siteData.cleared)
        assertNull(activity.binding)
        assertEquals("about:blank", activity.loadedUrl())
        assertEquals(listOf(Endpoint("ticket-alpha", "ticket-alpha")), harness.store.load().all)
        assertTrue("its card is this one", harness.cardTasks.closed.isEmpty())
    }

    @Test
    fun `removing the last endpoint returns to the ticket field`() {
        harness.seed("ticket-alpha")
        val activity = launch()

        activity.removeEndpoint(0)

        assertEquals(View.VISIBLE, activity.entry().visibility)
        assertEquals(View.GONE, activity.webView().visibility)
        assertNull(activity.binding)
    }

    @Test
    fun `removing another card's endpoint closes its card, and clears it on the port it runs on`() {
        harness.seed("ticket-alpha", "ticket-beta")
        val alpha = launch(harness.cardOf("ticket-alpha"))
        val beta = launch(harness.cardOf("ticket-beta")).binding!!

        alpha.removeEndpoint(1)

        assertEquals(listOf(beta.label), harness.cardTasks.closed)
        assertEquals(listOf(Origins.origin(beta.label, beta.port)), harness.siteData.cleared)
        assertEquals("this card's page stays", View.VISIBLE, alpha.webView().visibility)
        assertEquals(label("ticket-alpha"), alpha.binding?.label)
    }

    @Test
    fun `removing an endpoint with no card clears it on its preferred port`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 0)
        val activity = launch()

        activity.removeEndpoint(1)

        assertEquals(listOf(Origins.origin(label("ticket-beta"), harness.proxy.preferredPortFor("ticket-beta"))), harness.siteData.cleared)
    }

    @Test
    fun `a failed start shows an error and keeps the endpoint saved`() {
        // A peer that is unreachable now is not a peer that was typed wrong.
        harness.proxy.failures["ticket-alpha"] = ProxyError.StartFailed
        harness.seed("ticket-alpha")
        val activity = launch()

        assertEquals(View.VISIBLE, activity.entryError().visibility)
        assertEquals(View.GONE, activity.webView().visibility)
        assertNull(activity.binding)
        assertEquals(listOf("ticket-alpha"), harness.store.load().all.map { it.ticket })
    }

    @Test
    fun `pausing flushes cookies`() {
        // Persistent cookies are written lazily and an app killed from the
        // recents screen never gets another chance.
        harness.seed("ticket-alpha")
        val created = Robolectric.buildActivity(MainActivity::class.java, harness.lastCard()).setup()
        controllers += created
        val before = harness.siteData.flushes

        created.pause()

        assertEquals(before + 1, harness.siteData.flushes)
    }

    @Test
    fun `our own pages stay in the webview`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        val binding = activity.binding!!

        assertFalse(activity.navigate(Origins.url(binding.label, binding.port) + "page", tapped = false))
    }

    @Test
    fun `a tapped external link opens the real browser`() {
        harness.seed("ticket-alpha")
        val activity = launch()

        assertTrue(activity.navigate("https://example.com/docs", tapped = true))

        val opened = started().single()
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://example.com/docs", opened.data.toString())
    }

    @Test
    fun `a page sending itself elsewhere with no tap opens nothing`() {
        // On a timer, say, with whatever it likes in the URL.
        harness.seed("ticket-alpha")
        val activity = launch()
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        while (shadowOf(application).nextStartedActivity != null) {}

        assertTrue(activity.navigate("https://example.com/?stolen=1", tapped = false))

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `an intent url starts nothing at all`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        // Drain anything the launch itself queued.
        while (shadowOf(application).nextStartedActivity != null) {}

        assertTrue(activity.navigate("intent://evil#Intent;scheme=http;end", tapped = true))

        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `navigation is refused while nothing is running`() {
        val activity = launch()
        assertTrue(activity.navigate("http://anything.localhost:1/", tapped = true))
    }
}
