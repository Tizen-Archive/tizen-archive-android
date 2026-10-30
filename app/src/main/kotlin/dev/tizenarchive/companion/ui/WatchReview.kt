package dev.tizenarchive.companion.ui

import dev.tizenarchive.companion.catalog.CatalogError
import dev.tizenarchive.companion.catalog.PackageSummary
import dev.tizenarchive.companion.catalog.VersionCompare
import dev.tizenarchive.companion.catalog.Verdict
import dev.tizenarchive.companion.sdb.PackageOrigin
import dev.tizenarchive.companion.sdb.WatchPackage
import dev.tizenarchive.companion.sdb.WatchPackageKind
import dev.tizenarchive.companion.wifi.RememberedPackage

/** What the catalog knows about the packages on the watch (Wi-Fi mode). */
internal sealed interface WatchCatalogState {
    data object Idle : WatchCatalogState
    data object Loading : WatchCatalogState

    /** The catalog's packages among those on the watch, by package id. */
    data class Done(val found: Map<String, PackageSummary>) : WatchCatalogState
    data class Failed(val error: CatalogError) : WatchCatalogState
}

internal enum class WatchKindFilter { ALL, FACES, APPS }

/**
 * What a reset would do to the packages the user added: the catalog can install the [restorable] ones again, it holds the [unsure] ones
 * but cannot install their latest version, and the [lost] ones it does not have. [unknown] packages may be the user's: the watch did not tell.
 */
internal class ResetForecast(val restorable: List<WatchPackage>, val unsure: List<WatchPackage>, val lost: List<WatchPackage>, val unknown: Int)

internal object WatchReview {
    /** The catalog's newer version of [item] when it can be installed; null otherwise. */
    fun update(item: WatchPackage, summary: PackageSummary?): String? {
        val latest = summary?.latestVersion ?: return null
        if (summary.verdict != Verdict.INSTALLABLE) return null
        return latest.takeIf { VersionCompare.isNewer(it, item.version) }
    }

    /** The user added it by the watch's own flags or, when the watch did not tell, this app installed it; firmware cannot be removed. */
    fun removable(item: WatchPackage, remembered: Collection<RememberedPackage>): Boolean = when (item.origin) {
        PackageOrigin.ADDED -> true
        null -> remembered.any { it.id == item.id }
        PackageOrigin.BUILT_IN, PackageOrigin.BUILT_IN_UPDATED -> false
    }

    /** Null when the watch told about no package, so nothing can be said. */
    fun forecast(packages: List<WatchPackage>, found: Map<String, PackageSummary>): ResetForecast? {
        if (packages.none { it.origin != null }) return null
        val added = packages.filter { it.origin == PackageOrigin.ADDED }.sortedBy { it.name.lowercase() }
        val (held, lost) = added.partition { it.id in found }
        val (restorable, unsure) = held.partition { found[it.id]?.verdict == Verdict.INSTALLABLE }
        return ResetForecast(restorable, unsure, lost, packages.count { it.origin == null })
    }

    /** Firmware is hidden unless asked for; a package whose kind is unknown shows only without a kind filter. */
    fun visible(packages: List<WatchPackage>, query: String, showBuiltIn: Boolean, kind: WatchKindFilter): List<WatchPackage> {
        val needle = query.trim()
        return packages.filter { item ->
            (showBuiltIn || (item.origin != PackageOrigin.BUILT_IN && item.origin != PackageOrigin.BUILT_IN_UPDATED)) &&
                when (kind) {
                    WatchKindFilter.ALL -> true
                    WatchKindFilter.FACES -> item.kind == WatchPackageKind.WATCH_FACE
                    WatchKindFilter.APPS -> item.kind == WatchPackageKind.APP
                } &&
                (needle.isEmpty() || item.name.contains(needle, ignoreCase = true) || item.id.contains(needle, ignoreCase = true))
        }
    }
}
