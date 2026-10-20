package dev.tizenarchive.companion.tizen

public enum class TizenPackageKind(public val extension: String) {
    /** Web package: has `config.xml` at the root. */
    WGT("wgt"),

    /** Native package: has `tizen-manifest.xml` at the root. */
    TPK("tpk"),
}

public data class TizenPackageInfo(
    val kind: TizenPackageKind,
    val packageId: String,
    val version: String?,
    val displayName: String?,
)

public class InvalidTizenPackageException(message: String, cause: Throwable? = null) : Exception(message, cause)
