package com.example.irohbrowser

import java.security.interfaces.ECPublicKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * The authenticator, and the half of the browser that talks to it.
 *
 * A browser checks the page's request against the page's origin, then asks an
 * authenticator to sign. Here both halves are the app: the rules in
 * [PasskeyRequests] stand in for the browser's checks, the Keystore keys behind
 * [KeyVault] for the authenticator, and the origin comes from [PasskeySite] --
 * the running proxy -- so a page cannot claim to be anything else.
 *
 * Every refusal that does not need the user happens before the prompt, so a
 * page cannot raise a fingerprint prompt for a request that was going to fail.
 *
 * A passkey registered with PRF has two keys, and a touch unlocks exactly one
 * hardware operation. The touch goes to the PRF key, strictly: one HMAC,
 * which derives the passkey's PRF secret. The signing key accepts that touch
 * for a few seconds and signs straight after. PRF results are computed from
 * the secret by the specification's formula, and the secret is wiped -- so
 * however many inputs a sign-in asks about, it is still one touch, and every
 * evaluation still needs one.
 */
class PasskeyAuthenticator(
    private val store: PasskeyStore,
    private val vault: KeyVault,
    private val ui: PasskeyUi,
    private val random: (Int) -> ByteArray,
    private val clock: () -> Long,
) {

    /** `navigator.credentials.create`. Returns a way to cancel. */
    fun register(request: CreateRequest, site: PasskeySite, done: (Outcome<JSONObject>) -> Unit): () -> Unit {
        val rpId = when (val resolved = PasskeyRequests.rpId(request.rpId, site)) {
            is Outcome.Ok -> resolved.value
            is Outcome.Failed -> return fail(done, resolved.error)
        }
        if (!PasskeyRequests.acceptsEs256(request.algorithms)) {
            return fail(done, PasskeyError.notSupported("Passkeys here are ES256 (-7), which the server did not offer."))
        }
        if (PasskeyRequests.alreadyRegistered(store.load(), rpId, request.excludeCredentials)) {
            return fail(done, PasskeyError.invalidState("This phone already has a passkey for this account."))
        }

        val credentialId = random(CREDENTIAL_ID_SIZE)
        val unnamed = StoredPasskey(
            credentialId = WebAuthn.base64Url(credentialId),
            rpId = rpId,
            userHandle = WebAuthn.base64Url(request.userId),
            userName = request.userName,
            userDisplayName = request.userDisplayName,
            created = clock(),
        )
        // The PRF key first: if this phone cannot make one, the passkey is
        // made without PRF -- and so with the strict signing key -- and the
        // site is told PRF is not enabled.
        val prf = request.prf && runCatching { vault.createPrf(unnamed.prfAlias) }.isSuccess
        val passkey = unnamed.copy(prf = prf)
        val publicKey = try {
            vault.create(passkey.alias, touchWindow = prf)
        } catch (cause: Exception) {
            vault.delete(passkey.prfAlias)
            return fail(done, PasskeyError.notAllowed("No key could be created (${cause.message}). Is a fingerprint set up?"))
        }
        val operation = touchOperation(passkey) ?: run {
            forgetKeys(passkey)
            return fail(done, PasskeyError.unknown("The new key could not be used."))
        }

        val clientData = WebAuthn.clientDataJson("webauthn.create", request.challenge, site.origin)
        val authenticatorData = WebAuthn.authenticatorData(
            rpId,
            WebAuthn.FLAG_USER_PRESENT or WebAuthn.FLAG_USER_VERIFIED or WebAuthn.FLAG_ATTESTED_CREDENTIAL_DATA,
            WebAuthn.attestedCredentialData(credentialId, publicKey),
        )
        // Signed even when the attestation is "none", where nothing carries the
        // signature: it is what makes the user-verified flag true rather than
        // claimed, and it proves the new keys work before the server is told
        // they exist.
        val signed = WebAuthn.signedData(authenticatorData, clientData)
        return ui.verify(PasskeyPurpose.Register, account(passkey), site.endpointName, operation) { unlocked ->
            when (val touched = unlocked.then { afterTouch(it, passkey, signed) }) {
                is Outcome.Failed -> {
                    forgetKeys(passkey)
                    done(touched)
                }
                is Outcome.Ok -> {
                    touched.value.secret?.fill(0)
                    store.update { it + passkey }
                    val attestation = if (request.attestation == "none") {
                        WebAuthn.noneAttestation(authenticatorData)
                    } else {
                        WebAuthn.packedSelfAttestation(authenticatorData, touched.value.signature)
                    }
                    done(
                        Outcome.Ok(
                            CredentialJson.registration(
                                credentialId, clientData, attestation, authenticatorData, publicKey,
                                credProps = request.credProps,
                                prf = if (request.prf) prf else null,
                            ),
                        ),
                    )
                }
            }
        }
    }

    /** `navigator.credentials.get`. Returns a way to cancel. */
    fun signIn(request: GetRequest, site: PasskeySite, done: (Outcome<JSONObject>) -> Unit): () -> Unit {
        val rpId = when (val resolved = PasskeyRequests.rpId(request.rpId, site)) {
            is Outcome.Ok -> resolved.value
            is Outcome.Failed -> return fail(done, resolved.error)
        }
        val candidates = PasskeyRequests.candidates(store.load(), rpId, request.allowCredentials)
        if (candidates.isEmpty()) {
            return fail(done, PasskeyError.notAllowed("This phone has no passkey for $rpId."))
        }
        if (candidates.size == 1) return signInWith(candidates.single(), request, rpId, site, done)

        // The chooser's answer starts the prompt, so what cancelling means
        // changes once it has been given -- possibly before choose() returns.
        var afterChoosing: (() -> Unit)? = null
        val dismiss = ui.choose(site.endpointName, candidates.map(::account)) { index ->
            afterChoosing = if (index == null) {
                fail(done, PasskeyError.notAllowed("No account was chosen."))
            } else {
                signInWith(candidates[index], request, rpId, site, done)
            }
        }
        return { (afterChoosing ?: dismiss)() }
    }

    private fun signInWith(
        passkey: StoredPasskey,
        request: GetRequest,
        rpId: String,
        site: PasskeySite,
        done: (Outcome<JSONObject>) -> Unit,
    ): () -> Unit {
        val operation = touchOperation(passkey) ?: run {
            // Gone for good: an invalidated key never comes back, and offering
            // the passkey again would only fail again.
            store.forget(passkey.credentialId, vault)
            return fail(
                done,
                PasskeyError.notAllowed(
                    "This passkey stopped working -- adding a fingerprint invalidates them. Sign in another way and create a new one.",
                ),
            )
        }
        val clientData = WebAuthn.clientDataJson("webauthn.get", request.challenge, site.origin)
        val authenticatorData = WebAuthn.authenticatorData(
            rpId,
            WebAuthn.FLAG_USER_PRESENT or WebAuthn.FLAG_USER_VERIFIED,
        )
        val signed = WebAuthn.signedData(authenticatorData, clientData)
        val inputs = request.prf?.inputsFor(passkey.credentialId)
        return ui.verify(PasskeyPurpose.SignIn, account(passkey), site.endpointName, operation) { unlocked ->
            done(
                when (val touched = unlocked.then { afterTouch(it, passkey, signed) }) {
                    is Outcome.Failed -> touched
                    is Outcome.Ok -> {
                        val secret = touched.value.secret
                        val results = if (secret != null && inputs != null) prfResults(secret, inputs) else null
                        secret?.fill(0)
                        Outcome.Ok(
                            CredentialJson.assertion(
                                passkey, clientData, authenticatorData, touched.value.signature,
                                prf = if (request.prf != null) results ?: emptyMap() else null,
                            ),
                        )
                    }
                },
            )
        }
    }

    /**
     * What the touch is tied to: the PRF key for a passkey that has one, the
     * signing key otherwise. Null when that key is gone or invalidated.
     */
    private fun touchOperation(passkey: StoredPasskey): KeyOperation? =
        if (passkey.prf) {
            vault.prf(passkey.prfAlias)?.let(KeyOperation::Hmac)
        } else {
            vault.signer(passkey.alias)?.let(KeyOperation::Signing)
        }

    /** What one touch yields: the signature, and for a passkey with PRF, its PRF secret. */
    private class Touched(val signature: ByteArray, val secret: ByteArray?)

    private fun afterTouch(unlocked: KeyOperation, passkey: StoredPasskey, signed: ByteArray): Outcome<Touched> =
        runCatching {
            when (unlocked) {
                is KeyOperation.Signing -> {
                    unlocked.signature.update(signed)
                    Touched(unlocked.signature.sign(), secret = null)
                }
                is KeyOperation.Hmac -> {
                    val secret = unlocked.mac.doFinal(WebAuthn.PRF_SECRET_MESSAGE.toByteArray(Charsets.US_ASCII))
                    // Primed only now: priming the window key is what needs
                    // the touch that just happened.
                    val signer = vault.signer(passkey.alias) ?: error("the signing key is gone")
                    signer.update(signed)
                    Touched(signer.sign(), secret)
                }
            }
        }.fold(
            onSuccess = { Outcome.Ok(it) },
            onFailure = { Outcome.Failed(PasskeyError.notAllowed("The key would not sign: ${it.message}")) },
        )

    private fun prfResults(secret: ByteArray, inputs: PrfInputs): Map<String, ByteArray> =
        listOfNotNull(
            "first" to WebAuthn.prfResult(secret, inputs.first),
            inputs.second?.let { "second" to WebAuthn.prfResult(secret, it) },
        ).toMap()

    private fun forgetKeys(passkey: StoredPasskey) {
        vault.delete(passkey.alias)
        vault.delete(passkey.prfAlias)
    }

    private fun fail(done: (Outcome<JSONObject>) -> Unit, error: PasskeyError): () -> Unit {
        done(Outcome.Failed(error))
        return {}
    }

    private companion object {
        const val CREDENTIAL_ID_SIZE = 16

        fun account(passkey: StoredPasskey): String =
            passkey.userDisplayName.ifBlank { passkey.userName }

        fun <T> Outcome<KeyOperation>.then(next: (KeyOperation) -> Outcome<T>): Outcome<T> = when (this) {
            is Outcome.Failed -> this
            is Outcome.Ok -> next(value)
        }
    }
}

