package dev.tizenarchive.companion

import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.apk.AndroidBinXmlParser
import dev.tizenarchive.companion.apk.CompanionManifest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class CompanionManifestTest {
    private val manifest = CompanionManifest("org.example.tizen.c0123456789ab", versionCode = 7, minSdk = 26, targetSdk = 36)

    @Test
    fun `apksig reads package, versions and sdk levels`() {
        val xml = ByteBuffer.wrap(manifest.toBinaryXml())
        assertEquals("org.example.tizen.c0123456789ab", ApkUtils.getPackageNameFromBinaryAndroidManifest(xml.duplicate()))
        assertEquals(26, ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(xml.duplicate()))
        assertEquals(36, ApkUtils.getTargetSdkVersionFromBinaryAndroidManifest(xml.duplicate()))
        assertEquals(7L, ApkUtils.getLongVersionCodeFromBinaryAndroidManifest(xml.duplicate()))
    }

    @Test
    fun `element tree matches the companion contract`() {
        val parser = AndroidBinXmlParser(ByteBuffer.wrap(manifest.toBinaryXml()))
        val seen = mutableListOf<String>()
        var permission: String? = null
        var hasCode: Boolean? = null
        var event = parser.eventType
        while (event != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            if (event == AndroidBinXmlParser.EVENT_START_ELEMENT) {
                seen += parser.name
                for (i in 0 until parser.attributeCount) {
                    when {
                        parser.name == "uses-permission" && parser.getAttributeName(i) == "name" ->
                            permission = parser.getAttributeStringValue(i)
                        parser.name == "application" && parser.getAttributeName(i) == "hasCode" ->
                            hasCode = parser.getAttributeBooleanValue(i)
                    }
                }
            }
            event = parser.next()
        }
        assertEquals(listOf("manifest", "uses-sdk", "uses-permission", "application"), seen)
        assertEquals("com.samsung.wmanager.APP", permission)
        assertEquals(false, hasCode)
    }

    @Test
    fun `output is deterministic`() {
        assertArrayEquals(manifest.toBinaryXml(), manifest.copy().toBinaryXml())
    }

    @Test
    fun `chunk sizes are consistent`() {
        val bytes = ByteBuffer.wrap(manifest.toBinaryXml()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x0003, bytes.getShort(0).toInt())
        assertEquals(bytes.capacity(), bytes.getInt(4))
        assertEquals(0, bytes.capacity() % 4)
        var offset = 8
        while (offset < bytes.capacity()) {
            val size = bytes.getInt(offset + 4)
            assertTrue(size >= 8 && size % 4 == 0, "chunk at $offset has size $size")
            offset += size
        }
        assertEquals(bytes.capacity(), offset)
    }

    @Test
    fun `rejects invalid package names`() {
        for (name in listOf("single", "1abc.def", "a..b", "a.b-c", "")) {
            assertThrows(IllegalArgumentException::class.java) { CompanionManifest(name) }
        }
    }
}
