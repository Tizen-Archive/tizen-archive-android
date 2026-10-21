package dev.tizenarchive.companion

import android.net.Uri
import dev.tizenarchive.companion.build.BuiltCompanion
import dev.tizenarchive.companion.build.CompanionFactory
import dev.tizenarchive.companion.catalog.Artifact
import dev.tizenarchive.companion.catalog.CatalogApi
import dev.tizenarchive.companion.catalog.VersionInfo
import dev.tizenarchive.companion.companions.Companions
import dev.tizenarchive.companion.companions.InstalledCompanion
import dev.tizenarchive.companion.files.FolderMemory
import dev.tizenarchive.companion.files.FolderSource
import dev.tizenarchive.companion.files.PackageFile
import dev.tizenarchive.companion.files.PackagePreview
import dev.tizenarchive.companion.files.PackagePreviews
import dev.tizenarchive.companion.install.InstallOutcome
import dev.tizenarchive.companion.install.Installer
import dev.tizenarchive.companion.install.UninstallOutcome
import dev.tizenarchive.companion.notify.QueueNotifications
import dev.tizenarchive.companion.tizen.TizenPackageReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A minimal native Tizen package: just the manifest. */
internal fun tizenPackage(id: String, version: String, padding: Int = 0): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        z.putNextEntry(ZipEntry("tizen-manifest.xml"))
        z.write(
            """<?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns="http://tizen.org/ns/packages" api-version="4.0" package="$id" version="$version">
              <watch-application appid="$id" exec="$id"><label>$id</label></watch-application>
            </manifest>""".toByteArray(),
        )
        z.closeEntry()
        if (padding > 0) {
            z.putNextEntry(ZipEntry("shared/res/pad.bin"))
            z.write(ByteArray(padding))
            z.closeEntry()
        }
    }
    return out.toByteArray()
}

/** Time comes from the test scheduler so that the 15 s waits cost nothing. */
internal class FakePlatform(
    override val cacheDir: File,
    val clock: () -> Long,
) : Platform {
    var canInstall = true
    val installedPackages = mutableSetOf(
        "com.samsung.android.app.watchmanager",
        "com.samsung.android.geargplugin",
    )
    val contents = mutableMapOf<Uri, ByteArray>()
    val names = mutableMapOf<Uri, String>()

    override fun canRequestPackageInstalls() = canInstall

    override fun isInstalled(packageName: String) = packageName in installedPackages

    override fun elapsedRealtime() = clock()

    override fun openInput(uri: Uri): InputStream? =
        if (uri.scheme == "file") File(uri.path!!).inputStream() else contents[uri]?.inputStream()

    override fun displayName(uri: Uri): String? = names[uri]

    override fun takeReadPermission(uri: Uri) = Unit
}

internal class FakeFolders(var saved: Uri? = null) : FolderMemory {
    override suspend fun load() = saved

    override suspend fun save(uri: Uri) {
        saved = uri
    }
}

internal class FakeFolderSource(var files: List<PackageFile> = emptyList(), var name: String? = "Packages") : FolderSource {
    override fun displayName(tree: Uri) = name

    override fun scan(tree: Uri) = files
}

internal class FakeInstaller(private val clock: () -> Long) : Installer {
    data class Call(val packageName: String, val at: Long)

    val installs = mutableListOf<Call>()
    val removed = mutableListOf<String>()
    val outcomes = ArrayDeque<InstallOutcome>()
    var uninstallOutcome: UninstallOutcome = UninstallOutcome.Removed

    /** Suspends the install until completed, to test cancellation and the "waiting for confirmation" state. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun install(apk: File): InstallOutcome {
        val name = apk.nameWithoutExtension
        installs += Call(name, clock())
        gate?.await()
        return outcomes.removeFirstOrNull() ?: InstallOutcome.Installed(name)
    }

    override suspend fun uninstall(packageName: String): UninstallOutcome {
        removed += packageName
        return uninstallOutcome
    }
}

internal class FakeCompanions(var list: List<InstalledCompanion> = emptyList()) : Companions {
    override fun list() = list
}

internal class FakeCatalog : CatalogApi {
    var versions = mutableMapOf<String, VersionInfo>()
    var failure: Exception? = null
    var payload = ByteArray(0)

    override fun version(packageId: String, version: String?): VersionInfo {
        failure?.let { throw it }
        return versions[packageId] ?: throw dev.tizenarchive.companion.catalog.CatalogException(dev.tizenarchive.companion.catalog.CatalogError.NOT_FOUND, "no $packageId")
    }

    override fun download(artifact: Artifact, destination: File, progress: (Long) -> Unit) {
        failure?.let { throw it }
        destination.writeBytes(payload)
        progress(payload.size.toLong())
    }
}

/** Writes a placeholder APK named after the package; the package itself is read for real. */
internal class FakeFactory : CompanionFactory {
    override fun build(source: File, outputDir: File): BuiltCompanion {
        val info = TizenPackageReader.read(source)
        outputDir.mkdirs()
        val name = "companion.${info.packageId}"
        val apk = File(outputDir, "$name.apk").apply { writeText("apk") }
        return BuiltCompanion(apk, name, info)
    }
}

internal class FakePreviews : PackagePreviews {
    override fun load(uri: Uri, size: Long): PackagePreview? = null
}

internal class FakeNotifier : QueueNotifications {
    data class Done(val sent: Int, val skipped: Int, val failed: Int, val cancelled: Int)

    val done = mutableListOf<Done>()
    var cancelled = 0

    override fun notifyDone(sent: Int, skipped: Int, failed: Int, cancelled: Int) {
        done += Done(sent, skipped, failed, cancelled)
    }

    override fun cancel() {
        cancelled++
    }
}
