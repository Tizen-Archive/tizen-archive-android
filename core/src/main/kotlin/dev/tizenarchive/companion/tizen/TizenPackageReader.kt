package dev.tizenarchive.companion.tizen

import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** Reads package id, version and name from a `.wgt`/`.tpk`; the type comes from the content, not the file name. */
public object TizenPackageReader {
    private const val NATIVE_MANIFEST = "tizen-manifest.xml"
    private const val WEB_MANIFEST = "config.xml"
    private const val MAX_ENTRIES = 100_000
    private const val MAX_MANIFEST_BYTES = 1L shl 20
    private const val MAX_ICON_BYTES = 1L shl 20

    public fun read(file: File): TizenPackageInfo {
        try {
            ZipFile(file).use { zip ->
                if (zip.size() > MAX_ENTRIES) throw InvalidTizenPackageException("Too many entries in the archive")
                zip.getEntry(NATIVE_MANIFEST)?.let { return parseNative(zip.readManifest(it)) }
                zip.getEntry(WEB_MANIFEST)?.let { return parseWeb(zip.readManifest(it)) }
                throw InvalidTizenPackageException("Neither $NATIVE_MANIFEST nor $WEB_MANIFEST found")
            }
        } catch (e: ZipException) {
            throw InvalidTizenPackageException("Not a valid ZIP archive", e)
        } catch (e: IOException) {
            throw InvalidTizenPackageException("Cannot read the archive: ${e.message}", e)
        }
    }

    /** The icon image bytes (at most 1 MiB), or null when the package has none or it is missing. */
    public fun readIcon(file: File, info: TizenPackageInfo): ByteArray? {
        val path = info.iconPath ?: return null
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(path) ?: return null
                if (entry.size !in 1..MAX_ICON_BYTES) return null
                zip.getInputStream(entry).use { it.readNBytes(MAX_ICON_BYTES.toInt()) }
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun ZipFile.readManifest(entry: java.util.zip.ZipEntry): ByteArray {
        if (entry.size !in 0..MAX_MANIFEST_BYTES) throw InvalidTizenPackageException("Manifest has an unexpected size")
        val bytes = getInputStream(entry).use { it.readNBytes(MAX_MANIFEST_BYTES.toInt() + 1) }
        if (bytes.size > MAX_MANIFEST_BYTES) throw InvalidTizenPackageException("Manifest is too large")
        return bytes
    }

    private fun parseNative(xml: ByteArray): TizenPackageInfo {
        val handler = object : Collector() {
            var packageId: String? = null
            var version: String? = null
            var label: String? = null
            var icon: String? = null
            private var inApplicationLabel = false
            private var inIcon = false
            private val labelText = StringBuilder()
            private val iconText = StringBuilder()

            override fun startElement(uri: String, local: String, qName: String, a: Attributes) {
                depth++
                if (depth == 1 && local == "manifest") {
                    packageId = a.getValue("package")
                    version = a.getValue("version")
                } else if (depth == 3 && local == "label" && label == null && a.getValue("xml:lang") == null) {
                    inApplicationLabel = true
                    labelText.setLength(0)
                } else if (depth == 3 && local == "icon" && icon == null) {
                    inIcon = true
                    iconText.setLength(0)
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inApplicationLabel) labelText.append(ch, start, length)
                if (inIcon) iconText.append(ch, start, length)
            }

            override fun endElement(uri: String, local: String, qName: String) {
                if (inApplicationLabel && local == "label") {
                    label = labelText.toString().trim().ifEmpty { null }
                    inApplicationLabel = false
                }
                if (inIcon && local == "icon") {
                    icon = iconText.toString().trim().ifEmpty { null }
                    inIcon = false
                }
                depth--
            }
        }
        parse(xml, handler, namespaceAware = true)
        val id = handler.packageId?.trim().orEmpty()
        if (id.isEmpty()) throw InvalidTizenPackageException("tizen-manifest.xml has no package id")
        return TizenPackageInfo(TizenPackageKind.TPK, id, handler.version, handler.label, handler.icon?.let { "shared/res/$it" })
    }

    private fun parseWeb(xml: ByteArray): TizenPackageInfo {
        val handler = object : Collector() {
            var packageId: String? = null
            var version: String? = null
            var name: String? = null
            var icon: String? = null
            private var inName = false
            private val nameText = StringBuilder()

            override fun startElement(uri: String, local: String, qName: String, a: Attributes) {
                depth++
                when {
                    depth == 1 && local == "widget" -> version = a.getValue("version")
                    depth == 2 && local == "application" && uri == TIZEN_WIDGETS_NS -> packageId = a.getValue("package")
                    depth == 2 && local == "icon" && icon == null -> icon = a.getValue("src")?.trim()?.ifEmpty { null }
                    depth == 2 && local == "name" && name == null && a.getValue("xml:lang") == null -> {
                        inName = true
                        nameText.setLength(0)
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inName) nameText.append(ch, start, length)
            }

            override fun endElement(uri: String, local: String, qName: String) {
                if (inName && local == "name") {
                    name = nameText.toString().trim().ifEmpty { null }
                    inName = false
                }
                depth--
            }
        }
        parse(xml, handler, namespaceAware = true)
        val id = handler.packageId?.trim().orEmpty()
        if (id.isEmpty()) throw InvalidTizenPackageException("config.xml has no tizen:application package id")
        return TizenPackageInfo(TizenPackageKind.WGT, id, handler.version, handler.name, handler.icon)
    }

    private fun parse(xml: ByteArray, handler: DefaultHandler, namespaceAware: Boolean) {
        // Some XML parsers (notably on Android) reject hardening features, so DTDs are refused up front.
        val head = String(xml, Charsets.ISO_8859_1)
        if (head.contains("<!DOCTYPE", ignoreCase = true) || head.contains("<!ENTITY", ignoreCase = true)) {
            throw InvalidTizenPackageException("Manifest contains a DTD")
        }
        try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = namespaceAware }
            factory.newSAXParser().parse(InputSource(xml.inputStream()), handler)
        } catch (e: SAXException) {
            throw InvalidTizenPackageException("Manifest is not well-formed XML", e)
        } catch (e: IOException) {
            throw InvalidTizenPackageException("Cannot parse the manifest", e)
        }
    }

    private const val TIZEN_WIDGETS_NS = "http://tizen.org/ns/widgets"

    private abstract class Collector : DefaultHandler() {
        var depth = 0
    }
}
