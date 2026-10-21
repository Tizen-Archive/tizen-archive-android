package dev.tizenarchive.companion.signature

import dev.tizenarchive.companion.readUpTo
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/** What the watch is expected to do with the distributor signature of a package. */
public enum class SignatureVerdict {
    /** Signed by the Galaxy Store CA: installs. */
    STORE_SIGNED,

    /** A known CA that has not been tried on a watch, or one this reader does not know: nothing to warn about. */
    NOT_VERIFIED,

    /** A developer certificate: installs only on the devices listed in it. */
    DEVICE_BOUND,

    /** A developer certificate past its end date. */
    EXPIRED,

    /** No distributor signature at all. */
    UNSIGNED,

    /** The CA is not among the roots of Tizen watches (Samsung VD, Tizen SDK test certificates). */
    UNTRUSTED_BY_WATCH,

    /** The issuer name is a Samsung CA, but the certificate chain is not that CA's. */
    FORGED,
}

public data class SignatureAssessment(
    val verdict: SignatureVerdict,
    /** Developer certificates only: how many devices the certificate lists (the identifiers themselves are dropped). */
    val deviceCount: Int = 0,
    val notAfter: LocalDate? = null,
) {
    /** Whether the user should be told before sending the package. */
    val worthWarning: Boolean
        get() = verdict != SignatureVerdict.STORE_SIGNED && verdict != SignatureVerdict.NOT_VERIFIED
}

/**
 * Predicts, from the distributor signature alone, whether a Tizen watch accepts a package. It checks the certificate
 * chain in `signature1.xml` against known Samsung CAs and does not verify file digests, so a damaged package can still
 * look fine. The rules come from installs on a Galaxy Watch (SM-R800, Tizen 5.5) and are a prediction, not a promise.
 */
public object SignatureInspector {
    /** Returns null when the signature cannot be read at all (no verdict is better than a wrong one). */
    public fun inspect(file: File, now: Instant = Instant.now()): SignatureAssessment? =
        try {
            ZipFile(file).use { zip ->
                val name = zip.entries().asSequence()
                    .map { it.name }
                    .mapNotNull { DISTRIBUTOR.matchEntire(it)?.let { m -> m.groupValues[1].toIntOrNull() to it } }
                    .minByOrNull { it.first ?: Int.MAX_VALUE }
                    ?.second
                    ?: return SignatureAssessment(SignatureVerdict.UNSIGNED)
                val entry = zip.getEntry(name)
                if (entry.size !in 0..MAX_SIGNATURE_BYTES) return null
                val certificates = zip.getInputStream(entry).use { readCertificates(it) }
                assess(certificates, now)
            }
        } catch (e: IOException) {
            null
        } catch (e: java.security.GeneralSecurityException) {
            null
        } catch (e: org.xml.sax.SAXException) {
            null
        } catch (e: RuntimeException) {
            null
        }

    internal fun sha256Of(certificate: X509Certificate): String = sha256(certificate)

    internal fun assess(certificates: List<X509Certificate>, now: Instant, cas: List<Ca> = KNOWN_CAS): SignatureAssessment? {
        val chain = order(certificates)
        val leaf = chain.firstOrNull() ?: return null
        val issuer = commonName(leaf.issuerX500Principal.name) ?: return SignatureAssessment(SignatureVerdict.NOT_VERIFIED)
        val ca = cas.firstOrNull { it.name.equals(issuer.trim().replace(WHITESPACE, " "), ignoreCase = true) }
            ?: return SignatureAssessment(SignatureVerdict.NOT_VERIFIED)
        if (ca.fingerprint != null) {
            val intermediate = chain.getOrNull(1)
            val linked = chain.zipWithNext().all { (child, parent) -> signedBy(child, parent) }
            val genuine = linked && intermediate != null && sha256(intermediate).equals(ca.fingerprint, ignoreCase = true)
            if (!genuine) return SignatureAssessment(SignatureVerdict.FORGED)
        }
        return when (ca.policy) {
            Policy.STORE -> SignatureAssessment(SignatureVerdict.STORE_SIGNED)
            Policy.UNVERIFIED -> SignatureAssessment(SignatureVerdict.NOT_VERIFIED)
            Policy.UNTRUSTED -> SignatureAssessment(SignatureVerdict.UNTRUSTED_BY_WATCH)
            Policy.DEVICE_BOUND -> {
                val end = leaf.notAfter.toInstant()
                val date = end.atZone(ZoneOffset.UTC).toLocalDate()
                if (now.isAfter(end)) {
                    SignatureAssessment(SignatureVerdict.EXPIRED, notAfter = date)
                } else {
                    SignatureAssessment(SignatureVerdict.DEVICE_BOUND, deviceCount(leaf), date)
                }
            }
        }
    }

