package com.example.irohbrowser

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reading what the script relays, and the rules about what a page may ask for.
 *
 * The page can post to the bridge without going through the script, so these
 * are written as if the script did not exist: any message, any field.
 */
@RunWith(RobolectricTestRunner::class)
class PasskeyRequestsTest {

    private val alpha = PasskeySite("alpha", 21000, "Alpha")

    private fun creation(edit: JSONObject.() -> Unit = {}) = JSONObject(
        """
        {"rp": {"name": "demo"},
         "user": {"id": "AQID", "name": "alice", "displayName": "Alice"},
         "challenge": "CQgHBg",
         "pubKeyCredParams": [{"type": "public-key", "alg": -7}, {"type": "public-key", "alg": -257}],
         "excludeCredentials": [{"type": "public-key", "id": "qrs="}],
         "attestation": "none",
         "extensions": {"credProps": true}}
        """,
    ).apply(edit)

    private fun <T> Outcome<T>.value(): T = (this as Outcome.Ok).value
    private fun Outcome<*>.errorName(): String = (this as Outcome.Failed).error.name

    @Test
    fun `a creation request is read in full`() {
        val request = PasskeyRequests.parseCreate(creation()).value()
        assertNull(request.rpId)
        assertArrayEquals(byteArrayOf(1, 2, 3), request.userId)
        assertEquals("alice", request.userName)
        assertEquals("Alice", request.userDisplayName)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), request.challenge)
        assertEquals(listOf(-7, -257), request.algorithms)
        assertEquals(listOf("qrs"), request.excludeCredentials) // re-encoded without padding
        assertEquals("none", request.attestation)
        assertTrue(request.credProps)
    }

    @Test
    fun `an RP ID that is JSON null is no RP ID`() {
        // org.json's optString would report it as the string "null".
        val request = PasskeyRequests.parseCreate(creation { getJSONObject("rp").put("id", JSONObject.NULL) }).value()
        assertNull(request.rpId)
    }

    @Test
    fun `parameters of other types are not algorithms`() {
        val request = PasskeyRequests.parseCreate(
            creation { put("pubKeyCredParams", org.json.JSONArray("""[{"type": "other", "alg": -7}]""")) },
        ).value()
        assertEquals(emptyList<Int>(), request.algorithms)
    }

    @Test
    fun `missing or malformed members are a TypeError`() {
        listOf<JSONObject.() -> Unit>(
            { remove("challenge") },
            { put("challenge", "not base64!") },
            { remove("rp") },
            { remove("user") },
            { remove("pubKeyCredParams") },
            { getJSONObject("user").remove("id") },
            { getJSONObject("user").put("id", "") },
            { getJSONObject("user").put("id", WebAuthn.base64Url(ByteArray(65))) },
        ).forEach { edit ->
            assertEquals("TypeError", PasskeyRequests.parseCreate(creation(edit)).errorName())
        }
        assertEquals("TypeError", PasskeyRequests.parseCreate(null).errorName())
    }

    @Test
    fun `a sign-in request is read in full`() {
        val request = PasskeyRequests.parseGet(
            JSONObject("""{"challenge": "AQ", "rpId": "alpha.localhost", "allowCredentials": [{"type": "public-key", "id": "qg"}]}"""),
        ).value()
        assertArrayEquals(byteArrayOf(1), request.challenge)
        assertEquals("alpha.localhost", request.rpId)
        assertEquals(listOf("qg"), request.allowCredentials)
    }

    @Test
    fun `a sign-in request without a challenge is a TypeError`() {
        assertEquals("TypeError", PasskeyRequests.parseGet(JSONObject("{}")).errorName())
        assertEquals("TypeError", PasskeyRequests.parseGet(null).errorName())
    }

    @Test
    fun `an RP ID left out is the page's host`() {
        assertEquals("alpha.localhost", PasskeyRequests.rpId(null, alpha).value())
        assertEquals("alpha.localhost", PasskeyRequests.rpId("", alpha).value())
    }

    @Test
    fun `an RP ID naming the page's host is accepted, in any case`() {
        assertEquals("alpha.localhost", PasskeyRequests.rpId("ALPHA.localhost", alpha).value())
    }

    @Test
    fun `any other RP ID is a SecurityError`() {
        // "localhost" is what a browser would also allow, as a parent domain;
        // here each endpoint is one host and nothing needs the parent.
        listOf("localhost", "beta.localhost", "example.com", "alpha.localhost.example.com", "alpha.localhost:21000")
            .forEach { assertEquals(it, "SecurityError", PasskeyRequests.rpId(it, alpha).errorName()) }
    }

    @Test
    fun `ES256 is accepted when offered, or when nothing is`() {
        assertTrue(PasskeyRequests.acceptsEs256(listOf(-257, -7)))
        assertTrue(PasskeyRequests.acceptsEs256(emptyList()))
        assertFalse(PasskeyRequests.acceptsEs256(listOf(-257, -8)))
    }

    private fun passkey(id: String, rpId: String) = StoredPasskey(id, rpId, "h", "u", "U", 0)

    @Test
    fun `candidates are this RP ID's passkeys`() {
        val stored = listOf(passkey("a1", "alpha.localhost"), passkey("b1", "beta.localhost"), passkey("a2", "alpha.localhost"))
        assertEquals(listOf("a1", "a2"), PasskeyRequests.candidates(stored, "alpha.localhost", emptyList()).map { it.credentialId })
        assertEquals(listOf("a2"), PasskeyRequests.candidates(stored, "alpha.localhost", listOf("a2", "b1")).map { it.credentialId })
        assertEquals(emptyList<StoredPasskey>(), PasskeyRequests.candidates(stored, "alpha.localhost", listOf("b1")))
    }

    @Test
    fun `already registered means this RP ID and an excluded id`() {
        val stored = listOf(passkey("a1", "alpha.localhost"), passkey("b1", "beta.localhost"))
        assertTrue(PasskeyRequests.alreadyRegistered(stored, "alpha.localhost", listOf("a1")))
        assertFalse(PasskeyRequests.alreadyRegistered(stored, "alpha.localhost", listOf("b1")))
        assertFalse(PasskeyRequests.alreadyRegistered(stored, "alpha.localhost", emptyList()))
    }
}
