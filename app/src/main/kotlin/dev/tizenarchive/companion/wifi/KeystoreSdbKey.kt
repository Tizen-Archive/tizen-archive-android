package dev.tizenarchive.companion.wifi

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.tizenarchive.companion.sdb.JcaSdbSigner
import dev.tizenarchive.companion.sdb.SdbSigner
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * The host key the watch learns when its user confirms "Allow debugging". It lives in the Android Keystore and never leaves
 * the device; the watch keeps it in its list, so the key is kept for as long as this app's data exists.
 */
internal class KeystoreSdbKey {
    @Synchronized
    fun signer(): SdbSigner {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (!store.containsAlias(ALIAS)) generate()
        val key = store.getKey(ALIAS, null) as PrivateKey
        return JcaSdbSigner(key, store.getCertificate(ALIAS).publicKey as RSAPublicKey)
    }

    private fun generate() {
        // The watch sends a ready-made hash as the token, so the key must sign without hashing and with PKCS#1 padding.
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setKeySize(KEY_SIZE)
            .setDigests(KeyProperties.DIGEST_NONE)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "sdb-host"
        const val KEY_SIZE = 2048
    }
}
