package dev.tizenarchive.companion.sdb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * The public key format the watch's sdbd accepts in an AUTH_RSAPUBLICKEY packet: the Android `RSAPublicKey` struct
 * (little-endian) in Base64, a space and a name, NUL-terminated.
 */
internal object AdbPublicKey {
    private const val MODULUS_BITS = 2048
    private const val MODULUS_BYTES = MODULUS_BITS / 8
    private val TWO_POW_32 = BigInteger.ONE.shiftLeft(32)

    fun encode(key: RSAPublicKey, name: String): ByteArray {
        val n = key.modulus
        require(n.bitLength() in (MODULUS_BITS - 7)..MODULUS_BITS) { "Only 2048-bit RSA keys are supported" }
        val r = BigInteger.ONE.shiftLeft(MODULUS_BITS)
        val rr = r.multiply(r).mod(n)
        val n0inv = n.mod(TWO_POW_32).modInverse(TWO_POW_32).negate().mod(TWO_POW_32)
        val struct = ByteBuffer.allocate(4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MODULUS_BYTES / 4)
            .putInt(n0inv.toInt())
            .put(littleEndian(n))
            .put(littleEndian(rr))
            .putInt(key.publicExponent.toInt())
            .array()
        require(name.none { it.code < 0x20 || it.code > 0x7e }) { "The key name must be printable ASCII" }
        return (Base64.getEncoder().encodeToString(struct) + " " + name + "\u0000").toByteArray(Charsets.US_ASCII)
    }

    private fun littleEndian(value: BigInteger): ByteArray {
        val big = value.toByteArray()
        val out = ByteArray(MODULUS_BYTES)
        for (i in out.indices) {
            val j = big.size - 1 - i
            if (j >= 0) out[i] = big[j]
        }
        return out
    }
}
