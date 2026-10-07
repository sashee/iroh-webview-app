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
import com.webauthn4j.verifier.exception.VerificationException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Whole ceremonies, checked by an implementation nobody here wrote.
 *
 * webauthn4j plays the server. If it accepts what the authenticator produced
 * -- origin, RP ID hash, challenge, flags, signature, attestation -- then a
 * server using any conforming library will. The rest of the tests are about
 * what must be refused, and that every refusal a page could trigger at will
 * happens before a fingerprint prompt does.
 */
@RunWith(RobolectricTestRunner::class)
class PasskeyAuthenticatorTest {

    private val passkeys = FakePasskeys(ApplicationProvider.getApplicationContext())
    private val ui = passkeys.ui
    private val authenticator = PasskeyAuthenticator(
        passkeys.store, passkeys.vault, ui, passkeys.platform.random, passkeys.platform.clock,
    )

    private val alpha = PasskeySite("alpha", 21000, "Alpha")
    private val beta = PasskeySite("beta", 21000, "Beta")

    // Not createNonStrictWebAuthnManager(): that one waves every attestation
    // format but "none" through unchecked, so "packed" would pass whatever
    // its signature was. These verify for real; the only leniency is
    // accepting self attestation, which is all an authenticator without a
    // vendor certificate can offer.
    private val webauthn = WebAuthnManager(
        listOf(NoneAttestationStatementVerifier(), PackedAttestationStatementVerifier()),
        NullCertPathTrustworthinessVerifier(),
        DefaultSelfAttestationTrustworthinessVerifier(),
    )

    // --- driving it ---

    private fun createRequest(
        challenge: ByteArray = byteArrayOf(1, 2, 3, 4),
        userId: ByteArray = byteArrayOf(10, 11),
        userName: String = "alice",
        rpId: String? = null,
        algorithms: List<Int> = listOf(-7, -257),
        exclude: List<String> = emptyList(),
        attestation: String = "none",
        credProps: Boolean = false,
    ) = CreateRequest(rpId, userId, userName, userName.replaceFirstChar(Char::uppercase), challenge, algorithms, exclude, attestation, credProps)

    private fun getRequest(
        challenge: ByteArray = byteArrayOf(5, 6, 7, 8),
        rpId: String? = null,
        allow: List<String> = emptyList(),
    ) = GetRequest(rpId, challenge, allow)

    private fun register(request: CreateRequest = createRequest(), site: PasskeySite = alpha): Outcome<JSONObject> {
        val outcomes = mutableListOf<Outcome<JSONObject>>()
        authenticator.register(request, site) { outcomes += it }
        return outcomes.single()
    }

    private fun signIn(request: GetRequest = getRequest(), site: PasskeySite = alpha): Outcome<JSONObject> {
        val outcomes = mutableListOf<Outcome<JSONObject>>()
        authenticator.signIn(request, site) { outcomes += it }
        return outcomes.single()
    }

    private fun Outcome<JSONObject>.credential(): JSONObject = when (this) {
        is Outcome.Ok -> value
        is Outcome.Failed -> throw AssertionError("expected a credential, got $error")
    }

    private fun Outcome<*>.error(): PasskeyError = (this as? Outcome.Failed)?.error
        ?: throw AssertionError("expected a refusal, got $this")

    // --- the server's side ---

    private fun server(site: PasskeySite, challenge: ByteArray) = ServerProperty.builder()
        .origin(Origin.create(site.origin))
        .rpId(site.host)
        .challenge(DefaultChallenge(challenge))
        .build()

