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

/** Where a package on the watch came from, by its package manager's own flags. */
public enum class PackageOrigin {
    /** Part of the firmware; a reset keeps it. */
    BUILT_IN,

    /** Part of the firmware, updated since; a reset brings back the firmware's version. */
    BUILT_IN_UPDATED,

    /** Installed by the user (store, companion app, sdb); a reset removes it. */
    ADDED,
}

public enum class WatchPackageKind { WATCH_FACE, APP }

/** A package as `pkgcmd -l` lists it. [origin], [kind] and [iconPath] come from [WatchInstaller.inventory] and are null when the watch did not tell. */
public class WatchPackage(
    public val id: String,
    public val name: String,
    public val version: String,
    public val type: String,
    public val origin: PackageOrigin? = null,
    public val kind: WatchPackageKind? = null,
    public val iconPath: String? = null,
)

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

    /**
     * [list] with where each package came from, what it is and where its icon lies. Packages under /opt/usr/globalapps are told apart
     * by the package manager's preload and update flags, those only under /usr/apps are read-only firmware, and any other is unknown.
     * Watch faces are watch applications or web
     * applications with the wearable clock category; the manifests themselves are closed to the debugging shell.
     */
    public fun inventory(): List<WatchPackage> {
        val details = details()
        return list().map { item ->
            val known = details.packages[item.id]
            val origin = when {
                known?.inGlobalApps == true -> known.origin
                item.id in details.firmware -> PackageOrigin.BUILT_IN
                else -> null
            }
            WatchPackage(item.id, item.name, item.version, item.type, origin, known?.kind, known?.iconPath)
        }
    }

    /** The icon at [path] (one [inventory] reported), or null when the watch has no such file or it is larger than [maxBytes]. */
    public fun icon(path: String, maxBytes: Long = MAX_ICON_BYTES): ByteArray? {
        require(isIconPath(path)) { "Not an icon path" }
        val out = java.io.ByteArrayOutputStream()
        return try {
            if (service.pull(path, out, maxBytes)) out.toByteArray() else null
        } catch (e: SdbException) {
            if (e.error != SdbError.PROTOCOL) throw e
            null
        }
    }

    internal class PackageDetails(val inGlobalApps: Boolean, val origin: PackageOrigin?, val kind: WatchPackageKind?, val iconPath: String?)

    internal class Details(val firmware: Set<String>, val packages: Map<String, PackageDetails>)

    private class App(val packageId: String, var type: String? = null, var component: String? = null)

    internal fun details(): Details {
        val apps = linkedMapOf<String, App>()
        val flags = linkedMapOf<String, MutableMap<String, String>>()
        var app: String? = null
        var pkg: String? = null
        val firmware = mutableSetOf<String>()
        var section = ""
        for (line in service.shell(DETAILS_COMMAND).lineSequence().map { it.trimEnd('\r') }) {
            if (line == FIRMWARE_MARK || line == GLOBAL_APPS_MARK) {
                section = line
                continue
            }
            if (section == FIRMWARE_MARK) {
                if (isPackageId(line)) firmware += line
                continue
            }
            if (section != GLOBAL_APPS_MARK) {
                val field = FIELD_LINE.matchEntire(line) ?: continue
                val value = field.groupValues[2].trim()
                when (field.groupValues[1].lowercase()) {
                    "appid" -> app = value.takeIf(::isAppId)
                    "package" -> app?.let { id -> if (isPackageId(value)) apps[id] = App(value) else app = null }
                    "apptype" -> app?.let { apps[it]?.type = value }
                    "component" -> app?.let { apps[it]?.component = value }
                }
                continue
            }
            PACKAGE_LINE.matchEntire(line)?.let { start ->
                pkg = start.groupValues[1].takeIf(::isPackageId)?.also { flags[it] = mutableMapOf() }
                return@let
            } ?: pkg?.let { current ->
                val field = FIELD_LINE.matchEntire(line) ?: return@let
                val key = field.groupValues[1].lowercase()
                // A description of several lines comes between the icon and the flags: the first icon and the last flags are the package's
                if (key != "icon" || key !in flags.getValue(current)) flags.getValue(current)[key] = field.groupValues[2].trim()
            }
        }

        // SM-R800 registers web faces as watch applications too; the category is asked only of the other web applications, for firmware that does not
        val webApps = apps.filter { (_, a) -> a.packageId in flags && a.type == "webapp" && a.component != "watchapp" }.keys
        val clockApps = webApps.chunked(CATEGORY_CHUNK).flatMap(::clockApps).toSet()
        val faces = apps.filter { (id, a) -> a.component == "watchapp" || id in clockApps }.values.map { it.packageId }.toSet()
        val packages = (apps.values.map { it.packageId } + flags.keys).distinct().associateWith { id ->
            val own = flags[id]
            val preload = own?.get("preload")
            PackageDetails(
                inGlobalApps = own != null,
                origin = when {
                    preload == "0" -> PackageOrigin.ADDED
                    preload == "1" && own["update"] == "1" -> PackageOrigin.BUILT_IN_UPDATED
                    preload == "1" -> PackageOrigin.BUILT_IN
                    else -> null
                },
                kind = when {
                    id in faces -> WatchPackageKind.WATCH_FACE
                    apps.values.any { it.packageId == id } -> WatchPackageKind.APP
                    else -> null
                },
                iconPath = own?.get("icon")?.takeIf { isIconPath(it) && it.startsWith("$GLOBAL_APPS/$id/") },
            )
        }
        return Details(firmware, packages)
    }

    /** The web applications among [appIds] that carry the wearable clock category, which makes them watch faces. */
    private fun clockApps(appIds: List<String>): List<String> {
        val command = appIds.joinToString(" ", prefix = "for a in ", postfix = "; do echo \"app \$a\"; pkginfo --category \"\$a\" 2>/dev/null | grep -i '^ *category'; done")
        var app: String? = null
        val found = mutableListOf<String>()
        for (line in service.shell(command).lineSequence().map { it.trimEnd('\r') }) {
            APP_LINE.matchEntire(line)?.let { app = it.groupValues[1] } ?: run {
                val current = app ?: return@run
                if (line.contains(CLOCK_CATEGORY) && current in appIds && current !in found) found += current
            }
        }
        return found
    }

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

        public const val MAX_ICON_BYTES: Long = 512L * 1024
        private const val GLOBAL_APPS = "/opt/usr/globalapps"
        internal const val FIRMWARE_MARK = "--- firmware"
        internal const val GLOBAL_APPS_MARK = "--- globalapps"

        /**
         * Every application's id, package, type and component (`watchapp` for a watch face); after [FIRMWARE_MARK] the directories of
         * /usr/apps; after [GLOBAL_APPS_MARK] `pkg <id>` and the `pkginfo` lines naming preload, update and icon for every package under /opt/usr/globalapps.
         */
        internal const val DETAILS_COMMAND: String =
            "pkginfo --listapp 2>/dev/null | grep -E '^(Appid|Package|Apptype|component):'; echo '$FIRMWARE_MARK'; ls -1 /usr/apps 2>/dev/null; echo '$GLOBAL_APPS_MARK'; " +
                "cd $GLOBAL_APPS 2>/dev/null && for p in *; do echo \"pkg \$p\"; " +
                "pkginfo --pkg \"\$p\" 2>/dev/null | grep -iE '^ *(preload|update|icon) *:'; done"
        private const val CLOCK_CATEGORY = "http://tizen.org/category/wearable_clock"

        /** Application ids per category command, which keeps the command short. */
        private const val CATEGORY_CHUNK = 30
        private val PACKAGE_LINE = Regex("pkg (\\S+)")
        private val APP_LINE = Regex("app (\\S+)")
        private val FIELD_LINE = Regex("\\s*([A-Za-z_-]+)\\s*:(.*)")
        private val ICON_PATH = Regex("/[A-Za-z0-9._/-]{1,200}")
        private val APP_ID = Regex("[A-Za-z0-9._][A-Za-z0-9._-]{0,127}")

        private fun isAppId(text: String): Boolean = APP_ID.matches(text)

        public fun isPackageId(text: String): Boolean = PACKAGE_ID.matches(text)

        /** A path the sync service can fetch: plain characters only and no `..` part. */
        public fun isIconPath(path: String): Boolean = ICON_PATH.matches(path) && path.split('/').none { it == ".." }
    }
}
