package dev.tizenarchive.companion

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.android.apksig.KeyConfig
import dev.tizenarchive.companion.apk.CompanionApk
import dev.tizenarchive.companion.apk.CompanionManifest
import dev.tizenarchive.companion.apk.TizenPackageFile
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class CompanionApkTest {
    private val manifest = CompanionManifest("org.example.tizen.c0123456789ab")
    private val payload = ByteArray(300_000) { (it * 31 + 7).toByte() }
    private val pkg = TizenPackageFile("wgt", payload.size.toLong()) { payload.inputStream() }

    private fun unsigned(): ByteArray = ByteArrayOutputStream().also { CompanionApk.writeUnsigned(it, manifest, pkg) }.toByteArray()

    @Test
    fun `zip holds the manifest and the untouched package`(@TempDir dir: File) {
        val file = File(dir, "unsigned.apk").apply { writeBytes(unsigned()) }
        ZipFile(file).use { zip ->
            assertEquals(listOf("AndroidManifest.xml", "assets/watcher.wgt"), zip.entries().toList().map { it.name })
            val asset = zip.getEntry("assets/watcher.wgt")
            assertEquals(ZipEntry.STORED, asset.method)
            assertArrayEquals(payload, zip.getInputStream(asset).readBytes())
            assertArrayEquals(manifest.toBinaryXml(), zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes())
        }
    }

    @Test
    fun `tpk packages use the tpk asset name`() {
        val tpk = TizenPackageFile("tpk", payload.size.toLong()) { payload.inputStream() }
        val bytes = ByteArrayOutputStream().also { CompanionApk.writeUnsigned(it, manifest, tpk) }.toByteArray()
        val names = java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
            generateSequence { zin.nextEntry }.map { it.name }.toList()
        }
        assertEquals(listOf("AndroidManifest.xml", "assets/watcher.tpk"), names)
    }

    @Test
    fun `output is byte-for-byte reproducible`() {
        assertArrayEquals(unsigned(), unsigned())
    }

    @Test
    fun `apksig signs it with v2 and v3 and the verifier accepts it`(@TempDir dir: File) {
        val input = File(dir, "unsigned.apk").apply { writeBytes(unsigned()) }
        val output = File(dir, "signed.apk")
        val signer = TestSigner.create("Test Signer")
        val config = ApkSigner.SignerConfig.Builder("test", KeyConfig.Jca(signer.keyPair.private), listOf(signer.certificate)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input).setOutputApk(output)
            .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
            .build().sign()

        val result = ApkVerifier.Builder(output).build().verify()
        assertTrue(result.isVerified, result.errors.joinToString())
        // minSdk 26 >= 24, so apksig leaves out the legacy JAR (v1) signature.
        assertEquals(false, result.isVerifiedUsingV1Scheme)
        assertTrue(result.isVerifiedUsingV2Scheme)
        assertTrue(result.isVerifiedUsingV3Scheme)
        assertEquals(1, result.signerCertificates.size)
    }

    @Test
    fun `size mismatch is rejected`() {
        val lying = TizenPackageFile("wgt", payload.size + 1L) { payload.inputStream() }
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            CompanionApk.writeUnsigned(ByteArrayOutputStream(), manifest, lying)
        }
    }
}
