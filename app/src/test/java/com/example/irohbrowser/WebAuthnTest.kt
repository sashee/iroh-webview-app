package com.example.irohbrowser

import java.math.BigInteger
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bytes, against the specifications that define them.
 *
 * The CBOR cases are RFC 8949's own examples (Appendix A). The layouts are
 * WebAuthn's, field by field. `PasskeyAuthenticatorTest` checks the assembled
 * result with an independent verifier; these say which field is wrong when
 * that fails.
 */
class WebAuthnTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `cbor integers match RFC 8949`() {
        mapOf(
            0L to "00", 1L to "01", 10L to "0a", 23L to "17", 24L to "1818", 25L to "1819",
            100L to "1864", 1000L to "1903e8", 1000000L to "1a000f4240",
            1000000000000L to "1b000000e8d4a51000",
            -1L to "20", -10L to "29", -100L to "3863", -1000L to "3903e7",
        ).forEach { (value, expected) -> assertEquals("$value", expected, hex(Cbor.int(value))) }
    }

    @Test
    fun `cbor strings match RFC 8949`() {
        assertEquals("40", hex(Cbor.bytes(ByteArray(0))))
        assertEquals("4401020304", hex(Cbor.bytes(bytes("01020304"))))
        assertEquals("60", hex(Cbor.text("")))
        assertEquals("6161", hex(Cbor.text("a")))
        assertEquals("6449455446", hex(Cbor.text("IETF")))
        assertEquals("62c3bc", hex(Cbor.text("ü")))
    }

    @Test
    fun `cbor maps match RFC 8949`() {
        assertEquals("a0", hex(Cbor.map()))
        assertEquals(
            "a201020304",
            hex(Cbor.map(Cbor.int(1) to Cbor.int(2), Cbor.int(3) to Cbor.int(4))),
        )
    }

    @Test
    fun `long byte strings take a two-byte length`() {
        val encoded = Cbor.bytes(ByteArray(300))
        assertEquals("59012c", hex(encoded.copyOf(3)))
        assertEquals(303, encoded.size)
    }

    @Test
    fun `client data is the specification's serialisation`() {
        val json = WebAuthn.clientDataJson("webauthn.get", bytes("0102ff"), "http://abc.localhost:21000")
        assertEquals(
            """{"type":"webauthn.get","challenge":"AQL_","origin":"http://abc.localhost:21000","crossOrigin":false}""",
            String(json, Charsets.UTF_8),
        )
    }

    @Test
    fun `client data escapes what JSON requires`() {
        val json = WebAuthn.clientDataJson("webauthn.get", ByteArray(0), "http://a\"b\\c\u0001")
        assertEquals(
            """{"type":"webauthn.get","challenge":"","origin":"http://a\"b\\c\u0001","crossOrigin":false}""",
            String(json, Charsets.UTF_8),
        )
    }

    @Test
    fun `authenticator data is RP ID hash, flags, then a zero counter`() {
        val data = WebAuthn.authenticatorData("alpha.localhost", 0x05)
        assertEquals(37, data.size)
        assertArrayEquals(WebAuthn.sha256("alpha.localhost".toByteArray()), data.copyOfRange(0, 32))
        assertEquals(0x05, data[32].toInt())
        assertArrayEquals(ByteArray(4), data.copyOfRange(33, 37))
    }

    @Test
    fun `attested credential data is AAGUID, id length, id, key`() {
        val id = bytes("aabbccdd")
        val data = WebAuthn.attestedCredentialData(id, key(BigInteger.ONE, BigInteger.TWO))
        assertArrayEquals(ByteArray(16), data.copyOfRange(0, 16))
        assertEquals("0004", hex(data.copyOfRange(16, 18)))
        assertArrayEquals(id, data.copyOfRange(18, 22))
        assertArrayEquals(WebAuthn.coseKey(key(BigInteger.ONE, BigInteger.TWO)), data.copyOfRange(22, data.size))
    }

    @Test
    fun `the cose key is an EC2 P-256 ES256 key in canonical order`() {
        val x = BigInteger("11".repeat(32), 16)
        val y = BigInteger("22".repeat(32), 16)
        assertEquals(
            "a5" + "0102" + "0326" + "2001" + "215820" + "11".repeat(32) + "225820" + "22".repeat(32),
            hex(WebAuthn.coseKey(key(x, y))),
        )
    }

    @Test
    fun `cose coordinates are always 32 bytes`() {
        // BigInteger drops leading zeros and adds a sign byte when the top bit
        // is set; a coordinate must be neither shorter nor longer.
        val small = BigInteger.ONE
        val high = BigInteger("ff" + "00".repeat(31), 16)
        val encoded = hex(WebAuthn.coseKey(key(small, high)))
        assertEquals("215820" + "00".repeat(31) + "01", encoded.substring(14, 14 + 6 + 64))
        assertEquals("225820" + "ff" + "00".repeat(31), encoded.substring(84, 84 + 6 + 64))
    }

    @Test
    fun `none attestation has fmt, attStmt and authData in canonical order`() {
        assertEquals(
            "a3" + "63666d74" + "646e6f6e65" + "6761747453746d74" + "a0" + "686175746844617461" + "4201" + "02",
            hex(WebAuthn.noneAttestation(bytes("0102"))),
        )
    }

    @Test
    fun `packed self attestation carries alg and sig`() {
        assertEquals(
            "a3" + "63666d74" + "667061636b6564" +
                "6761747453746d74" + "a2" + "63616c67" + "26" + "63736967" + "4109" +
                "686175746844617461" + "4101",
            hex(WebAuthn.packedSelfAttestation(bytes("01"), bytes("09"))),
        )
    }

    @Test
    fun `what is signed is authenticator data then the client data hash`() {
        val authData = bytes("0102")
        val clientData = "{}".toByteArray()
        assertArrayEquals(authData + WebAuthn.sha256(clientData), WebAuthn.signedData(authData, clientData))
    }

    @Test
    fun `base64url has no padding and round-trips`() {
        assertEquals("AQL_", WebAuthn.base64Url(bytes("0102ff")))
        assertEquals("AQ", WebAuthn.base64Url(bytes("01")))
        assertArrayEquals(bytes("0102ff"), WebAuthn.fromBase64Url("AQL_"))
        assertArrayEquals(bytes("01"), WebAuthn.fromBase64Url("AQ=="))
        assertNull(WebAuthn.fromBase64Url("not base64!"))
    }

    private fun key(x: BigInteger, y: BigInteger): ECPublicKey = object : ECPublicKey {
        override fun getW() = ECPoint(x, y)
        override fun getParams(): ECParameterSpec = throw UnsupportedOperationException()
        override fun getAlgorithm() = "EC"
        override fun getFormat() = "X.509"
        override fun getEncoded() = ByteArray(0)
    }
}
