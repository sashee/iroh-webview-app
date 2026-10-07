package com.example.irohbrowser

import androidx.test.core.app.ApplicationProvider
import com.example.irohbrowser.testing.FakePasskeyUi
import com.example.irohbrowser.testing.FakePasskeys
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The message protocol between the injected script and the app: ids, origins,
 * one ceremony at a time, cancellation.
 */
@RunWith(RobolectricTestRunner::class)
class PasskeyBridgeTest {

    private val passkeys = FakePasskeys(ApplicationProvider.getApplicationContext())
    private val bridge = PasskeyBridge(
        PasskeyAuthenticator(passkeys.store, passkeys.vault, passkeys.ui, passkeys.platform.random, passkeys.platform.clock),
    )
    private val alpha = PasskeySite("alpha", 21000, "Alpha")

    private val creation = """
        {"rp": {"name": "demo"}, "user": {"id": "AQ", "name": "alice", "displayName": "Alice"},
         "challenge": "AQID", "pubKeyCredParams": [{"type": "public-key", "alg": -7}]}
    """

    private fun message(id: Int, type: String, options: String? = null) =
        """{"id": $id, "type": "$type"${options?.let { ""","options": $it""" } ?: ""}}"""

    private fun post(message: String, from: String = alpha.origin): List<JSONObject> {
        val replies = mutableListOf<JSONObject>()
        bridge.receive(message, from, alpha) { replies += JSONObject(it) }
        return replies
    }

    private fun JSONObject.errorName(): String = getJSONObject("error").getString("name")

    @Test
    fun `a registration is answered with a credential under the request's id`() {
        val reply = post(message(7, "create", creation)).single()
        assertEquals(7, reply.getInt("id"))
        assertEquals("public-key", reply.getJSONObject("credential").getString("type"))
    }

    @Test
    fun `a sign-in is answered under the request's id`() {
        post(message(1, "create", creation))
        val reply = post(message(2, "get", """{"challenge": "AQ"}""")).single()
        assertEquals(2, reply.getInt("id"))
        assertTrue(reply.has("credential"))
    }

    @Test
    fun `a message from another origin is refused without a prompt`() {
        val reply = post(message(1, "create", creation), from = "http://beta.localhost:21000").single()
        assertEquals("SecurityError", reply.errorName())
        assertTrue(passkeys.ui.prompts.isEmpty())
    }

    @Test
    fun `the same host on another port is another origin`() {
        val reply = post(message(1, "create", creation), from = "http://alpha.localhost:21001").single()
        assertEquals("SecurityError", reply.errorName())
    }

    @Test
    fun `a second request while one is in progress is refused, and the first still completes`() {
        passkeys.ui.verdict = FakePasskeyUi.Verdict.Hold
        val replies = mutableListOf<JSONObject>()
        bridge.receive(message(1, "create", creation), alpha.origin, alpha) { replies += JSONObject(it) }
        bridge.receive(message(2, "get", """{"challenge": "AQ"}"""), alpha.origin, alpha) { replies += JSONObject(it) }

        assertEquals(listOf(2), replies.map { it.getInt("id") })
        assertEquals("NotAllowedError", replies.single().errorName())

        passkeys.ui.approveHeld()
        assertEquals(listOf(2, 1), replies.map { it.getInt("id") })
        assertTrue(replies.last().has("credential"))
    }

    @Test
    fun `a finished request makes way for the next`() {
        post(message(1, "get", """{"challenge": "AQ"}""")) // refused: nothing registered
        assertTrue(post(message(2, "create", creation)).single().has("credential"))
    }

    @Test
    fun `the page cancelling withdraws the prompt`() {
        passkeys.ui.verdict = FakePasskeyUi.Verdict.Hold
        post(message(1, "create", creation))
        post(message(1, "cancel"))
        assertEquals(1, passkeys.ui.withdrawn)
        assertTrue(passkeys.store.load().isEmpty())
    }

    @Test
    fun `cancelling someone else's id withdraws nothing`() {
        passkeys.ui.verdict = FakePasskeyUi.Verdict.Hold
        post(message(1, "create", creation))
        post(message(2, "cancel"))
        assertEquals(0, passkeys.ui.withdrawn)
    }

    @Test
    fun `the app cancelling withdraws the prompt and frees the bridge`() {
        passkeys.ui.verdict = FakePasskeyUi.Verdict.Hold
        post(message(1, "create", creation))
        bridge.cancel()
        assertEquals(1, passkeys.ui.withdrawn)
        passkeys.ui.verdict = FakePasskeyUi.Verdict.Approve
        assertTrue(post(message(2, "create", creation)).single().has("credential"))
    }

    @Test
    fun `malformed options are a TypeError`() {
        assertEquals("TypeError", post(message(1, "create", "{}")).single().errorName())
        assertEquals("TypeError", post(message(2, "get")).single().errorName())
    }

    @Test
    fun `an unknown request type is a TypeError`() {
        assertEquals("TypeError", post(message(1, "store")).single().errorName())
    }

    @Test
    fun `messages that are not requests get no reply`() {
        listOf("not json", "[]", """{"type": "create"}""", """{"id": -1, "type": "create"}""").forEach {
            assertEquals(it, emptyList<JSONObject>(), post(it))
        }
    }
}
