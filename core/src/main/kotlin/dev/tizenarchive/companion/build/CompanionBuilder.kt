package dev.tizenarchive.companion.build

import dev.tizenarchive.companion.apk.CompanionApk
import dev.tizenarchive.companion.apk.CompanionManifest
import dev.tizenarchive.companion.apk.TizenPackageFile
import dev.tizenarchive.companion.tizen.TizenPackageInfo
import dev.tizenarchive.companion.tizen.TizenPackageReader
import java.io.File
import java.time.Instant

public class BuiltCompanion(
    public val apk: File,
    public val androidPackageName: String,
    public val info: TizenPackageInfo,
)

/** Turns a local Tizen package file into a signed companion APK. */
public class CompanionBuilder(private val key: SigningKey, private val clock: () -> Instant = Instant::now) {
    public fun build(source: File, outputDir: File): BuiltCompanion {
        val info = TizenPackageReader.read(source)
        val name = CompanionNaming.androidPackageName(info.packageId)
        val manifest = CompanionManifest(
            name,
            versionCode = CompanionNaming.versionCode(clock()),
            versionName = info.version?.takeIf { it.isNotBlank() }?.take(MAX_VERSION_NAME) ?: "1.0",
            label = info.displayName ?: info.packageId,
            tizenPackageId = info.packageId,
            tizenVersion = info.version,
            tizenKind = info.kind.extension,
        )

        outputDir.mkdirs()
        val unsigned = File(outputDir, "$name.unsigned.apk")
        val signed = File(outputDir, "$name.apk")
        try {
            unsigned.outputStream().buffered().use {
                CompanionApk.writeUnsigned(it, manifest, TizenPackageFile(info.kind.extension, source.length()) { source.inputStream() })
            }
            CompanionSigner.sign(unsigned, signed, key)
        } finally {
            unsigned.delete()
        }
        return BuiltCompanion(signed, name, info)
    }

    private companion object {
        const val MAX_VERSION_NAME = 64
    }
}
