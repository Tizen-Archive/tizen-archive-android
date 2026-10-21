package dev.tizenarchive.companion.files

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.tizenarchive.companion.signature.SignatureAssessment
import dev.tizenarchive.companion.signature.SignatureInspector
import dev.tizenarchive.companion.tizen.TizenPackageReader
import java.io.File

/** What the list shows about a package instead of its file name. */
internal data class PackagePreview(
    val name: String?,
    val packageId: String,
    val version: String?,
    val kind: String,
    val icon: ImageBitmap?,
    /** Whether the watch is expected to accept the package's signature; null when it could not be read. */
    val signature: SignatureAssessment? = null,
)

internal interface PackagePreviews {
    fun load(uri: Uri, size: Long): PackagePreview?
}

/** Reads the manifest and the icon of a package straight from its file, without installing anything. */
internal class PreviewLoader(private val context: Context) : PackagePreviews {
    override fun load(uri: Uri, size: Long): PackagePreview? {
        // Most providers hand out a seekable descriptor, so the ZIP can be read in place, even when it is large
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                return read(File("/proc/self/fd/${descriptor.fd}"))
            }
        }
        if (size !in 1..SMALL_COPY_LIMIT) return null
        val copy = File.createTempFile("preview", ".bin", context.cacheDir)
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { src -> copy.outputStream().use { src.copyTo(it) } }
            runCatching { read(copy) }.getOrNull()
        } finally {
            copy.delete()
        }
    }

    private fun read(file: File): PackagePreview {
        val info = TizenPackageReader.read(file)
        val icon = TizenPackageReader.readIcon(file, info)?.let(::decode)
        return PackagePreview(info.displayName, info.packageId, info.version, info.kind.extension, icon, SignatureInspector.inspect(file))
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ICON_PX && bounds.outHeight / (sample * 2) >= ICON_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private companion object {
        const val ICON_PX = 96
        const val SMALL_COPY_LIMIT = 8L * 1024 * 1024
    }
}
