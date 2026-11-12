package dev.tizenarchive.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tizenarchive.companion.tizen.InvalidTizenPackageException
import dev.tizenarchive.companion.tizen.TizenPackageReader
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TizenPackageReaderAndroidTest {
    @Test
    fun readsNativeManifestWithTheDeviceSaxParser() {
        val file = packageFile(
            "tizen-manifest.xml",
            """<manifest xmlns="http://tizen.org/ns/packages" package="com.example.watch" version="1.0">
                <watch-application><label>Example Watch</label></watch-application>
            </manifest>""",
        )
        try {
            val info = TizenPackageReader.read(file)

            assertEquals("com.example.watch", info.packageId)
            assertEquals("Example Watch", info.displayName)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsDoctypeWithTheDeviceSaxParser() {
        val file = packageFile(
            "tizen-manifest.xml",
            """<?xml version="1.0"?>
                <!DOCTYPE manifest [<!ENTITY x SYSTEM "file:///system/build.prop">]>
                <manifest package="com.example.watch" version="1.0">
                  <watch-application><label>&x;</label></watch-application>
                </manifest>""",
        )
        try {
            assertThrows(InvalidTizenPackageException::class.java) {
                TizenPackageReader.read(file)
            }
        } finally {
            file.delete()
        }
    }

    private fun packageFile(entryName: String, xml: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("tizen-package-", ".wgt", context.cacheDir)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file
    }
}
