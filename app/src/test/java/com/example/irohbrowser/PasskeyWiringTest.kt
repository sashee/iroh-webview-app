package com.example.irohbrowser

import android.content.Intent
import com.example.irohbrowser.testing.FakePasskeyUi
import com.example.irohbrowser.testing.TestHarness
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * Where the activity offers the passkey bridge, and when it takes it away.
 *
 * The bridge is one of the two ways page JavaScript reaches into the app, so
 * the rule worth pinning down is the boundary: offered to the running
 * endpoint's exact origin, withdrawn the moment that endpoint stops being the
 * one on screen.
 */
@RunWith(RobolectricTestRunner::class)
class PasskeyWiringTest {

    private lateinit var harness: TestHarness
    private val controllers = mutableListOf<ActivityController<MainActivity>>()

    private val installer get() = harness.passkeys.installer

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

    private val creation = """
        {"id": 1, "type": "create", "options": {"rp": {"name": "demo"},
         "user": {"id": "AQ", "name": "alice", "displayName": ""},
         "challenge": "AQID", "pubKeyCredParams": [{"type": "public-key", "alg": -7}]}}
    """

    @Test
    fun `the bridge is offered to the running endpoint's origin and no other`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        assertEquals(listOf("install:${activity.origin()}"), installer.events)
    }

    @Test
    fun `nothing is offered before an endpoint is running`() {
        launch()
        assertTrue(installer.events.isEmpty())
    }

    @Test
    fun `each card offers the bridge to its own origin`() {
        harness.seed("ticket-alpha", "ticket-beta")
        val alpha = launch(harness.cardOf("ticket-alpha")).origin()
        val beta = launch(harness.cardOf("ticket-beta")).origin()

        assertEquals(listOf("install:$alpha", "install:$beta"), installer.events)
    }

    @Test
    fun `each card's page signs for its own endpoint`() {
        harness.seed("ticket-alpha", "ticket-beta")
        val alpha = launch(harness.cardOf("ticket-alpha"))
        val beta = launch(harness.cardOf("ticket-beta"))

        installer.post(creation, from = alpha.origin())
        installer.post(creation.replace("\"AQ\"", "\"Ag\""), from = beta.origin())

        assertEquals(
            setOf(Origins.host(alpha.binding!!.label), Origins.host(beta.binding!!.label)),
            harness.passkeys.store.load().map { it.rpId }.toSet(),
        )
        assertEquals(listOf("ticket-alpha", "ticket-beta"), harness.passkeys.ui.prompts.map { it.endpointName })
    }

    @Test
    fun `a prompt raised by a card's page is withdrawn when its endpoint is removed`() {
        harness.seed("ticket-alpha", "ticket-beta")
        val activity = launch()
        harness.passkeys.ui.verdict = FakePasskeyUi.Verdict.Hold
        installer.post(creation)

        activity.removeEndpoint(1)

        assertEquals(1, harness.passkeys.ui.withdrawn)
        assertTrue(harness.passkeys.store.load().isEmpty())
    }

    @Test
    fun `an endpoint that fails to start is offered nothing`() {
        harness.proxy.failures["ticket-alpha"] = ProxyError.StartFailed
        harness.seed("ticket-alpha")
        launch()
        assertTrue(installer.events.isEmpty())
    }

    @Test
    fun `removing the endpoint withdraws the bridge`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        val origin = activity.origin()
        activity.removeEndpoint(0)
        assertEquals(listOf("install:$origin", "uninstall:$origin"), installer.events)
        assertNull(installer.installedOrigin)
    }

    @Test
    fun `finishing the activity withdraws the bridge`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        val origin = activity.origin()
        close(activity)
        assertEquals("uninstall:$origin", installer.events.last())
    }

    @Test
    fun `a page's request reaches the authenticator, for the endpoint on screen`() {
        harness.seed("ticket-alpha")
        val activity = launch()

        val reply = JSONObject(installer.post(creation).single())

        assertEquals(1, reply.getInt("id"))
        assertTrue(reply.has("credential"))
        val stored = harness.passkeys.store.load().single()
        assertEquals(Origins.host(activity.binding!!.label), stored.rpId)
        // The endpoint's name, as saved, is what the prompt shows.
        assertEquals("ticket-alpha", harness.passkeys.ui.prompts.single().endpointName)
    }

    @Test
    fun `a WebView without the bridge's features still browses`() {
        installer.supported = false
        harness.seed("ticket-alpha")
        val activity = launch()
        assertTrue(activity.binding != null)
        activity.removeEndpoint(0) // withdrawing nothing must not fail
    }
}
