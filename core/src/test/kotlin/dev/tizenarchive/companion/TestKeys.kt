package dev.tizenarchive.companion

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date

internal class TestSigner(val keyPair: KeyPair, val certificate: X509Certificate) {
    companion object {
        fun create(commonName: String): TestSigner {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=$commonName")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 60_000), Date(now + 86_400_000L * 365), name, pair.public,
            )
            val holder = builder.build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            return TestSigner(pair, JcaX509CertificateConverter().getCertificate(holder))
        }
    }
}
