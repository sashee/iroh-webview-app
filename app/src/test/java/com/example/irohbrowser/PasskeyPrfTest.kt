package com.example.irohbrowser

import androidx.test.core.app.ApplicationProvider
import com.example.irohbrowser.testing.FakePasskeyUi
import com.example.irohbrowser.testing.FakePasskeys
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationData
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.verifier.attestation.statement.none.NoneAttestationStatementVerifier
import com.webauthn4j.verifier.attestation.statement.packed.PackedAttestationStatementVerifier
import com.webauthn4j.verifier.attestation.trustworthiness.certpath.NullCertPathTrustworthinessVerifier
import com.webauthn4j.verifier.attestation.trustworthiness.self.DefaultSelfAttestationTrustworthinessVerifier
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The PRF extension: a secret per passkey that a site can turn into keys of
 * its own, which never leave the phone.
 *
 * What the sites rely on is that a result is a pure function of (passkey,
 * input), and that it is the function the specification gives -- so the
 * expected values are computed here independently, from the fake vault's raw
 * PRF key, rather than by calling the code under test.
 */
@RunWith(RobolectricTestRunner::class)
class PasskeyPrfTest {

    private val passkeys = FakePasskeys(ApplicationProvider.getApplicationContext())
    private val ui = passkeys.ui
    private val vault = passkeys.vault
    private val authenticator = PasskeyAuthenticator(
        passkeys.store, vault, ui, passkeys.platform.random, passkeys.platform.clock,
    )
    private val alpha = PasskeySite("alpha", 21000, "Alpha")
    private val beta = PasskeySite("beta", 21000, "Beta")

