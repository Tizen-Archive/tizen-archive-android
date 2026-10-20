package dev.tizenarchive.companion

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.apksig.ApkVerifier
import dev.tizenarchive.companion.build.CompanionBuilder
import dev.tizenarchive.companion.signing.KeystoreSigningKey
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** S7: how long does building a companion take for big packages, and does it stay within the heap? */
@RunWith(AndroidJUnit4::class)
class ScaleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun bigWgt(dir: File, megabytes: Int): File = File(dir, "big$megabytes.wgt").also { file ->
        val random = Random(megabytes.toLong())
        ZipOutputStream(file.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("config.xml"))
            z.write(
                """<widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" version="1.0.0">
                     <tizen:application id="BigPkg$megabytes.App" package="BigPkg$megabytes"/><name>Big</name></widget>""".toByteArray(),
            )
            z.closeEntry()
            // Random bytes do not compress, so the ZIP is about as large as requested.
            z.putNextEntry(ZipEntry("res/blob.bin"))
            val chunk = ByteArray(1 shl 20)
            repeat(megabytes) {
                random.nextBytes(chunk)
                z.write(chunk)
            }
            z.closeEntry()
        }
    }

    @Test
    fun buildTimeAndMemoryForLargePackages() {
        val runtime = Runtime.getRuntime()
        Log.i("S7", "maxHeap=${runtime.maxMemory() shr 20}MB")
        val key = KeystoreSigningKey().get()
        for (mb in listOf(1, 10, 50, 100, 300)) {
            val dir = File(context.cacheDir, "s7-$mb").apply { deleteRecursively(); mkdirs() }
            val source = bigWgt(dir, mb)
            System.gc()
            val before = runtime.totalMemory() - runtime.freeMemory()
            val start = System.nanoTime()
            val built = CompanionBuilder(key).build(source, File(dir, "out"))
            val buildMs = (System.nanoTime() - start) / 1_000_000
            val peakish = (runtime.totalMemory() - runtime.freeMemory() - before) shr 20
            val verifyStart = System.nanoTime()
            val verified = ApkVerifier.Builder(built.apk).build().verify().isVerified
            val verifyMs = (System.nanoTime() - verifyStart) / 1_000_000
            Log.i("S7", "size=${mb}MB source=${source.length() shr 20}MB apk=${built.apk.length() shr 20}MB build=${buildMs}ms verify=${verifyMs}ms heapDelta~${peakish}MB verified=$verified")
            assertTrue(verified)
            dir.deleteRecursively()
        }
    }
}
