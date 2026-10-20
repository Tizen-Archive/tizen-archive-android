package dev.tizenarchive.companion

import dev.tizenarchive.companion.build.CompanionBuilder
import dev.tizenarchive.companion.build.SigningKey
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Not a real test: writes signed companions for manual installation on a phone.
 * Set S4_OUT_DIR and S4_PACKAGE (a .wgt/.tpk file or a directory of them) to enable.
 * The signing key is generated per run, so reinstalling over an earlier sample needs `adb uninstall` first.
 */
class DeviceSamplesTest {
    @Test
    fun `write signed sample companions`() {
        val outDir = System.getenv("S4_OUT_DIR")?.takeIf { it.isNotBlank() }
        val source = System.getenv("S4_PACKAGE")?.takeIf { it.isNotBlank() }
        assumeTrue(outDir != null && source != null, "S4_OUT_DIR / S4_PACKAGE not set")
        val input = File(source!!)
        val files = if (input.isDirectory) input.listFiles()!!.filter { it.extension in setOf("wgt", "tpk") }.sortedBy { it.name } else listOf(input)
        val signer = TestSigner.create("Sample")
        val builder = CompanionBuilder(SigningKey(signer.keyPair.private, listOf(signer.certificate)))
        for (file in files) {
            val built = builder.build(file, File(outDir!!))
            println("${file.name} -> ${built.apk.name} (${built.info.packageId})")
        }
    }
}
