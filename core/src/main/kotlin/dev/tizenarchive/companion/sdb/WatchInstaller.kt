package dev.tizenarchive.companion.sdb

import java.io.File
import java.io.IOException
import java.security.MessageDigest

public enum class PackageType(internal val pkgcmdName: String, internal val extension: String) { WGT("wgt", "wgt"), TPK("tpk", "tpk") }

public sealed interface InstallProgress {
    public class Uploading(public val sent: Long, public val total: Long) : InstallProgress

    /** [percent] is null until `pkgcmd` reports the first value. */
    public class Installing(public val percent: Int?) : InstallProgress
}

public class WatchPackage(public val id: String, public val name: String, public val version: String, public val type: String)

/**
 * Installs and removes packages on a watch through `pkgcmd -w`, the mode that accepts store-signed packages.
 * Shell commands are built only from constants and values checked here, never from text inside a package.
 */
public class WatchInstaller(private val service: SdbService, private val remoteDir: String = DEFAULT_DIR) {
    /** Sends [file], installs it and removes the copy from the watch whatever the outcome. */
    public fun install(file: File, type: PackageType, progress: (InstallProgress) -> Unit = {}): PkgcmdResult {
        val remote = "$remoteDir/${digestPrefix(file)}.${type.extension}"
        // A copy left behind by an install that was cancelled or cut off cannot be removed from the phone's side: do it now
        service.shell("mkdir -p $remoteDir; rm -f $remoteDir/$HEX16.${PackageType.WGT.extension} $remoteDir/$HEX16.${PackageType.TPK.extension}")
        try {
            val total = file.length()
            file.inputStream().use { input -> service.push(remote, input) { progress(InstallProgress.Uploading(it, total)) } }
            progress(InstallProgress.Installing(null))
            val output = PkgcmdOutput()
            service.shell("pkgcmd -i -t ${type.pkgcmdName} -p $remote -w 2>&1") { line ->
                output.feed(line)?.let { progress(InstallProgress.Installing(it)) }
            }
            return output.result()
        } finally {
            try {
                service.shell("rm -f $remote")
            } catch (_: SdbException) {
            }
        }
    }

    /** Every package the watch lists, system ones included: the output does not tell them apart. */
    public fun list(): List<WatchPackage> =
        service.shell("pkgcmd -l 2>&1").lineSequence().mapNotNull { LIST_LINE.find(it) }
            .map { WatchPackage(it.groupValues[2], it.groupValues[3], it.groupValues[4], it.groupValues[1]) }.toList()

    /** The version of the package on the watch, or null when it is not installed. */
    public fun installedVersion(packageId: String): String? = list().firstOrNull { it.id == packageId }?.version

    /** Removes the package; the application data on the watch is lost. */
    public fun remove(packageId: String): PkgcmdResult {
        require(isPackageId(packageId)) { "Invalid package id" }
        val output = PkgcmdOutput()
        service.shell("pkgcmd -u -n $packageId 2>&1") { output.feed(it) }
        return output.result()
    }

    private fun digestPrefix(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        if (file.length() == 0L) throw IOException("The package is empty")
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    public companion object {
        public const val DEFAULT_DIR: String = "/home/owner/share/tmp/sdk_tools"
        private val HEX16 = "[0-9a-f]".repeat(16)
        private val PACKAGE_ID = Regex("[A-Za-z0-9._][A-Za-z0-9._-]{0,127}")
        private val LIST_LINE = Regex("pkg_type \\[(\\w+)]\\s+pkgid \\[([^\\]]+)]\\s+name \\[(.*)]\\s+version \\[([^\\]]*)]")

        public fun isPackageId(text: String): Boolean = PACKAGE_ID.matches(text)
    }
}
