package com.example.irohbrowser

import java.security.Signature
import java.security.interfaces.ECPublicKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * The authenticator, and the half of the browser that talks to it.
 *
 * A browser checks the page's request against the page's origin, then asks an
 * authenticator to sign. Here both halves are the app: the rules in
 * [PasskeyRequests] stand in for the browser's checks, the Keystore key behind
 * [KeyVault] for the authenticator, and the origin comes from [PasskeySite] --
 * the running proxy -- so a page cannot claim to be anything else.
 *
 * Every refusal that does not need the user happens before the prompt, so a
 * page cannot raise a fingerprint prompt for a request that was going to fail.
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
        val passkey = StoredPasskey(
            credentialId = WebAuthn.base64Url(credentialId),
            rpId = rpId,
            userHandle = WebAuthn.base64Url(request.userId),
            userName = request.userName,
            userDisplayName = request.userDisplayName,
            created = clock(),
        )
        val publicKey = try {
            vault.create(passkey.alias)
        } catch (cause: Exception) {
            return fail(done, PasskeyError.notAllowed("No key could be created (${cause.message}). Is a fingerprint set up?"))
        }
        val signer = vault.signer(passkey.alias) ?: run {
            vault.delete(passkey.alias)
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
        // claimed, and it proves the new key works before the server is told
        // it exists.
        val signed = WebAuthn.signedData(authenticatorData, clientData)
        return ui.verify(PasskeyPurpose.Register, account(passkey), site.endpointName, signer) { verified ->
            when (val signature = verified.sign(signed)) {
                is Outcome.Failed -> {
                    vault.delete(passkey.alias)
                    done(signature)
                }
                is Outcome.Ok -> {
                    store.update { it + passkey }
                    val attestation = if (request.attestation == "none") {
                        WebAuthn.noneAttestation(authenticatorData)
                    } else {
                        WebAuthn.packedSelfAttestation(authenticatorData, signature.value)
                    }
                    done(
                        Outcome.Ok(
                            CredentialJson.registration(
                                credentialId, clientData, attestation, authenticatorData, publicKey, request.credProps,
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
        val signer = vault.signer(passkey.alias) ?: run {
            // Gone for good: an invalidated key never comes back, and offering
            // it again would only fail again.
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
        return ui.verify(PasskeyPurpose.SignIn, account(passkey), site.endpointName, signer) { verified ->
            done(
                when (val signature = verified.sign(signed)) {
                    is Outcome.Failed -> signature
                    is Outcome.Ok -> Outcome.Ok(
                        CredentialJson.assertion(passkey, clientData, authenticatorData, signature.value),
                    )
                },
            )
        }
    }

    private fun fail(done: (Outcome<JSONObject>) -> Unit, error: PasskeyError): () -> Unit {
        done(Outcome.Failed(error))
        return {}
    }

    private companion object {
        const val CREDENTIAL_ID_SIZE = 16

        fun account(passkey: StoredPasskey): String =
            passkey.userDisplayName.ifBlank { passkey.userName }

        fun Outcome<Signature>.sign(data: ByteArray): Outcome<ByteArray> = when (this) {
            is Outcome.Failed -> this
            is Outcome.Ok -> runCatching {
                value.update(data)
                value.sign()
            }.fold(
                onSuccess = { Outcome.Ok(it) },
                onFailure = { Outcome.Failed(PasskeyError.notAllowed("The key would not sign: ${it.message}")) },
            )
        }
    }
}

/**
 * The credential as the page receives it: WebAuthn's own JSON form
 * (RegistrationResponseJSON and AuthenticationResponseJSON), which the injected
 * script turns into objects and hands back unchanged from `toJSON()`.
 */
internal object CredentialJson {

    fun registration(
        credentialId: ByteArray,
        clientData: ByteArray,
        attestationObject: ByteArray,
        authenticatorData: ByteArray,
        publicKey: ECPublicKey,
        credProps: Boolean,
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
            if (credProps) JSONObject().put("credProps", JSONObject().put("rk", true)) else JSONObject(),
        )

    fun assertion(
        passkey: StoredPasskey,
        clientData: ByteArray,
        authenticatorData: ByteArray,
        signature: ByteArray,
    ): JSONObject = credential(WebAuthn.fromBase64Url(passkey.credentialId)!!)
        .put(
            "response",
            JSONObject()
                .put("clientDataJSON", WebAuthn.base64Url(clientData))
                .put("authenticatorData", WebAuthn.base64Url(authenticatorData))
                .put("signature", WebAuthn.base64Url(signature))
                .put("userHandle", passkey.userHandle),
        )
        .put("clientExtensionResults", JSONObject())

    private fun credential(credentialId: ByteArray): JSONObject = JSONObject()
        .put("id", WebAuthn.base64Url(credentialId))
        .put("rawId", WebAuthn.base64Url(credentialId))
        .put("type", "public-key")
        .put("authenticatorAttachment", "platform")
}
