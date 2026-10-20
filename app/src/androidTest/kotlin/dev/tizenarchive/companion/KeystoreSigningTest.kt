package dev.tizenarchive.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.apksig.ApkVerifier
import dev.tizenarchive.companion.build.CompanionBuilder
import dev.tizenarchive.companion.signing.KeystoreSigningKey
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** S5: apksig must sign with a key that lives in the Android Keystore. */
@RunWith(AndroidJUnit4::class)
class KeystoreSigningTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun wgt(dir: File): File = File(dir, "app.wgt").also { file ->
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("config.xml"))
            z.write(
                """<widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" version="1.0.0">
                     <tizen:application id="AbCdEfGhIj.App" package="AbCdEfGhIj"/><name>App</name></widget>""".toByteArray(),
            )
            z.closeEntry()
        }
    }

    @Test
    fun keystoreKeySignsAndVerifies() {
        val dir = File(context.cacheDir, "s5").apply { deleteRecursively(); mkdirs() }
        val keys = KeystoreSigningKey()
        val key = keys.get()
        assertEquals("RSA", key.privateKey.algorithm)

        val built = CompanionBuilder(key).build(wgt(dir), File(dir, "out"))
        val result = ApkVerifier.Builder(built.apk).build().verify()
        assertTrue(result.errors.joinToString(), result.isVerified)
        assertTrue(result.isVerifiedUsingV2Scheme)
        assertTrue(result.isVerifiedUsingV3Scheme)

        // The signer certificate in the APK is the Keystore certificate, and the key is stable across calls.
        val apkCertificate = result.signerCertificates.single()
        assertArrayEquals(key.certificates.first().encoded, apkCertificate.encoded)
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(key.certificates.first().encoded),
            MessageDigest.getInstance("SHA-256").digest(keys.get().certificates.first().encoded),
        )
        dir.deleteRecursively()
    }
}
