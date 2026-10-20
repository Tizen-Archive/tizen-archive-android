package dev.tizenarchive.companion

import dev.tizenarchive.companion.apk.CompanionApk
import dev.tizenarchive.companion.apk.CompanionManifest
import dev.tizenarchive.companion.apk.TizenPackageFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/** Cross-checks the generated manifest against Google's aapt2 when AAPT2 points at the binary. */
class Aapt2OracleTest {
    private fun aapt2(): File? = System.getenv("AAPT2")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.canExecute() }

    @Test
    fun `aapt2 dumps the expected manifest`(@TempDir dir: File) {
        val aapt2 = aapt2()
        assumeTrue(aapt2 != null, "AAPT2 is not set")
        val apk = File(dir, "companion.apk")
        val payload = ByteArray(1024) { it.toByte() }
        apk.outputStream().use {
            CompanionApk.writeUnsigned(
                it,
                CompanionManifest("org.example.tizen.c0123456789ab", versionCode = 3),
                TizenPackageFile("wgt", payload.size.toLong()) { payload.inputStream() },
            )
        }
        val process = ProcessBuilder(aapt2!!.path, "dump", "xmltree", "--file", "AndroidManifest.xml", apk.path)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), output)
        for (expected in listOf(
            "E: manifest",
            "package=\"org.example.tizen.c0123456789ab\"",
            "versionCode",
            "E: uses-sdk",
            "E: uses-permission",
            "com.samsung.wmanager.APP",
            "E: application",
            "hasCode",
        )) {
            assertTrue(output.contains(expected), "missing '$expected' in:\n$output")
        }
    }
}
