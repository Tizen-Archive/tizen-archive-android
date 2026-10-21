package dev.tizenarchive.companion.signature

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SignatureInspectorTest {
    @TempDir
    lateinit var dir: File

    private val now = Instant.parse("2026-10-21T00:00:00Z")

    private class Pki(val cert: X509Certificate, val keys: KeyPair)

    private fun keys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun issue(
        subject: String,
        issuer: Pki?,
        notAfter: Instant = now.plusSeconds(86_400L * 300),
        deviceIds: String? = null,
    ): Pki {
        val pair = keys()
        val signer = issuer?.keys ?: pair
        val issuerName = issuer?.cert?.subjectX500Principal?.name ?: "CN=$subject"
        val builder = JcaX509v3CertificateBuilder(
            X500Name(issuerName), BigInteger.valueOf(System.nanoTime()), Date.from(now.minusSeconds(86_400L * 60)), Date.from(notAfter),
            X500Name("CN=$subject"), pair.public,
        )
        if (deviceIds != null) {
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.uniformResourceIdentifier, "URN:tizen:deviceid=$deviceIds")))
        }
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(signer.private)))
        return Pki(cert, pair)
    }

    private val root = issue("Root", null)
    private val goodCa = issue("GEAR2 PROD Public CA Class", root)
    private val devCa = issue("Samsung Tizen DEVELOPER Public CA Class", root)
    private val vdCa = issue("VD DEVELOPER Public CA Class", root)
    private val cas = listOf(
        SignatureInspector.Ca("GEAR2 PROD Public CA Class", SignatureInspector.Policy.STORE, SignatureInspector.sha256Of(goodCa.cert)),
        SignatureInspector.Ca("Samsung Tizen DEVELOPER Public CA Class", SignatureInspector.Policy.DEVICE_BOUND, SignatureInspector.sha256Of(devCa.cert)),
        SignatureInspector.Ca("VD DEVELOPER Public CA Class", SignatureInspector.Policy.UNTRUSTED, SignatureInspector.sha256Of(vdCa.cert)),
        SignatureInspector.Ca("Tizen Public Distributor CA", SignatureInspector.Policy.UNTRUSTED),
    )

    private fun assess(vararg certificates: X509Certificate) = SignatureInspector.assess(certificates.toList(), now, cas)

    @Test
    fun `store signature from the genuine CA is fine`() {
        val leaf = issue("GEAR2 PROD Public Signer Class", goodCa)
        assertEquals(SignatureVerdict.STORE_SIGNED, assess(leaf.cert, goodCa.cert)?.verdict)
        assertEquals(false, assess(leaf.cert, goodCa.cert)?.worthWarning)
    }

    @Test
    fun `the order of certificates in KeyInfo does not matter`() {
        val leaf = issue("Signer", goodCa)
        assertEquals(SignatureVerdict.STORE_SIGNED, assess(goodCa.cert, leaf.cert)?.verdict)
    }

    @Test
    fun `a lone leaf with a Samsung issuer name is forged`() {
        val leaf = issue("Signer", goodCa)
        assertEquals(SignatureVerdict.FORGED, assess(leaf.cert)?.verdict)
    }

    @Test
    fun `an imitation of the CA is forged`() {
        val fakeCa = issue("GEAR2 PROD Public CA Class", root)
        val leaf = issue("Signer", fakeCa)
        assertEquals(SignatureVerdict.FORGED, assess(leaf.cert, fakeCa.cert)?.verdict)
    }

    @Test
    fun `a leaf not signed by the intermediate is forged`() {
        val leaf = issue("Signer", goodCa)
        val other = issue("GEAR2 PROD Public CA Class", root)
        // Same name as the genuine CA, but a different key signed the leaf
        val impostor = issue("Signer", other)
        assertEquals(SignatureVerdict.FORGED, assess(impostor.cert, goodCa.cert)?.verdict)
        assertTrue(leaf.cert.issuerX500Principal == goodCa.cert.subjectX500Principal)
    }

    @Test
    fun `developer certificate counts devices and reports the end date`() {
        val leaf = issue("TizenSDK", devCa, deviceIds = "AAA,BBB,CCC")
        val result = assess(leaf.cert, devCa.cert)!!
        assertEquals(SignatureVerdict.DEVICE_BOUND, result.verdict)
        assertEquals(3, result.deviceCount)
        assertTrue(result.worthWarning)
    }

    @Test
    fun `an expired developer certificate is reported with its date`() {
        val leaf = issue("TizenSDK", devCa, notAfter = now.minusSeconds(86_400L * 10), deviceIds = "AAA")
        val result = assess(leaf.cert, devCa.cert)!!
        assertEquals(SignatureVerdict.EXPIRED, result.verdict)
        assertEquals(LocalDate.parse("2026-10-11"), result.notAfter)
    }

    @Test
    fun `VD developer certificates are untrusted by the watch`() {
        val leaf = issue("Dev", vdCa, deviceIds = "AAA")
        assertEquals(SignatureVerdict.UNTRUSTED_BY_WATCH, assess(leaf.cert, vdCa.cert)?.verdict)
    }

    @Test
    fun `the Tizen SDK default certificate is untrusted by the watch`() {
        val sdk = issue("Tizen Public Distributor CA", root)
        val leaf = issue("Developer", sdk)
        assertEquals(SignatureVerdict.UNTRUSTED_BY_WATCH, assess(leaf.cert, sdk.cert)?.verdict)
    }

    @Test
    fun `an unknown issuer gives nothing to warn about`() {
        val ca = issue("Some Other CA", root)
        val leaf = issue("Signer", ca)
        val result = assess(leaf.cert, ca.cert)!!
        assertEquals(SignatureVerdict.NOT_VERIFIED, result.verdict)
        assertEquals(false, result.worthWarning)
    }

    private fun packageWith(vararg entries: Pair<String, String>): File {
        val file = File(dir, "p-${System.nanoTime()}.wgt")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun signatureXml(vararg certs: X509Certificate): String =
        "<Signature xmlns=\"http://www.w3.org/2000/09/xmldsig#\"><KeyInfo><X509Data>" +
            certs.joinToString("") { "<X509Certificate>${Base64.getMimeEncoder().encodeToString(it.encoded)}</X509Certificate>" } +
            "</X509Data></KeyInfo></Signature>"

    @Test
    fun `a package without a distributor signature is unsigned`() {
        val file = packageWith("config.xml" to "<widget/>", "author-signature.xml" to "<Signature/>")
        assertEquals(SignatureVerdict.UNSIGNED, SignatureInspector.inspect(file, now)?.verdict)
    }

    @Test
    fun `an unreadable signature gives no verdict`() {
        assertNull(SignatureInspector.inspect(packageWith("signature1.xml" to "<not xml"), now))
        assertNull(SignatureInspector.inspect(packageWith("signature1.xml" to "<!DOCTYPE x [<!ENTITY a \"b\">]><x/>"), now))
        assertNull(SignatureInspector.inspect(packageWith("signature1.xml" to "<Signature><X509Certificate>AAAA</X509Certificate></Signature>"), now))
        val notZip = File(dir, "x.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertNull(SignatureInspector.inspect(notZip, now))
    }

    @Test
    fun `a signature file inside a package is read, the lowest number wins`() {
        val leaf = issue("Signer", issue("Whatever CA", root))
        val file = packageWith("signature2.xml" to "<broken", "signature1.xml" to signatureXml(leaf.cert))
        // Real CAs are not known to the synthetic table, so the issuer is simply not recognised
        assertEquals(SignatureVerdict.NOT_VERIFIED, SignatureInspector.inspect(file, now)?.verdict)
    }
}
