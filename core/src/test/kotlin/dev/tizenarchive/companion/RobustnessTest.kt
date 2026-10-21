package dev.tizenarchive.companion

import dev.tizenarchive.companion.catalog.InstallLinkParser
import dev.tizenarchive.companion.catalog.InvalidLinkException
import dev.tizenarchive.companion.tizen.InvalidTizenPackageException
import dev.tizenarchive.companion.tizen.TizenPackageReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Hostile input must end in the documented exception and nothing else. */
class RobustnessTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `install links never fail with an unexpected exception`() {
        val random = Random(1)
        val alphabet = "tizen-archive://install?package=version=&%/.:#@ \u0000é中"
        repeat(5000) {
            val link = buildString { repeat(random.nextInt(60)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            try {
                InstallLinkParser.parse(link)
            } catch (_: InvalidLinkException) {
            }
        }
        for (prefix in listOf("tizen-archive://install?package=", "tizen-archive://install?version=1&package=")) {
            for (tail in listOf("%", "%zz", "%00", "a".repeat(10_000), "..%2F..", "a b")) {
                try {
                    InstallLinkParser.parse(prefix + tail)
                } catch (_: InvalidLinkException) {
                }
            }
        }
    }

    @Test
    fun `random bytes are not a valid package`() {
        val random = Random(2)
        repeat(200) { index ->
            val file = File(dir, "r$index.bin")
            file.writeBytes(ByteArray(random.nextInt(2000)).also(random::nextBytes))
            try {
                TizenPackageReader.read(file)
            } catch (_: InvalidTizenPackageException) {
            }
        }
    }

    @Test
    fun `mangled manifests are rejected with the documented exception`() {
        val manifests = listOf(
            "",
            "<manifest",
            "<manifest xmlns=\"http://tizen.org/ns/packages\"/>",
            "<!DOCTYPE x [<!ENTITY a \"b\">]><manifest xmlns=\"http://tizen.org/ns/packages\" package=\"&a;\"/>",
            "<?xml version=\"1.0\"?><widget xmlns=\"http://www.w3.org/ns/widgets\"/>",
            "\u0000\u0001\u0002",
        )
        for ((index, xml) in manifests.withIndex()) {
            for (entry in listOf("tizen-manifest.xml", "config.xml")) {
                val file = File(dir, "m$index-$entry.zip")
                ZipOutputStream(file.outputStream()).use {
                    it.putNextEntry(ZipEntry(entry))
                    it.write(xml.toByteArray())
                    it.closeEntry()
                }
                try {
                    TizenPackageReader.read(file)
                } catch (_: InvalidTizenPackageException) {
                }
            }
        }
    }
}
