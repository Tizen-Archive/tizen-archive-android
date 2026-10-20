package dev.tizenarchive.companion.catalog

/** Why a version cannot be installed from the catalog. */
public enum class NotInstallableReason { NO_BUILDS, NOT_DOWNLOADABLE, DEVICE_BOUND, EXPIRED, REJECTED_BY_WATCH, UNVERIFIED }

public sealed interface Selection {
    public data class Found(val artifact: Artifact) : Selection

    public data class NotInstallable(val reason: NotInstallableReason) : Selection
}

public object ArtifactSelection {
    /** The first installable build (the API lists the primary one first) that has a downloadable file. */
    public fun select(version: VersionInfo): Selection {
        val installable = version.builds.filter { it.verdict == Verdict.INSTALLABLE }
        installable.flatMap { it.artifacts }.firstOrNull { it.downloadUrl != null }?.let { return Selection.Found(it) }
        val reason = when {
            installable.isNotEmpty() -> NotInstallableReason.NOT_DOWNLOADABLE
            else -> when (version.builds.firstOrNull()?.verdict) {
                null -> NotInstallableReason.NO_BUILDS
                Verdict.DEVICE_BOUND -> NotInstallableReason.DEVICE_BOUND
                Verdict.EXPIRED -> NotInstallableReason.EXPIRED
                Verdict.NOT_INSTALLABLE -> NotInstallableReason.REJECTED_BY_WATCH
                else -> NotInstallableReason.UNVERIFIED
            }
        }
        return Selection.NotInstallable(reason)
    }
}
