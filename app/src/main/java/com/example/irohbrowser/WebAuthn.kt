package com.example.irohbrowser

import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * The bytes of WebAuthn: what an authenticator writes and a server verifies.
 *
 * Pure functions, and deliberately small. Everything here is something a
 * browser would normally produce; since the app stands in for the browser and
 * the authenticator at once, it has to produce them itself. `WebAuthnTest`
 * checks each against the specification's layout, and
 * `PasskeyAuthenticatorTest` checks the assembled result with an independent
 * verifier.
 */
object WebAuthn {

    /** COSE algorithm identifier for ECDSA over P-256 with SHA-256 -- the one algorithm offered. */
    const val ES256 = -7

    const val FLAG_USER_PRESENT = 0x01
    const val FLAG_USER_VERIFIED = 0x04
    const val FLAG_ATTESTED_CREDENTIAL_DATA = 0x40

    /** Base64url without padding, the encoding WebAuthn uses wherever bytes meet JSON. */
    fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Null for anything that is not base64url. Padding is tolerated, as browsers tolerate it. */
    fun fromBase64Url(text: String): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * The client data a signature covers.
     *
     * Members in the order the specification serialises them, with no
     * whitespace, so servers using its "limited verification algorithm" -- a
     * prefix match rather than a JSON parse -- accept it too. [origin] is the
     * proxy's origin, never anything the page said.
     */
    fun clientDataJson(type: String, challenge: ByteArray, origin: String): ByteArray =
        buildString {
            append("""{"type":""").append(jsonString(type))
            append(""","challenge":""").append(jsonString(base64Url(challenge)))
            append(""","origin":""").append(jsonString(origin))
            append(""","crossOrigin":false}""")
        }.toByteArray(Charsets.UTF_8)

    /**
     * Authenticator data: RP ID hash, flags, signature counter, and -- at
     * registration -- the attested credential data.
     *
     * The counter is always zero, which the specification reserves for an
     * authenticator that does not count. A counter kept beside the key in app
     * storage would protect against nothing the hardware-bound key does not.
     */
    fun authenticatorData(rpId: String, flags: Int, attested: ByteArray = ByteArray(0)): ByteArray =
        sha256(rpId.toByteArray(Charsets.UTF_8)) +
            byteArrayOf(flags.toByte()) +
            ByteArray(4) +
            attested

    /**
     * The credential being registered: AAGUID, id, public key.
     *
     * The AAGUID is all zeros, the specification's "unknown model": there is no
     * registered model to name, and a made-up one would be a claim nobody can
     * check.
     */
    fun attestedCredentialData(credentialId: ByteArray, publicKey: ECPublicKey): ByteArray =
        ByteArray(16) +
            byteArrayOf((credentialId.size shr 8).toByte(), credentialId.size.toByte()) +
            credentialId +
            coseKey(publicKey)

    /** A P-256 public key as a COSE_Key, map keys in CTAP2 canonical order. */
    fun coseKey(publicKey: ECPublicKey): ByteArray =
        Cbor.map(
            Cbor.int(1) to Cbor.int(2), // kty: EC2
            Cbor.int(3) to Cbor.int(ES256.toLong()), // alg
            Cbor.int(-1) to Cbor.int(1), // crv: P-256
            Cbor.int(-2) to Cbor.bytes(coordinate(publicKey.w.affineX)),
            Cbor.int(-3) to Cbor.bytes(coordinate(publicKey.w.affineY)),
        )

    /** The "none" attestation: the server learns the key, and no claim about where it lives. */
    fun noneAttestation(authenticatorData: ByteArray): ByteArray =
        Cbor.map(
            Cbor.text("fmt") to Cbor.text("none"),
            Cbor.text("attStmt") to Cbor.map(),
            Cbor.text("authData") to Cbor.bytes(authenticatorData),
        )

    /**
     * "packed" self attestation: the new key signing its own registration.
     *
     * Proves possession of the key and nothing about the hardware, which is
     * the most an authenticator without a vendor certificate can honestly
     * offer a server that asked for more than "none".
     */
    fun packedSelfAttestation(authenticatorData: ByteArray, signature: ByteArray): ByteArray =
        Cbor.map(
            Cbor.text("fmt") to Cbor.text("packed"),
            Cbor.text("attStmt") to Cbor.map(
                Cbor.text("alg") to Cbor.int(ES256.toLong()),
                Cbor.text("sig") to Cbor.bytes(signature),
            ),
            Cbor.text("authData") to Cbor.bytes(authenticatorData),
        )

    /** What an assertion or a self attestation signs. */
    fun signedData(authenticatorData: ByteArray, clientDataJson: ByteArray): ByteArray =
        authenticatorData + sha256(clientDataJson)

    /** A coordinate as exactly 32 bytes, big-endian. `BigInteger` gives a sign byte or too few. */
    private fun coordinate(value: BigInteger): ByteArray {
        val raw = value.toByteArray().takeLast(32).toByteArray()
        return ByteArray(32 - raw.size) + raw
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }
}

/**
 * The few CBOR shapes WebAuthn needs: integers, byte and text strings, maps.
 *
 * Map entries are written in the order given; callers give them in CTAP2
 * canonical order (shorter keys first, then bytewise), which is what servers
 * expect of an authenticator.
 */
object Cbor {
    fun int(value: Long): ByteArray =
        if (value >= 0) head(0, value) else head(1, -1 - value)

    fun int(value: Int): ByteArray = int(value.toLong())

    fun bytes(value: ByteArray): ByteArray = head(2, value.size.toLong()) + value

    fun text(value: String): ByteArray =
        value.toByteArray(Charsets.UTF_8).let { head(3, it.size.toLong()) + it }

    fun map(vararg entries: Pair<ByteArray, ByteArray>): ByteArray =
        entries.fold(head(5, entries.size.toLong())) { out, (key, value) -> out + key + value }

    private fun head(major: Int, argument: Long): ByteArray {
        val type = major shl 5
        return when {
            argument < 24 -> byteArrayOf((type or argument.toInt()).toByte())
            argument < 0x100 -> byteArrayOf((type or 24).toByte(), argument.toByte())
            argument < 0x10000 -> byteArrayOf((type or 25).toByte()) + bigEndian(argument, 2)
            argument < 0x100000000 -> byteArrayOf((type or 26).toByte()) + bigEndian(argument, 4)
            else -> byteArrayOf((type or 27).toByte()) + bigEndian(argument, 8)
        }
    }

    private fun bigEndian(value: Long, size: Int): ByteArray =
        ByteArray(size) { index -> (value shr (8 * (size - 1 - index))).toByte() }
}
