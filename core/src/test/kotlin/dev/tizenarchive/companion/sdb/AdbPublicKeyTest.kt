package dev.tizenarchive.companion.sdb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdbPublicKeyTest {
    private fun key(bits: Int = 2048): RSAPublicKey =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair().public as RSAPublicKey

    @Test
    fun `the struct holds the modulus, its Montgomery constants and the exponent`() {
        val rsa = key()
        val text = String(AdbPublicKey.encode(rsa, "phone@test"), Charsets.US_ASCII)
        assertTrue(text.endsWith(" phone@test\u0000"))
        val struct = ByteBuffer.wrap(Base64.getDecoder().decode(text.substringBefore(' '))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(4 + 4 + 256 + 256 + 4, struct.remaining())
        assertEquals(64, struct.getInt())
        val n0inv = struct.getInt().toLong() and 0xffffffffL
        val modulus = BigInteger(1, ByteArray(256).also { struct.get(it) }.reversedArray())
        val rr = BigInteger(1, ByteArray(256).also { struct.get(it) }.reversedArray())
        assertEquals(65537, struct.getInt())

        assertEquals(rsa.modulus, modulus)
        // n0inv * n[0] == -1 (mod 2^32)
        val low = modulus.mod(BigInteger.ONE.shiftLeft(32)).toLong()
        assertEquals(0xffffffffL, (n0inv * low) and 0xffffffffL)
        // rr == (2^2048)^2 mod n
        assertEquals(BigInteger.TWO.pow(4096).mod(modulus), rr)
    }

    @Test
    fun `only 2048-bit keys are accepted`() {
        assertThrows(IllegalArgumentException::class.java) { AdbPublicKey.encode(key(1024), "x") }
    }

    @Test
    fun `the name cannot smuggle control characters`() {
        assertThrows(IllegalArgumentException::class.java) { AdbPublicKey.encode(key(), "a\nb") }
    }

    @Test
    fun `a signature is the token under a SHA-1 DigestInfo and verifies with the public key`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val signer = JcaSdbSigner(pair.private, pair.public as RSAPublicKey)
        val token = ByteArray(20) { it.toByte() }
        val signature = signer.sign(token)
        val verifier = java.security.Signature.getInstance("NONEwithRSA")
        verifier.initVerify(pair.public)
        verifier.update(byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14))
        verifier.update(token)
        assertTrue(verifier.verify(signature))
    }
}