    // --- the expected values, by hand ---

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)

    /** HMAC(HMAC(prfKey, fixed message), SHA-256("WebAuthn PRF" ‖ 0x00 ‖ input)). */
    private fun expected(passkey: StoredPasskey, input: ByteArray): ByteArray {
        val secret = hmac(vault.prfKeys.getValue(passkey.prfAlias), "iroh-webview-app passkey PRF secret v1".toByteArray())
        val salt = MessageDigest.getInstance("SHA-256").digest("WebAuthn PRF".toByteArray() + byteArrayOf(0) + input)
        return hmac(secret, salt)
    }

    // --- driving it ---

    private val webauthn = WebAuthnManager(
        listOf(NoneAttestationStatementVerifier(), PackedAttestationStatementVerifier()),
        NullCertPathTrustworthinessVerifier(),
        DefaultSelfAttestationTrustworthinessVerifier(),
    )

    private fun server(site: PasskeySite, challenge: ByteArray) = ServerProperty.builder()
        .origin(Origin.create(site.origin)).rpId(site.host).challenge(DefaultChallenge(challenge)).build()

    private val challenge = byteArrayOf(1, 2, 3, 4)

    private fun register(
        prf: Boolean = true,
        userId: ByteArray = byteArrayOf(10),
        userName: String = "alice",
        site: PasskeySite = alpha,
    ): Outcome<JSONObject> {
        val request = CreateRequest(null, userId, userName, "", challenge, listOf(-7), emptyList(), "none", false, prf)
        val outcomes = mutableListOf<Outcome<JSONObject>>()
        authenticator.register(request, site) { outcomes += it }
        return outcomes.single()
    }

    private fun registered(prf: Boolean = true, userId: ByteArray = byteArrayOf(10), userName: String = "alice"): Pair<StoredPasskey, RegistrationData> {
        val credential = register(prf, userId, userName).credential()
        val data = webauthn.verifyRegistrationResponseJSON(
            credential.toString(),
            RegistrationParameters(
                server(alpha, challenge),
                listOf(PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
                true,
                true,
            ),
        )
        return passkeys.store.load().single { it.credentialId == credential.getString("id") } to data
    }

    private fun signIn(prf: PrfRequest?, allow: List<String> = emptyList(), site: PasskeySite = alpha, challenge: ByteArray = byteArrayOf(9)): Outcome<JSONObject> {
        val outcomes = mutableListOf<Outcome<JSONObject>>()
        authenticator.signIn(GetRequest(null, challenge, allow, prf), site) { outcomes += it }
        return outcomes.single()
    }

    private fun verifiedSignIn(registration: RegistrationData, credential: JSONObject, challenge: ByteArray = byteArrayOf(9)) {
        webauthn.verifyAuthenticationResponseJSON(
            credential.toString(),
            AuthenticationParameters(
                server(alpha, challenge),
                CredentialRecordImpl(
                    registration.attestationObject!!,
                    registration.collectedClientData!!,
                    registration.clientExtensions,
                    registration.transports,
                ),
                null,
                true,
                true,
            ),
        )
    }

    private fun Outcome<JSONObject>.credential(): JSONObject = when (this) {
        is Outcome.Ok -> value
        is Outcome.Failed -> throw AssertionError("expected a credential, got $error")
    }

    private fun Outcome<*>.error(): PasskeyError = (this as? Outcome.Failed)?.error
        ?: throw AssertionError("expected a refusal, got $this")

    private fun JSONObject.prf(): JSONObject? = getJSONObject("clientExtensionResults").optJSONObject("prf")

    private fun JSONObject.result(name: String): ByteArray? =
        prf()?.optJSONObject("results")?.optString(name)?.takeIf { it.isNotEmpty() }?.let(WebAuthn::fromBase64Url)

    private fun eval(first: String, second: String? = null) =
        PrfRequest(PrfInputs(first.toByteArray(), second?.toByteArray()), emptyMap())

    // --- registration ---

    @Test
    fun `registering with PRF makes a PRF key and says it is enabled`() {
        val (passkey, _) = registered(prf = true)
        assertTrue(passkey.prf)
        assertTrue(passkey.prfAlias in vault.prfKeys)
        val credential = passkeys.store.load().single()
        assertEquals(passkey, credential)
    }

    @Test
    fun `the registration's touch goes to the PRF key, and the signing key gets the window`() {
        val credential = register(prf = true).credential()
        assertTrue(credential.prf()!!.getBoolean("enabled"))
        assertEquals(1, ui.prompts.size)
        assertTrue(ui.operations.single() is KeyOperation.Hmac)
        assertEquals(setOf(passkeys.store.load().single().alias), vault.windowKeys)
    }

    @Test
    fun `a PRF registration still verifies`() {
        registered(prf = true)
    }

    @Test
    fun `registering without PRF keeps one strict key and says nothing about PRF`() {
        val credential = register(prf = false).credential()
        assertNull(credential.prf())
        assertTrue(vault.prfKeys.isEmpty())
        assertTrue(vault.windowKeys.isEmpty())
        assertTrue(ui.operations.single() is KeyOperation.Signing)
        assertFalse(passkeys.store.load().single().prf)
    }

    @Test
    fun `a phone that cannot make a PRF key registers without it, and says so`() {
        vault.refuseToCreatePrf = true
        val credential = register(prf = true).credential()
        assertFalse(credential.prf()!!.getBoolean("enabled"))
        assertFalse(passkeys.store.load().single().prf)
        assertTrue(vault.windowKeys.isEmpty())
    }

    @Test
    fun `refusing the prompt at a PRF registration leaves no keys`() {
        ui.verdict = FakePasskeyUi.Verdict.Refuse
        assertEquals("NotAllowedError", register(prf = true).error().name)
        assertTrue(vault.keys.isEmpty())
        assertTrue(vault.prfKeys.isEmpty())
        assertTrue(passkeys.store.load().isEmpty())
    }

    // --- results ---

    @Test
    fun `a sign-in returns the result the specification defines`() {
        val (passkey, registration) = registered()
        ui.prompts.clear()
        val credential = signIn(eval("notes")).credential()

        assertArrayEquals(expected(passkey, "notes".toByteArray()), credential.result("first"))
        assertNull(credential.result("second"))
        verifiedSignIn(registration, credential)
        assertEquals(1, ui.prompts.size)
    }

    @Test
    fun `both inputs are answered with one touch`() {
        val (passkey, _) = registered()
        ui.prompts.clear()
        val credential = signIn(eval("old", "new")).credential()

        assertArrayEquals(expected(passkey, "old".toByteArray()), credential.result("first"))
        assertArrayEquals(expected(passkey, "new".toByteArray()), credential.result("second"))
        assertEquals(1, ui.prompts.size)
    }

    @Test
    fun `the same passkey and input always give the same result`() {
        registered()
        val once = signIn(eval("notes")).credential().result("first")
        val again = signIn(eval("notes"), challenge = byteArrayOf(8)).credential().result("first")
        assertArrayEquals(once, again)
    }

    @Test
    fun `a result does not depend on which slot the input came in`() {
        // What rotation relies on: today's second is tomorrow's first.
        registered()
        val asSecond = signIn(eval("old", "new")).credential().result("second")
        val asFirst = signIn(eval("new")).credential().result("first")
        assertArrayEquals(asSecond, asFirst)
    }

    @Test
    fun `another input gives another result`() {
        registered()
        val a = signIn(eval("a")).credential().result("first")
        val b = signIn(eval("b")).credential().result("first")
        assertFalse(a!!.contentEquals(b))
    }

    @Test
    fun `another passkey gives another result for the same input`() {
        val (alice, _) = registered(userId = byteArrayOf(1), userName = "alice")
        val (bob, _) = registered(userId = byteArrayOf(2), userName = "bob")
        val forAlice = signIn(eval("notes"), allow = listOf(alice.credentialId)).credential().result("first")
        val forBob = signIn(eval("notes"), allow = listOf(bob.credentialId)).credential().result("first")
        assertFalse(forAlice!!.contentEquals(forBob))
    }

    @Test
    fun `per-passkey inputs take precedence over the general ones`() {
        val (passkey, _) = registered()
        val request = PrfRequest(
            eval = PrfInputs("general".toByteArray(), null),
            byCredential = mapOf(passkey.credentialId to PrfInputs("specific".toByteArray(), null)),
        )
        val credential = signIn(request, allow = listOf(passkey.credentialId)).credential()
        assertArrayEquals(expected(passkey, "specific".toByteArray()), credential.result("first"))
    }

    @Test
    fun `per-passkey inputs for another passkey leave this one with the general ones`() {
        val (alice, _) = registered(userId = byteArrayOf(1), userName = "alice")
        val (bob, _) = registered(userId = byteArrayOf(2), userName = "bob")
        val request = PrfRequest(
            eval = PrfInputs("general".toByteArray(), null),
            byCredential = mapOf(bob.credentialId to PrfInputs("bob only".toByteArray(), null)),
        )
        val credential = signIn(request, allow = listOf(alice.credentialId, bob.credentialId)).credential()
        // Two candidates: the fake chooser picks the first, alice.
        assertArrayEquals(expected(alice, "general".toByteArray()), credential.result("first"))
    }

    // --- passkeys and requests without PRF ---

    @Test
    fun `a passkey without PRF answers a PRF request with no results, and still signs in`() {
        registered(prf = false)
        val credential = signIn(eval("notes")).credential()
        assertNotNull(credential.prf())
        assertFalse(credential.prf()!!.has("results"))
    }

    @Test
    fun `a sign-in that does not ask for PRF hears nothing about it`() {
        val (_, registration) = registered()
        ui.prompts.clear()
        val credential = signIn(prf = null).credential()
        assertNull(credential.prf())
        verifiedSignIn(registration, credential)
        assertEquals(1, ui.prompts.size)
    }

    // --- the keys ---

    @Test
    fun `the signing key of a PRF passkey signs only on a fresh touch`() {
        // As if the window had passed: the Keystore refuses, and so must we.
        registered()
        ui.onApprove = { vault.touchedRecently = false }
        vault.touchedRecently = false
        assertEquals("NotAllowedError", signIn(eval("notes")).error().name)
    }

    @Test
    fun `an invalidated PRF key forgets the whole passkey`() {
        // Adding a fingerprint invalidates the strict PRF key but not the
        // window key; a passkey left half-working would just fail later.
        val (passkey, _) = registered()
        vault.invalidated += passkey.prfAlias
        ui.prompts.clear()

        val error = signIn(eval("notes")).error()
        assertEquals("NotAllowedError", error.name)
        assertTrue(error.message, error.message.contains("fingerprint"))
        assertTrue(ui.prompts.isEmpty())
        assertTrue(passkeys.store.load().isEmpty())
        assertTrue(vault.keys.isEmpty())
        assertTrue(vault.prfKeys.isEmpty())
    }

    @Test
    fun `another site's page gets no PRF from this passkey`() {
        registered()
        ui.prompts.clear()
        assertEquals("NotAllowedError", signIn(eval("notes"), site = beta).error().name)
        assertTrue(ui.prompts.isEmpty())
    }
}