/**
 * The credential as the page receives it: WebAuthn's own JSON form
 * (RegistrationResponseJSON and AuthenticationResponseJSON), which the injected
 * script turns into objects and hands back unchanged from `toJSON()`.
 */
internal object CredentialJson {

    /** [prf] is whether a PRF key was made, or null when the site did not ask. */
    fun registration(
        credentialId: ByteArray,
        clientData: ByteArray,
        attestationObject: ByteArray,
        authenticatorData: ByteArray,
        publicKey: ECPublicKey,
        credProps: Boolean,
        prf: Boolean?,
    ): JSONObject = credential(credentialId)
        .put(
            "response",
            JSONObject()
                .put("clientDataJSON", WebAuthn.base64Url(clientData))
                .put("attestationObject", WebAuthn.base64Url(attestationObject))
                .put("authenticatorData", WebAuthn.base64Url(authenticatorData))
                .put("publicKey", WebAuthn.base64Url(publicKey.encoded))
                .put("publicKeyAlgorithm", WebAuthn.ES256)
                .put("transports", JSONArray(listOf("internal"))),
        )
        .put(
            "clientExtensionResults",
            JSONObject().apply {
                if (credProps) put("credProps", JSONObject().put("rk", true))
                if (prf != null) put("prf", JSONObject().put("enabled", prf))
            },
        )

