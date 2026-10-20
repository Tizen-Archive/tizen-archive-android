package dev.tizenarchive.companion.signing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.tizenarchive.companion.build.SigningKey
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/**
 * The signing key of the helper apps lives in the Android Keystore and never leaves the device.
 * Android only accepts an update of a helper app when it is signed by the same key, so the key is kept
 * for as long as this app's data exists.
 */
internal class KeystoreSigningKey {
    @Synchronized
    fun get(): SigningKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (!store.containsAlias(ALIAS)) generate()
        val key = store.getKey(ALIAS, null) as PrivateKey
        val chain = store.getCertificateChain(ALIAS).map { it as X509Certificate }
        return SigningKey(key, chain)
    }

    fun fingerprint(): String {
        val certificate = get().certificates.first()
        return MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }
    }

    private fun generate() {
        val notBefore = Calendar.getInstance()
        val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, VALID_YEARS) }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setKeySize(KEY_SIZE)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=Tizen Companion Installer"))
            .setCertificateNotBefore(notBefore.time)
            .setCertificateNotAfter(notAfter.time)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "companion-signing"
        const val KEY_SIZE = 2048
        const val VALID_YEARS = 30
    }
}
