package com.example.irohbrowser

import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.WebView
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AlertDialog
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
import org.robolectric.fakes.RoboMenu
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowDialog

/**
 * The settings screen, driven the way a user would: menu, buttons, dialogs.
 *
 * The rules it shows are `SettingsTest`'s; what is checked here is that each
 * action does what it says to the saved state and to the browser -- and that
 * the two irreversible ones ask first.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {

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

    // --- driving it ---

    private fun MainActivity.openSettingsFromMenu() {
        val menu = RoboMenu(this)
        onCreateOptionsMenu(menu)
        val item = (0 until menu.size()).map(menu::getItem).single { it.title == getString(R.string.menu_settings) }
        onOptionsItemSelected(item)
    }

    private fun MainActivity.settings(): View = findViewById(R.id.settings)
    private fun MainActivity.content(): LinearLayout = findViewById(R.id.settings_content)
    private fun MainActivity.webView(): WebView = findViewById(R.id.web_view)

    private fun View.descendants(): Sequence<View> = sequenceOf(this) +
        ((this as? ViewGroup)?.let { group -> (0 until group.childCount).asSequence().flatMap { group.getChildAt(it).descendants() } }
            ?: emptySequence())

    private fun MainActivity.texts(): List<String> =
        content().descendants().filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun MainActivity.tap(tag: String) {
        content().findViewWithTag<View>(tag).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun dialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

    private fun answer(button: Int) {
        dialog().getButton(button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun MainActivity.origin(): String = binding!!.let { Origins.origin(it.label, it.port) }

    private fun preferredOrigin(ticket: String) =
        Origins.origin(harness.proxy.labelFor(ticket), harness.proxy.preferredPortFor(ticket))

    /** A passkey for [user], created by the open endpoint's page. */
    private fun createPasskey(user: String) {
        val reply = harness.passkeys.installer.post(
            """{"id": 1, "type": "create", "options": {"rp": {"name": "demo"},
                "user": {"id": "${WebAuthn.base64Url(user.toByteArray())}", "name": "$user", "displayName": ""},
                "challenge": "AQID", "pubKeyCredParams": [{"type": "public-key", "alg": -7}]}}""",
        ).single()
        assertTrue(reply, JSONObject(reply).has("credential"))
    }

    // --- what it shows ---

    @Test
    fun `the menu opens a list of every endpoint, the open one marked`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 1)
        val activity = launch()

        activity.openSettingsFromMenu()

        assertEquals(View.VISIBLE, activity.settings().visibility)
        assertEquals(View.GONE, activity.webView().visibility)
        val texts = activity.texts()
        assertTrue(texts.toString(), "ticket-alpha" in texts)
        assertTrue(texts.toString(), activity.getString(R.string.settings_open_marker, "ticket-beta") in texts)
        // The open one where it really is; the other where it would be.
        assertTrue(texts.toString(), activity.origin() in texts)
        assertTrue(texts.toString(), preferredOrigin("ticket-alpha") in texts)
    }

    @Test
    fun `an unnamed endpoint is listed by its label`() {
        harness.seed("ticket-alpha", named = false)
        val activity = launch()
        activity.showSettings()
        assertTrue(activity.getString(R.string.settings_open_marker, harness.proxy.labelFor("ticket-alpha")) in activity.texts())
    }

    @Test
    fun `passkeys are listed under their endpoint, with where the key lives`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        createPasskey("alice")

        activity.showSettings()

        val texts = activity.texts()
        assertTrue(texts.toString(), "alice" in texts)
        assertTrue(texts.toString(), texts.any { it.contains(activity.getString(R.string.key_software)) })
        assertFalse(texts.toString(), activity.getString(R.string.settings_orphans) in texts)
    }

    // --- removing ---

    @Test
    fun `removing another endpoint asks, then forgets only its site data and leaves the page alone`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 1)
        val activity = launch()
        harness.proxy.calls.clear()
        activity.showSettings()

        activity.tap("remove:0")
        answer(DialogInterface.BUTTON_POSITIVE)

        assertEquals(listOf(preferredOrigin("ticket-alpha")), harness.siteData.cleared)
        assertEquals(listOf("ticket-beta"), activity.endpoints.all.map { it.ticket })
        assertEquals("ticket-beta", activity.endpoints.selected?.ticket)
        assertTrue("the open endpoint was restarted", harness.proxy.calls.isEmpty())
        assertEquals(View.VISIBLE, activity.settings().visibility)
    }

    @Test
    fun `declining a removal removes nothing`() {
        harness.seed("ticket-alpha", "ticket-beta")
        val activity = launch()
        activity.showSettings()

        activity.tap("remove:0")
        answer(DialogInterface.BUTTON_NEGATIVE)

        assertEquals(2, activity.endpoints.all.size)
        assertTrue(harness.siteData.cleared.isEmpty())
    }

    @Test
    fun `removing the open endpoint opens the next and stays on the list`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 1)
        val activity = launch()
        val removed = activity.origin()
        activity.showSettings()

        activity.tap("remove:1")
        answer(DialogInterface.BUTTON_POSITIVE)

        assertEquals(listOf(removed), harness.siteData.cleared)
        assertEquals("ticket-alpha", harness.proxy.startedTickets.last())
        assertEquals(View.VISIBLE, activity.settings().visibility)
    }

    @Test
    fun `a removed endpoint's passkeys are listed as without an endpoint`() {
        harness.seed("ticket-beta", "ticket-alpha", selected = 1)
        val activity = launch()
        createPasskey("alice")
        activity.showSettings()

        activity.tap("remove:1")
        answer(DialogInterface.BUTTON_POSITIVE)

        val texts = activity.texts()
        assertTrue(texts.toString(), activity.getString(R.string.settings_orphans) in texts)
        val host = Origins.host(harness.proxy.labelFor("ticket-alpha"))
        assertTrue(texts.toString(), activity.getString(R.string.passkey_on_site, "alice", host) in texts)
        assertEquals(1, harness.passkeys.store.load().size)
    }

    // --- deleting a passkey ---

    @Test
    fun `deleting a passkey asks, then forgets it and its key`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        createPasskey("alice")
        val id = harness.passkeys.store.load().single().credentialId
        activity.showSettings()

        activity.tap("delete:$id")
        answer(DialogInterface.BUTTON_POSITIVE)

        assertTrue(harness.passkeys.store.load().isEmpty())
        assertTrue(harness.passkeys.vault.keys.isEmpty())
        assertTrue(activity.getString(R.string.settings_no_passkeys) in activity.texts())
    }

    @Test
    fun `declining to delete keeps the passkey`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        createPasskey("alice")
        val id = harness.passkeys.store.load().single().credentialId
        activity.showSettings()

        activity.tap("delete:$id")
        answer(DialogInterface.BUTTON_NEGATIVE)

        assertEquals(1, harness.passkeys.store.load().size)
        assertEquals(1, harness.passkeys.vault.keys.size)
    }

    // --- renaming, opening, leaving ---

    @Test
    fun `a rename shows in the list and in the next prompt`() {
        harness.seed("ticket-alpha", named = false)
        val activity = launch()
        activity.showSettings()

        activity.tap("rename:0")
        dialog().window!!.decorView.descendants().filterIsInstance<EditText>().single().setText("rpi5")
        answer(DialogInterface.BUTTON_POSITIVE)

        assertEquals("rpi5", activity.endpoints.all[0].name)
        assertTrue(activity.getString(R.string.settings_open_marker, "rpi5") in activity.texts())
        createPasskey("alice")
        assertEquals("rpi5", harness.passkeys.ui.prompts.last().endpointName)
    }

    @Test
    fun `opening another endpoint switches to it`() {
        harness.seed("ticket-alpha", "ticket-beta", selected = 1)
        val activity = launch()
        activity.showSettings()

        activity.tap("open:0")

        assertEquals("ticket-alpha", harness.proxy.startedTickets.last())
        assertEquals(View.VISIBLE, activity.webView().visibility)
        assertEquals(View.GONE, activity.settings().visibility)
    }

    @Test
    fun `opening the open endpoint shows its page again without reconnecting`() {
        // With one endpoint this is the visible way back; it must not reload
        // a page that is already there.
        harness.seed("ticket-alpha")
        val activity = launch()
        harness.proxy.calls.clear()
        activity.showSettings()

        activity.tap("open:0")

        assertEquals(View.VISIBLE, activity.webView().visibility)
        assertEquals(View.GONE, activity.settings().visibility)
        assertTrue(harness.proxy.calls.isEmpty())
    }

    @Test
    fun `the app bar says where you are and has a way back`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        val bar = activity.supportActionBar!!

        activity.showSettings()
        assertEquals(activity.getString(R.string.menu_settings), bar.title)
        assertTrue(bar.displayOptions and ActionBar.DISPLAY_HOME_AS_UP != 0)

        activity.onOptionsItemSelected(RoboMenuItem(android.R.id.home))
        assertEquals(View.VISIBLE, activity.webView().visibility)
        assertEquals(activity.getString(R.string.app_name), bar.title)
        assertTrue(bar.displayOptions and ActionBar.DISPLAY_HOME_AS_UP == 0)
    }

    @Test
    fun `back from the list returns to the page`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        activity.showSettings()

        @Suppress("DEPRECATION")
        activity.onBackPressed()

        assertEquals(View.VISIBLE, activity.webView().visibility)
        assertEquals(View.GONE, activity.settings().visibility)
    }

    @Test
    fun `adding from the list shows the ticket field, and back returns to the page`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        activity.showSettings()

        activity.tap("add")
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.entry).visibility)

        @Suppress("DEPRECATION")
        activity.onBackPressed()
        assertEquals(View.VISIBLE, activity.webView().visibility)
    }
}