    /**
     * [prf] is the PRF results by name ("first", "second"), empty when the
     * passkey has none to give, or null when the site did not ask.
     */
    fun assertion(
        passkey: StoredPasskey,
        clientData: ByteArray,
        authenticatorData: ByteArray,
        signature: ByteArray,
        prf: Map<String, ByteArray>?,
    ): JSONObject = credential(WebAuthn.fromBase64Url(passkey.credentialId)!!)
        .put(
            "response",
            JSONObject()
                .put("clientDataJSON", WebAuthn.base64Url(clientData))
                .put("authenticatorData", WebAuthn.base64Url(authenticatorData))
                .put("signature", WebAuthn.base64Url(signature))
                .put("userHandle", passkey.userHandle),
        )
        .put(
            "clientExtensionResults",
            JSONObject().apply {
                if (prf != null) {
                    put(
                        "prf",
                        if (prf.isEmpty()) {
                            JSONObject()
                        } else {
                            JSONObject().put(
                                "results",
                                JSONObject().apply { prf.forEach { (name, value) -> put(name, WebAuthn.base64Url(value)) } },
                            )
                        },
                    )
                }
            },
        )

    private fun credential(credentialId: ByteArray): JSONObject = JSONObject()
        .put("id", WebAuthn.base64Url(credentialId))
        .put("rawId", WebAuthn.base64Url(credentialId))
        .put("type", "public-key")
        .put("authenticatorAttachment", "platform")
}
