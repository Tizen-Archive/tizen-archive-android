package dev.tizenarchive.companion.sdb

import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/** The host key of the sdb session. The private part may live in the Android Keystore and never leave it. */
public interface SdbSigner {
    public val publicKey: RSAPublicKey

    /** Signs the 20-byte token from the watch: RSA PKCS#1 v1.5 with a SHA-1 DigestInfo around the token (no further hashing). */
    public fun sign(token: ByteArray): ByteArray
}

/** [SdbSigner] over a JCA key pair; the private key can be an Android Keystore key created for `DIGEST_NONE` and PKCS#1 padding. */
public class JcaSdbSigner(private val privateKey: PrivateKey, override val publicKey: RSAPublicKey) : SdbSigner {
    override fun sign(token: ByteArray): ByteArray {
        val signature = Signature.getInstance("NONEwithRSA")
        signature.initSign(privateKey)
        signature.update(SHA1_DIGEST_INFO)
        signature.update(token)
        return signature.sign()
    }

    private companion object {
        val SHA1_DIGEST_INFO = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
    }
}
