package dev.tizenarchive.companion

import dev.tizenarchive.companion.tizen.InvalidTizenPackageException
import dev.tizenarchive.companion.tizen.TizenPackageKind
import dev.tizenarchive.companion.tizen.TizenPackageReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TizenPackageReaderTest {
    @TempDir
    lateinit var dir: File

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = File(dir, "p-${System.nanoTime()}.bin")
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return file
    }

    private val tpkManifest = """<?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns="http://tizen.org/ns/packages" api-version="2.3.1.2" package="com.example.watch" version="1.2.3">
          <watch-application ambient-support="true" appid="com.example.watch" exec="com.example.watch">
            <label xml:lang="ru-ru">Циферблат</label>
            <label> Example Face </label>
            <icon>face.png</icon>
          </watch-application>
        </manifest>"""

    private val wgtConfig = """<?xml version="1.0" encoding="UTF-8"?>
        <widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" id="http://x/Y" version="2.0.1">
          <tizen:application id="AbCdEfGhIj.Calc" package="AbCdEfGhIj" required_version="2.3"/>
          <content src="index.html"/>
          <icon src="img/icon.png" height="117" width="117"/>
          <name xml:lang="ru">Калькулятор</name>
          <name>Calc</name>
        </widget>"""

    @Test
    fun `native package is read from tizen-manifest`() {
        val info = TizenPackageReader.read(zip("tizen-manifest.xml" to tpkManifest, "bin/x" to "elf"))
        assertEquals(TizenPackageKind.TPK, info.kind)
        assertEquals("com.example.watch", info.packageId)
        assertEquals("1.2.3", info.version)
        assertEquals("Example Face", info.displayName)
        assertEquals("shared/res/face.png", info.iconPath)
    }

    @Test
    fun `web package is read from config xml`() {
        val info = TizenPackageReader.read(zip("config.xml" to wgtConfig, "index.html" to "<html/>"))
        assertEquals(TizenPackageKind.WGT, info.kind)
        assertEquals("AbCdEfGhIj", info.packageId)
        assertEquals("2.0.1", info.version)
        assertEquals("Calc", info.displayName)
        assertEquals("img/icon.png", info.iconPath)
    }

    @Test
    fun `the icon is read from the archive`() {
        val file = zip("config.xml" to wgtConfig, "img/icon.png" to "PNGDATA")
        val info = TizenPackageReader.read(file)
        assertEquals("PNGDATA", String(TizenPackageReader.readIcon(file, info)!!))
        assertEquals(null, TizenPackageReader.readIcon(zip("config.xml" to wgtConfig), info))
    }

    @Test
    fun `a wgt that carries a native manifest is treated as native`() {
        val info = TizenPackageReader.read(zip("config.xml" to wgtConfig, "tizen-manifest.xml" to tpkManifest))
        assertEquals(TizenPackageKind.TPK, info.kind)
    }

    @Test
    fun `rejects archives without a manifest`() {
        assertThrows(InvalidTizenPackageException::class.java) { TizenPackageReader.read(zip("readme.txt" to "hi")) }
    }

    @Test
    fun `rejects non-zip data`() {
        val file = File(dir, "junk.wgt").apply { writeBytes(ByteArray(500) { it.toByte() }) }
        assertThrows(InvalidTizenPackageException::class.java) { TizenPackageReader.read(file) }
    }

    @Test
    fun `rejects manifests with a DTD`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE manifest [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <manifest package="a.b" version="1"/>"""
        assertThrows(InvalidTizenPackageException::class.java) { TizenPackageReader.read(zip("tizen-manifest.xml" to xml)) }
    }

    @Test
    fun `rejects a manifest without a package id`() {
        assertThrows(InvalidTizenPackageException::class.java) {
            TizenPackageReader.read(zip("tizen-manifest.xml" to """<manifest version="1"/>"""))
        }
    }

    @Test
    fun `rejects an oversized manifest`() {
        val huge = "<manifest package=\"a.b\">" + " ".repeat(2_000_000) + "</manifest>"
        assertThrows(InvalidTizenPackageException::class.java) { TizenPackageReader.read(zip("tizen-manifest.xml" to huge)) }
    }
}
