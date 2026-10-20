package dev.tizenarchive.companion.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class PackageInfo(val latestVersion: String? = null)

@Serializable
public enum class Verdict {
    @SerialName("installable") INSTALLABLE,

    @SerialName("device-bound") DEVICE_BOUND,

    @SerialName("expired") EXPIRED,

    @SerialName("not-installable") NOT_INSTALLABLE,

    @SerialName("unverified") UNVERIFIED,

    /** Any value this client does not know about. */
    UNKNOWN,
}

@Serializable
public data class VersionInfo(
    val packageId: String,
    val version: String,
    val name: String? = null,
    val type: String,
    val builds: List<Build> = emptyList(),
)

@Serializable
public data class Build(
    val verdict: Verdict = Verdict.UNKNOWN,
    val artifacts: List<Artifact> = emptyList(),
)

@Serializable
public data class Artifact(
    val sha256: String,
    val size: Long,
    val downloadUrl: String? = null,
)
