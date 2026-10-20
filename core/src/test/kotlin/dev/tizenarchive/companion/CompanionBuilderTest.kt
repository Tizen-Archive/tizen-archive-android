package dev.tizenarchive.companion

import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import dev.tizenarchive.companion.apk.CompanionMetadata
import dev.tizenarchive.companion.build.CompanionBuilder
import dev.tizenarchive.companion.build.CompanionNaming
import dev.tizenarchive.companion.build.SigningKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class CompanionBuilderTest {
    @TempDir
    lateinit var dir: File

    private val signer = TestSigner.create("Builder Test")
    private val key = SigningKey(signer.keyPair.private, listOf(signer.certificate))

    private fun wgt(): File = File(dir, "app.wgt").also { file ->
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("config.xml"))
            z.write(
                """<widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" version="1.0.0">
                     <tizen:application id="AbCdEfGhIj.App" package="AbCdEfGhIj"/><name>App</name></widget>""".toByteArray(),
            )
            z.closeEntry()
            z.putNextEntry(ZipEntry("index.html"))
            z.write("<html/>".toByteArray())
            z.closeEntry()
        }
    }

    @Test
    fun `builds a verified companion for a wgt`() {
        val now = Instant.parse("2026-10-20T12:00:00Z")
        val built = CompanionBuilder(key) { now }.build(wgt(), File(dir, "out"))

        assertEquals(CompanionNaming.androidPackageName("AbCdEfGhIj"), built.androidPackageName)
        assertEquals("AbCdEfGhIj", built.info.packageId)
        val result = ApkVerifier.Builder(built.apk).build().verify()
        assertTrue(result.isVerified, result.errors.joinToString())
        assertTrue(result.isVerifiedUsingV2Scheme && result.isVerifiedUsingV3Scheme)

        ZipFile(built.apk).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
            assertEquals(built.androidPackageName, ApkUtils.getPackageNameFromBinaryAndroidManifest(ByteBuffer.wrap(manifest)))
            assertTrue(zip.getEntry("assets/watcher.wgt") != null)
        }
        assertTrue(File(dir, "out").listFiles()!!.none { it.name.endsWith(".unsigned.apk") })

        // the companion tells which Tizen package it carries
        val xml = ZipFile(built.apk).use { it.getInputStream(it.getEntry("AndroidManifest.xml")).readBytes() }
        val strings = String(xml, Charsets.UTF_16LE)
        assertTrue(strings.contains("AbCdEfGhIj") && strings.contains(CompanionMetadata.MARKER_ACTION))
    }

    @Test
    fun `the same package gets the same name and a higher version code later`() {
        val earlier = CompanionBuilder(key) { Instant.parse("2026-10-20T12:00:00Z") }.build(wgt(), File(dir, "a"))
        val later = CompanionBuilder(key) { Instant.parse("2026-10-20T12:00:05Z") }.build(wgt(), File(dir, "b"))
        assertEquals(earlier.androidPackageName, later.androidPackageName)
        val code = { f: File -> ZipFile(f).use { z -> ApkUtils.getLongVersionCodeFromBinaryAndroidManifest(ByteBuffer.wrap(z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes())) } }
        assertEquals(5L, code(later.apk) - code(earlier.apk))
    }

    @Test
    fun `different tizen packages get different companion names`() {
        assertNotEquals(CompanionNaming.androidPackageName("a.b"), CompanionNaming.androidPackageName("a.c"))
    }

    @Test
    fun `clock before the epoch is refused`() {
        assertThrows(IllegalArgumentException::class.java) { CompanionNaming.versionCode(Instant.parse("2025-12-31T00:00:00Z")) }
    }
}