    private fun readCertificates(input: InputStream): List<X509Certificate> {
        val texts = mutableListOf<String>()
        val handler = object : DefaultHandler() {
            private var inCertificate = false
            private val text = StringBuilder()

            override fun startElement(uri: String?, local: String, qName: String?, a: Attributes?) {
                if (local == "X509Certificate") {
                    inCertificate = true
                    text.setLength(0)
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inCertificate) text.append(ch, start, length)
            }

            override fun endElement(uri: String?, local: String, qName: String?) {
                if (local == "X509Certificate") {
                    inCertificate = false
                    texts += text.toString()
                }
            }
        }
        val bytes = input.readUpTo(MAX_SIGNATURE_BYTES.toInt() + 1)
        // A DTD can only come before the root element, which is within the first bytes
        val head = String(bytes, 0, minOf(bytes.size, DTD_SCAN_BYTES), Charsets.ISO_8859_1)
        if (head.contains("<!DOCTYPE", ignoreCase = true) || head.contains("<!ENTITY", ignoreCase = true)) throw IOException("DTD")
        if (bytes.size > MAX_SIGNATURE_BYTES) throw IOException("Signature too large")
        SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().parse(InputSource(bytes.inputStream()), handler)
        val factory = CertificateFactory.getInstance("X.509")
        return texts.take(MAX_CERTIFICATES).map {
            factory.generateCertificate(Base64.getMimeDecoder().decode(it.trim()).inputStream()) as X509Certificate
        }
    }

    /** The leaf is the certificate that issued no other one; the rest follow by issuer. */
    private fun order(certificates: List<X509Certificate>): List<X509Certificate> {
        if (certificates.size < 2) return certificates
        val leaf = certificates.firstOrNull { c -> certificates.none { it !== c && it.issuerX500Principal == c.subjectX500Principal } }
            ?: return certificates
        val ordered = mutableListOf(leaf)
        val rest = certificates.filter { it !== leaf }.toMutableList()
        while (rest.isNotEmpty()) {
            val next = rest.firstOrNull { it.subjectX500Principal == ordered.last().issuerX500Principal } ?: break
            ordered += next
            rest -= next
        }
        return ordered + rest
    }

    private fun signedBy(child: X509Certificate, parent: X509Certificate): Boolean =
        try {
            child.verify(parent.publicKey)
            true
        } catch (e: Exception) {
            false
        }

    private fun sha256(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02X".format(it) }

    /** Subject alternative names look like `URN:tizen:deviceid=A,B`; only the number of distinct ids is kept. */
    private fun deviceCount(leaf: X509Certificate): Int {
        val ids = mutableSetOf<String>()
        val names = try { leaf.subjectAlternativeNames } catch (e: java.security.cert.CertificateParsingException) { null }
        for (entry in names.orEmpty()) {
            if (entry.size < 2 || entry[0] != URI_NAME_TYPE) continue
            val value = entry[1] as? String ?: continue
            val match = DEVICE_IDS.matchEntire(value.trim()) ?: continue
            match.groupValues[1].split(',').map(String::trim).filter(String::isNotEmpty).forEach(ids::add)
        }
        return ids.size
    }

    /** The CN of an RFC 2253 name such as `CN=GEAR2 PROD Public CA Class,OU=...` (javax.naming does not exist on Android). */
    private fun commonName(rfc2253: String): String? {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (c in rfc2253) {
            when {
                escaped -> { current.append(c); escaped = false }
                c == '\\' -> { current.append(c); escaped = true }
                c == ',' -> { parts += current.toString(); current.setLength(0) }
                else -> current.append(c)
            }
        }
        parts += current.toString()
        return parts.firstOrNull { it.startsWith("CN=", ignoreCase = true) }?.substring(3)?.replace(Regex("\\\\(.)"), "$1")
    }

    internal enum class Policy { STORE, DEVICE_BOUND, UNVERIFIED, UNTRUSTED }

    internal class Ca(val name: String, val policy: Policy, val fingerprint: String? = null)

    private val KNOWN_CAS = listOf(
        Ca("GEAR2 PROD Public CA Class", Policy.STORE, "6F034CB3524D7BFD90AE2AA616067B2228E631ED195E0C332B44BE6C39549634"),
        Ca("GEAR2 PROD Platform CA Class", Policy.STORE, "0D76E611F6769760E61426F0E7BB3DEBAF1CBA20301F73DD801588844C545468"),
        Ca("Samsung Tizen Platform CA Class", Policy.UNVERIFIED, "7709F9487171FB834DA175B0D191D8CE980EC4343732DF0AFCE3D1D43AD628C0"),
        Ca("Samsung Tizen DEVELOPER Public CA Class", Policy.DEVICE_BOUND, "43EFF328726D5C7C58F660A1EBE2987F06932EA04C0EE831EC530710DFDB6CAC"),
        Ca("VD DEVELOPER Public CA Class", Policy.UNTRUSTED, "BF18859EB024CC1D63F008DCDDD643077B0DE53E958F35993FF0088D1A1D31AE"),
        Ca("Tizen Public Distributor CA", Policy.UNTRUSTED),
    )

    private val DISTRIBUTOR = Regex("signature([1-9][0-9]*)\\.xml")
    private val WHITESPACE = Regex("\\s+")
    private val DEVICE_IDS = Regex("URN:tizen:deviceid=(.*)", RegexOption.IGNORE_CASE)
    private const val URI_NAME_TYPE = 6
    /** A signature lists every file of the package, so real ones reach almost 1 MB. */
    private const val MAX_SIGNATURE_BYTES = 8L * 1024 * 1024
    private const val DTD_SCAN_BYTES = 64 * 1024
    private const val MAX_CERTIFICATES = 8
}
