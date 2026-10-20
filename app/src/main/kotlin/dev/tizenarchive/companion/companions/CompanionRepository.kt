package dev.tizenarchive.companion.companions

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.tizenarchive.companion.apk.CompanionMetadata
import dev.tizenarchive.companion.build.CompanionNaming
import java.util.Locale

/** A companion on the phone. Gear Plugin keeps the app on the watch for as long as the companion is installed. */
internal data class InstalledCompanion(
    val androidPackage: String,
    val label: String,
    val tizenPackageId: String?,
    val tizenVersion: String?,
    val kind: String?,
    val firstInstalledAt: Long,
    val updatedAt: Long,
    /** The companion's own APK, from which the Tizen package can be taken out again. */
    val sourceDir: String,
)

/**
 * Lists the companions installed on this phone. Their metadata is written into the companion manifest, so nothing
 * has to be stored by the app: the list survives app updates and cleared app data. Android hides other apps from
 * us unless they match the `<queries>` entry of this app, which the marker component of a companion does.
 */
internal class CompanionRepository(private val context: Context) {
    fun list(): List<InstalledCompanion> {
        val manager = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val packages: List<PackageInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            manager.getInstalledPackages(flags)
        }
        return packages
            .filter { it.packageName.startsWith(CompanionNaming.PACKAGE_PREFIX) }
            .mapNotNull { info ->
                val application = info.applicationInfo ?: return@mapNotNull null
                val meta = application.metaData
                val id = meta?.getString(CompanionMetadata.TIZEN_PACKAGE_ID)
                InstalledCompanion(
                    androidPackage = info.packageName,
                    label = manager.getApplicationLabel(application).toString().takeIf { it != info.packageName } ?: id ?: info.packageName,
                    tizenPackageId = id,
                    tizenVersion = meta?.getString(CompanionMetadata.TIZEN_VERSION),
                    kind = meta?.getString(CompanionMetadata.TIZEN_KIND),
                    firstInstalledAt = info.firstInstallTime,
                    updatedAt = info.lastUpdateTime,
                    sourceDir = application.sourceDir,
                )
            }
            .sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.androidPackage }))
    }
}
