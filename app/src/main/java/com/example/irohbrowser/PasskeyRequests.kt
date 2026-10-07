package com.example.irohbrowser

import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a WebAuthn call came from: the endpoint being browsed.
 *
 * Built from the running proxy, never from anything the page says. [origin]
 * is what goes into the client data a server checks, and [host] is the only
 * RP ID a page here may use.
 */
data class PasskeySite(val label: String, val port: Int, val endpointName: String) {
    val host: String get() = Origins.host(label)
    val origin: String get() = Origins.origin(label, port)
}

/** Why a WebAuthn call failed, named as the browser exception the page will see. */
data class PasskeyError(val name: String, val message: String) {
    companion object {
        fun notAllowed(message: String) = PasskeyError("NotAllowedError", message)
        fun security(message: String) = PasskeyError("SecurityError", message)
        fun notSupported(message: String) = PasskeyError("NotSupportedError", message)
        fun invalidState(message: String) = PasskeyError("InvalidStateError", message)
        fun type(message: String) = PasskeyError("TypeError", message)
        fun unknown(message: String) = PasskeyError("UnknownError", message)
    }
}

/** A result that is either a value or the error the page should see. */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Failed(val error: PasskeyError) : Outcome<Nothing>
}

/** `navigator.credentials.create`, as the injected script relays it. */
class CreateRequest(
    val rpId: String?,
    val userId: ByteArray,
    val userName: String,
    val userDisplayName: String,
    val challenge: ByteArray,
    /** COSE algorithms the server accepts, in its order of preference. Empty means the default. */
    val algorithms: List<Int>,
    val excludeCredentials: List<String>,
    val attestation: String,
    val credProps: Boolean,
)

/** `navigator.credentials.get`, as the injected script relays it. */
class GetRequest(
    val rpId: String?,
    val challenge: ByteArray,
    val allowCredentials: List<String>,
)

/**
 * Reading the script's messages, and the rules that decide what a page may do.
 *
 * Pure. The page can post to the bridge directly, bypassing the script, so
 * nothing the script checked is assumed to have been checked: every field is
 * validated again here.
 */
object PasskeyRequests {

    /** Server-chosen user ids are 1 to 64 bytes. */
    private val USER_ID_SIZE = 1..64

    fun parseCreate(options: JSONObject?): Outcome<CreateRequest> {
        options ?: return typeError("publicKey options are required")
        val rp = options.optJSONObject("rp") ?: return typeError("rp is required")
        val user = options.optJSONObject("user") ?: return typeError("user is required")
        val userId = user.bytes("id")?.takeIf { it.size in USER_ID_SIZE }
            ?: return typeError("user.id must be 1 to 64 bytes")
        val challenge = options.bytes("challenge") ?: return typeError("challenge is required")
        val parameters = options.optJSONArray("pubKeyCredParams")
            ?: return typeError("pubKeyCredParams is required")
        val algorithms = parameters.objects()
            .filter { it.text("type") == "public-key" }
            .map { it.optInt("alg", 0) }
        return Outcome.Ok(
            CreateRequest(
                rpId = rp.text("id"),
                userId = userId,
                userName = user.text("name").orEmpty(),
                userDisplayName = user.text("displayName").orEmpty(),
                challenge = challenge,
                algorithms = algorithms,
                excludeCredentials = credentialIds(options.optJSONArray("excludeCredentials")),
                attestation = options.text("attestation") ?: "none",
                credProps = options.optJSONObject("extensions")?.optBoolean("credProps") == true,
            ),
        )
    }

    fun parseGet(options: JSONObject?): Outcome<GetRequest> {
        options ?: return typeError("publicKey options are required")
        val challenge = options.bytes("challenge") ?: return typeError("challenge is required")
        return Outcome.Ok(
            GetRequest(
                rpId = options.text("rpId"),
                challenge = challenge,
                allowCredentials = credentialIds(options.optJSONArray("allowCredentials")),
            ),
        )
    }

    /**
     * The RP ID to use: the page's own host, whether or not the page named it.
     *
     * Stricter than a browser, which also accepts a parent domain of the host.
     * Each endpoint is exactly one host, so nothing legitimate needs a parent,
     * and refusing it keeps one rule: a page can only ever reach the passkeys
     * of the endpoint it was served by.
     */
    fun rpId(requested: String?, site: PasskeySite): Outcome<String> = when {
        requested == null || requested.isEmpty() -> Outcome.Ok(site.host)
        requested.equals(site.host, ignoreCase = true) -> Outcome.Ok(site.host)
        else -> Outcome.Failed(
            PasskeyError.security("The RP ID \"$requested\" is not this page's host, ${site.host}."),
        )
    }

    /** Whether ES256 is acceptable. An empty list means the specification's default, which includes it. */
    fun acceptsEs256(algorithms: List<Int>): Boolean =
        algorithms.isEmpty() || WebAuthn.ES256 in algorithms

    /** The passkeys a sign-in may use: this RP ID's, narrowed to the server's list if it gave one. */
    fun candidates(stored: List<StoredPasskey>, rpId: String, allowed: List<String>): List<StoredPasskey> =
        stored.filter { it.rpId == rpId && (allowed.isEmpty() || it.credentialId in allowed) }

    /** Whether the server already holds one of this device's passkeys for the RP ID. */
    fun alreadyRegistered(stored: List<StoredPasskey>, rpId: String, excluded: List<String>): Boolean =
        stored.any { it.rpId == rpId && it.credentialId in excluded }

    private fun typeError(message: String) = Outcome.Failed(PasskeyError.type(message))

    private fun credentialIds(array: JSONArray?): List<String> =
        array?.objects().orEmpty()
            .filter { it.text("type") == "public-key" }
            .mapNotNull { it.bytes("id") }
            .map(WebAuthn::base64Url)
}

/** A string member, or null when it is absent or JSON null -- `optString` reports null as "null". */
internal fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name)

/** A base64url member decoded, or null when absent or not base64url. */
private fun JSONObject.bytes(name: String): ByteArray? = text(name)?.let(WebAuthn::fromBase64Url)

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
