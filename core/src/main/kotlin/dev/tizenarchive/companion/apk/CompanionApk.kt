package dev.tizenarchive.companion.apk

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A Tizen package that is embedded into the companion without changes. [open] must be repeatable. */
public class TizenPackageFile(
    public val extension: String,
    public val size: Long,
    public val open: () -> InputStream,
) {
    init {
        require(extension == "wgt" || extension == "tpk") { "Unsupported package extension: $extension" }
    }
}

/** Writes the unsigned companion APK: the binary manifest plus the Tizen package under assets/. */
public object CompanionApk {
    private val FIXED_TIME: FileTime = FileTime.from(Instant.parse("2026-01-01T00:00:00Z"))

    public fun assetName(extension: String): String = "assets/watcher.$extension"

    /**
     * Copies the Tizen package out of an installed companion (its own APK file), so it can be sent again without the
     * original file. Returns the extension of the package.
     */
    public fun extractPackage(companionApk: File, target: File): String {
        ZipFile(companionApk).use { zip ->
            for (extension in listOf("wgt", "tpk")) {
                val entry = zip.getEntry(assetName(extension)) ?: continue
                zip.getInputStream(entry).use { input -> target.outputStream().buffered().use { input.copyTo(it, BUFFER) } }
                return extension
            }
        }
        throw IOException("The companion does not carry a Tizen package")
    }

    public fun writeUnsigned(out: OutputStream, manifest: CompanionManifest, pkg: TizenPackageFile) {
        ZipOutputStream(out).use { zip ->
            val manifestBytes = manifest.toBinaryXml()
            zip.putNextEntry(entry("AndroidManifest.xml", ZipEntry.DEFLATED))
            zip.write(manifestBytes)
            zip.closeEntry()

            // The package is already compressed, so store it; STORED needs size and CRC up front.
            val crc = CRC32()
            var counted = 0L
            pkg.open().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    crc.update(buffer, 0, read)
                    counted += read
                }
            }
            check(counted == pkg.size) { "Package size changed while reading: expected ${pkg.size}, got $counted" }

            val stored = entry(assetName(pkg.extension), ZipEntry.STORED)
            stored.size = pkg.size
            stored.compressedSize = pkg.size
            stored.crc = crc.value
            zip.putNextEntry(stored)
            pkg.open().use { it.copyTo(zip, BUFFER) }
            zip.closeEntry()
        }
    }

    private fun entry(name: String, method: Int): ZipEntry = ZipEntry(name).apply {
        this.method = method
        setLastModifiedTime(FIXED_TIME)
    }

    private const val BUFFER = 64 * 1024
}
