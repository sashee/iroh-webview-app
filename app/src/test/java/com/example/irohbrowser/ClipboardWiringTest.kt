package com.example.irohbrowser

import android.content.Intent
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.android.controller.ActivityController

/**
 * Where the activity offers the clipboard bridge, and when a page's copy
 * reaches the clipboard.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardWiringTest {

    private lateinit var harness: TestHarness
    private val controllers = mutableListOf<ActivityController<MainActivity>>()

    private val installer get() = harness.clipboardBridge

    private val clipboard: ClipboardManager
        get() = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)

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

    /** The activity, on screen and focused, as it is when the user taps a page's copy button. */
    private fun launch(card: Intent = harness.lastCard()): MainActivity {
        val created = Robolectric.buildActivity(MainActivity::class.java, card).setup().windowFocusChanged(true)
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

    private fun copy(text: String): JSONObject =
        JSONObject(installer.post(JSONObject().put("id", 1).put("text", text).toString()).single())

    @Test
    fun `each card offers the bridge to its own origin`() {
        harness.seed("ticket-alpha", "ticket-beta")
        val alpha = launch(harness.cardOf("ticket-alpha")).origin()
        val beta = launch(harness.cardOf("ticket-beta")).origin()

        assertEquals(listOf("install:$alpha", "install:$beta"), installer.events)
    }

    @Test
    fun `nothing is offered before an endpoint is running`() {
        launch()
        assertTrue(installer.events.isEmpty())
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
    fun `a page's copy lands on the clipboard marked sensitive`() {
        harness.seed("ticket-alpha")
        launch()

        assertFalse(copy("hunter2").has("error"))

        val clip = clipboard.primaryClip!!
        assertEquals("hunter2", clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    @Test
    fun `a page behind the settings screen cannot copy`() {
        harness.seed("ticket-alpha")
        launch().showSettings()

        assertEquals("NotAllowedError", copy("hunter2").getJSONObject("error").getString("name"))
        assertNull(clipboard.primaryClip)
    }

    @Test
    fun `a page cannot copy while the app is not focused`() {
        harness.seed("ticket-alpha")
        launch()
        controllers.single().windowFocusChanged(false)

        assertEquals("NotAllowedError", copy("hunter2").getJSONObject("error").getString("name"))
        assertNull(clipboard.primaryClip)
    }
}
