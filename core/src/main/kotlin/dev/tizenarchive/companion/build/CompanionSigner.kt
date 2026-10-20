package dev.tizenarchive.companion.build

import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

public class SigningKey(public val privateKey: PrivateKey, public val certificates: List<X509Certificate>) {
    init {
        require(certificates.isNotEmpty()) { "A certificate chain is required" }
    }
}

public object CompanionSigner {
    /** Signs with the v2 and v3 schemes; minSdk of the companion is 26, so the legacy v1 scheme is not needed. */
    public fun sign(unsigned: File, signed: File, key: SigningKey) {
        val config = ApkSigner.SignerConfig.Builder("companion", KeyConfig.Jca(key.privateKey), key.certificates).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }
}