    private fun verifyRegistration(credential: JSONObject, site: PasskeySite, challenge: ByteArray): RegistrationData =
        webauthn.verifyRegistrationResponseJSON(
            credential.toString(),
            RegistrationParameters(
                server(site, challenge),
                listOf(PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
                true,
                true,
            ),
        )

    private fun verifySignIn(credential: JSONObject, site: PasskeySite, challenge: ByteArray, registration: RegistrationData) {
        webauthn.verifyAuthenticationResponseJSON(
            credential.toString(),
            AuthenticationParameters(
                server(site, challenge),
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

    private fun registered(request: CreateRequest = createRequest(), site: PasskeySite = alpha): RegistrationData =
        verifyRegistration(register(request, site).credential(), site, request.challenge)

    // --- registration ---

    @Test
    fun `a registration verifies as a user-verified ES256 passkey for the page's own host`() {
        val registration = registered()
        val authenticatorData = registration.attestationObject!!.authenticatorData
        assertTrue(authenticatorData.isFlagUV)
        assertTrue(authenticatorData.isFlagUP)
        assertEquals("none", registration.attestationObject!!.format)
    }

    @Test
    fun `the client data carries the proxy's origin, port included`() {
        val credential = register().credential()
        val clientData = JSONObject(
            String(WebAuthn.fromBase64Url(credential.getJSONObject("response").getString("clientDataJSON"))!!),
        )
        assertEquals("http://alpha.localhost:21000", clientData.getString("origin"))
        assertEquals("webauthn.create", clientData.getString("type"))
    }

    @Test
    fun `a server that names the host as RP ID gets the same passkey`() {
        verifyRegistration(register(createRequest(rpId = "alpha.localhost")).credential(), alpha, byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun `asking for attestation gets packed self attestation, which verifies`() {
        val registration = registered(createRequest(attestation = "direct"))
        assertEquals("packed", registration.attestationObject!!.format)
    }

    @Test
    fun `the response is what a browser's toJSON would give`() {
        val credential = register(createRequest(credProps = true)).credential()
        assertEquals(credential.getString("id"), credential.getString("rawId"))
        assertEquals("public-key", credential.getString("type"))
        assertEquals("platform", credential.getString("authenticatorAttachment"))
        val response = credential.getJSONObject("response")
        assertEquals(-7, response.getInt("publicKeyAlgorithm"))
        assertEquals("internal", response.getJSONArray("transports").getString(0))
        assertTrue(credential.getJSONObject("clientExtensionResults").getJSONObject("credProps").getBoolean("rk"))
    }

    @Test
    fun `a registration verified for another origin or challenge is rejected`() {
        // The checks above would pass vacuously if the verifier were lenient.
        val credential = register().credential()
        assertThrows(VerificationException::class.java) { verifyRegistration(credential, beta, byteArrayOf(1, 2, 3, 4)) }
        assertThrows(VerificationException::class.java) { verifyRegistration(credential, alpha, byteArrayOf(9)) }
    }

    @Test
    fun `the passkey is remembered under the host, for the user`() {
        register()
        val stored = passkeys.store.load().single()
        assertEquals("alpha.localhost", stored.rpId)
        assertEquals(WebAuthn.base64Url(byteArrayOf(10, 11)), stored.userHandle)
        assertEquals("alice", stored.userName)
        assertTrue(stored.alias in passkeys.vault.keys)
    }

    @Test
    fun `the prompt says what is being created, for whom, and where`() {
        register()
        assertEquals(listOf(FakePasskeyUi.Prompt(PasskeyPurpose.Register, "Alice", "Alpha")), ui.prompts)
    }

    @Test
    fun `naming another RP ID is refused before any prompt`() {
        assertEquals("SecurityError", register(createRequest(rpId = "beta.localhost")).error().name)
        assertTrue(ui.prompts.isEmpty())
        assertTrue(passkeys.vault.keys.isEmpty())
    }

    @Test
    fun `a server that does not accept ES256 is refused before any prompt`() {
        assertEquals("NotSupportedError", register(createRequest(algorithms = listOf(-257, -8))).error().name)
        assertTrue(ui.prompts.isEmpty())
    }

    @Test
    fun `a server offering no algorithms gets ES256, the default`() {
        registered(createRequest(algorithms = emptyList()))
    }

    @Test
    fun `an account that already has this phone's passkey is refused before any prompt`() {
        val first = register().credential().getString("id")
        ui.prompts.clear()
        assertEquals("InvalidStateError", register(createRequest(exclude = listOf(first))).error().name)
        assertTrue(ui.prompts.isEmpty())
        assertEquals(1, passkeys.store.load().size)
    }

    @Test
    fun `excluding another site's passkey does not block this one`() {
        val betaId = register(site = beta).credential().getString("id")
        registered(createRequest(exclude = listOf(betaId)))
    }

    @Test
    fun `refusing the prompt stores nothing and keeps no key`() {
        ui.verdict = FakePasskeyUi.Verdict.Refuse
        assertEquals("NotAllowedError", register().error().name)
        assertTrue(passkeys.store.load().isEmpty())
        assertTrue(passkeys.vault.keys.isEmpty())
    }

    @Test
    fun `a phone that cannot make a key refuses without a prompt`() {
        passkeys.vault.refuseToCreate = true
        assertEquals("NotAllowedError", register().error().name)
        assertTrue(ui.prompts.isEmpty())
        assertTrue(passkeys.store.load().isEmpty())
    }

    @Test
    fun `withdrawing a registration prompt stores nothing and keeps no key`() {
        ui.verdict = FakePasskeyUi.Verdict.Hold
        val outcomes = mutableListOf<Outcome<JSONObject>>()
        val cancel = authenticator.register(createRequest(), alpha) { outcomes += it }
        cancel()
        assertEquals("NotAllowedError", outcomes.single().error().name)
        assertTrue(passkeys.store.load().isEmpty())
        assertTrue(passkeys.vault.keys.isEmpty())
    }

    // --- sign-in ---

    @Test
    fun `a sign-in verifies against the registered passkey`() {
        val registration = registered()
        val challenge = byteArrayOf(42, 43)
        verifySignIn(signIn(getRequest(challenge)).credential(), alpha, challenge, registration)
    }

    @Test
    fun `a sign-in returns the user handle, for usernameless login`() {
        registered()
        val response = signIn().credential().getJSONObject("response")
        assertArrayEquals(byteArrayOf(10, 11), WebAuthn.fromBase64Url(response.getString("userHandle")))
    }

    @Test
    fun `a sign-in verified for another site is rejected`() {
        val registration = registered()
        val credential = signIn(getRequest(byteArrayOf(1))).credential()
        assertThrows(VerificationException::class.java) {
            verifySignIn(credential, beta, byteArrayOf(1), registration)
        }
    }

    @Test
    fun `the sign-in prompt names the account and the endpoint`() {
        registered()
        ui.prompts.clear()
        signIn()
        assertEquals(listOf(FakePasskeyUi.Prompt(PasskeyPurpose.SignIn, "Alice", "Alpha")), ui.prompts)
    }

    @Test
    fun `another endpoint's page is not offered this passkey, and sees no prompt`() {
        registered()
        ui.prompts.clear()
        val outcome = signIn(site = beta)
        assertEquals("NotAllowedError", outcome.error().name)
        assertTrue(ui.prompts.isEmpty())
    }

    @Test
    fun `naming this endpoint's host from another endpoint's page is refused`() {
        // beta's page asking for alpha's passkeys by name.
        registered()
        assertEquals("SecurityError", signIn(getRequest(rpId = "alpha.localhost"), site = beta).error().name)
        assertEquals(1, ui.prompts.size) // only the registration's
    }

    @Test
    fun `the server's allow list narrows the choice`() {
        registered(createRequest(userId = byteArrayOf(1), userName = "alice"))
        val bob = register(createRequest(userId = byteArrayOf(2), userName = "bob")).credential().getString("id")
        val response = signIn(getRequest(allow = listOf(bob))).credential()
        assertEquals(bob, response.getString("id"))
        assertTrue(ui.chooserShown.isEmpty())
    }

    @Test
    fun `an allow list naming nothing on this phone is refused before any prompt`() {
        registered()
        ui.prompts.clear()
        assertEquals("NotAllowedError", signIn(getRequest(allow = listOf("bm90LWhlcmU"))).error().name)
        assertTrue(ui.prompts.isEmpty())
    }

    @Test
    fun `with two accounts the user chooses which`() {
        register(createRequest(userId = byteArrayOf(1), userName = "alice")).credential()
        register(createRequest(userId = byteArrayOf(2), userName = "bob")).credential()
        ui.choice = 1
        val response = signIn().credential().getJSONObject("response")
        assertEquals(listOf(listOf("Alice", "Bob")), ui.chooserShown)
        assertArrayEquals(byteArrayOf(2), WebAuthn.fromBase64Url(response.getString("userHandle")))
    }

    @Test
    fun `declining to choose an account is NotAllowed, without a prompt`() {
        register(createRequest(userId = byteArrayOf(1), userName = "alice"))
        register(createRequest(userId = byteArrayOf(2), userName = "bob"))
        ui.prompts.clear()
        ui.choice = null
        assertEquals("NotAllowedError", signIn().error().name)
        assertTrue(ui.prompts.isEmpty())
    }

    @Test
    fun `refusing the sign-in prompt is NotAllowed and keeps the passkey`() {
        registered()
        ui.verdict = FakePasskeyUi.Verdict.Refuse
        assertEquals("NotAllowedError", signIn().error().name)
        assertEquals(1, passkeys.store.load().size)
    }

    @Test
    fun `an invalidated key is forgotten, with a reason the user can act on`() {
        registered()
        val alias = passkeys.store.load().single().alias
        passkeys.vault.invalidated += alias
        ui.prompts.clear()

        val error = signIn().error()
        assertEquals("NotAllowedError", error.name)
        assertTrue(error.message, error.message.contains("fingerprint"))
        assertTrue(ui.prompts.isEmpty())
        assertTrue(passkeys.store.load().isEmpty())
        assertFalse(alias in passkeys.vault.keys)
    }

    @Test
    fun `withdrawing a sign-in prompt answers NotAllowed`() {
        registered()
        ui.verdict = FakePasskeyUi.Verdict.Hold
        val outcomes = mutableListOf<Outcome<JSONObject>>()
        val cancel = authenticator.signIn(getRequest(), alpha) { outcomes += it }
        assertTrue(outcomes.isEmpty())
        cancel()
        assertEquals("NotAllowedError", outcomes.single().error().name)
        assertEquals(1, ui.withdrawn)
    }
}
