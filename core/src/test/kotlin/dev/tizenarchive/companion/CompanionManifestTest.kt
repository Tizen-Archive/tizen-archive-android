package dev.tizenarchive.companion

import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.apk.AndroidBinXmlParser
import dev.tizenarchive.companion.apk.CompanionManifest
import dev.tizenarchive.companion.apk.CompanionMetadata
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

    private data class Parsed(val elements: List<String>, val attributes: List<Triple<String, String, String>>)

    private fun parse(bytes: ByteArray): Parsed {
        val parser = AndroidBinXmlParser(ByteBuffer.wrap(bytes))
        val elements = mutableListOf<String>()
        val attributes = mutableListOf<Triple<String, String, String>>()
        var event = parser.eventType
        while (event != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            if (event == AndroidBinXmlParser.EVENT_START_ELEMENT) {
                elements += parser.name
                for (i in 0 until parser.attributeCount) {
                    if (parser.getAttributeValueType(i) == AndroidBinXmlParser.VALUE_TYPE_STRING) {
                        attributes += Triple(parser.name, parser.getAttributeName(i), parser.getAttributeStringValue(i))
                    }
                }
            }
            event = parser.next()
        }
        return Parsed(elements, attributes)
    }

    @Test
    fun `tizen metadata, label and a discoverable marker are written when given`() {
        val rich = manifest.copy(label = "Example Face", tizenPackageId = "com.example.face", tizenVersion = "2.0.1", tizenKind = "tpk")
        val parsed = parse(rich.toBinaryXml())

        assertEquals(
            listOf("manifest", "uses-sdk", "uses-permission", "application", "meta-data", "meta-data", "meta-data", "activity", "intent-filter", "action"),
            parsed.elements,
        )
        val application = parsed.attributes.filter { it.first == "application" }
        assertEquals("Example Face", application.single { it.second == "label" }.third)
        val meta = parsed.attributes.filter { it.first == "meta-data" }.chunked(2).associate { (name, value) -> name.third to value.third }
        assertEquals(
            mapOf(
                CompanionMetadata.TIZEN_PACKAGE_ID to "com.example.face",
                CompanionMetadata.TIZEN_VERSION to "2.0.1",
                CompanionMetadata.TIZEN_KIND to "tpk",
            ),
            meta,
        )
        assertTrue(parsed.attributes.contains(Triple("action", "name", CompanionMetadata.MARKER_ACTION)))
        assertTrue(parsed.attributes.contains(Triple("activity", "name", CompanionMetadata.MARKER_COMPONENT)))
        // the carrier permission is still the only thing Gear Plugin needs
        assertTrue(parsed.attributes.contains(Triple("uses-permission", "name", CompanionManifest.WATCH_MANAGER_PERMISSION)))
    }

    @Test
    fun `labels are cleaned and shortened`() {
        assertEquals("A B", CompanionMetadata.sanitizeLabel("  A\u0000 B\n "))
        assertEquals(80, CompanionMetadata.sanitizeLabel("x".repeat(500)).length)
    }
}
