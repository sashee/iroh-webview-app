package com.example.irohbrowser

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
    private var controller: ActivityController<MainActivity>? = null

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
        controller?.close()
        AppContainer.install(null)
    }

    /** The activity, on screen and focused, as it is when the user taps a page's copy button. */
    private fun launch(): MainActivity {
        val created = Robolectric.buildActivity(MainActivity::class.java).setup().windowFocusChanged(true)
        controller = created
        return created.get()
    }

    private fun MainActivity.origin(): String = binding!!.let { Origins.origin(it.label, it.port) }

    private fun copy(text: String): JSONObject =
        JSONObject(installer.post(JSONObject().put("id", 1).put("text", text).toString()).single())

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
    fun `nothing is offered before an endpoint is running`() {
        launch()
        assertTrue(installer.events.isEmpty())
    }

    @Test
    fun `finishing the activity withdraws the bridge`() {
        harness.seed("ticket-alpha")
        val activity = launch()
        val origin = activity.origin()
        controller!!.pause().stop().destroy()
        controller = null
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
        controller!!.windowFocusChanged(false)

        assertEquals("NotAllowedError", copy("hunter2").getJSONObject("error").getString("name"))
        assertNull(clipboard.primaryClip)
    }
}
