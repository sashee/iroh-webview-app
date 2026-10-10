package com.example.irohbrowser

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The clipboard bridge's messages, and the clip it writes.
 *
 * Every refusal is checked to copy nothing: the page can post to the bridge
 * without the script, so the checks have to hold on their own.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardBridgeTest {

    private val alpha = ProxyBinding("alpha", 21000)
    private val copied = mutableListOf<String>()

    private fun answer(
        message: String,
        from: String = Origins.origin(alpha.label, alpha.port),
        onScreen: Boolean = true,
        accepted: Boolean = true,
    ): JSONObject? =
        ClipboardBridge.answer(message, from, alpha, onScreen) {
            copied += it
            accepted
        }?.let(::JSONObject)

    private fun JSONObject.errorName(): String? = optJSONObject("error")?.getString("name")

    @Test
    fun `a page's text is copied, and the reply carries its id`() {
        val reply = answer("""{"id": 7, "text": "hunter2"}""")!!

        assertEquals(7, reply.getInt("id"))
        assertFalse(reply.has("error"))
        assertEquals(listOf("hunter2"), copied)
    }

    @Test
    fun `another origin, or this host on another port, copies nothing`() {
        listOf("http://beta.localhost:21000", "http://alpha.localhost:21001", "https://alpha.localhost:21000")
            .forEach { assertEquals("SecurityError", answer("""{"id": 1, "text": "x"}""", from = it)!!.errorName()) }
        assertTrue(copied.isEmpty())
    }

    @Test
    fun `a page that is not on screen copies nothing`() {
        assertEquals("NotAllowedError", answer("""{"id": 1, "text": "x"}""", onScreen = false)!!.errorName())
        assertTrue(copied.isEmpty())
    }

    @Test
    fun `a message without text is a TypeError`() {
        assertEquals("TypeError", answer("""{"id": 1}""")!!.errorName())
        assertEquals("TypeError", answer("""{"id": 1, "text": 5}""")!!.errorName())
        assertTrue(copied.isEmpty())
    }

    @Test
    fun `a copy the system refuses is reported to the page`() {
        assertEquals("NotAllowedError", answer("""{"id": 1, "text": "x"}""", accepted = false)!!.errorName())
    }

    @Test
    fun `non-requests get no reply`() {
        listOf("not json", "{}", """{"id": -1, "text": "x"}""").forEach { assertNull(answer(it)) }
        assertTrue(copied.isEmpty())
    }

    @Test
    fun `the clip is marked sensitive`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTrue(copySensitive(context, "hunter2"))

        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("hunter2", clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }
}
