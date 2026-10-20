package dev.tizenarchive.companion.apk

/**
 * What a companion tells about the Tizen package it carries, so the app can list the companions on the phone
 * (the companion is the record Gear Plugin keeps for the app on the watch).
 */
public object CompanionMetadata {
    /** An action declared by a marker component of every companion; the app queries for it (package visibility). */
    public const val MARKER_ACTION: String = "dev.tizenarchive.companion.COMPANION"

    /** Name of the marker component. The companion has no code, so the class never exists and is never started. */
    public const val MARKER_COMPONENT: String = "dev.tizenarchive.companion.CompanionMarker"

    public const val TIZEN_PACKAGE_ID: String = "dev.tizenarchive.companion.TIZEN_PACKAGE_ID"
    public const val TIZEN_VERSION: String = "dev.tizenarchive.companion.TIZEN_VERSION"
    public const val TIZEN_KIND: String = "dev.tizenarchive.companion.TIZEN_KIND"

    private const val MAX_LABEL = 80

    /** A label for the settings app: no control characters, at most [MAX_LABEL] characters. */
    public fun sanitizeLabel(value: String): String =
        value.filter { !it.isISOControl() }.trim().take(MAX_LABEL)
}
