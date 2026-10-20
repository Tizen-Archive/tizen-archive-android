package dev.tizenarchive.companion.apk

import dev.tizenarchive.companion.axml.AxmlAttribute
import dev.tizenarchive.companion.axml.AxmlElement
import dev.tizenarchive.companion.axml.AxmlNamespace
import dev.tizenarchive.companion.axml.AxmlValue
import dev.tizenarchive.companion.axml.AxmlWriter

/** The manifest of a code-free companion APK that Gear Plugin delivers to the watch. */
public data class CompanionManifest(
    val packageName: String,
    val versionCode: Int = 1,
    val versionName: String = "1.0",
    val minSdk: Int = 26,
    val targetSdk: Int = 36,
    /** Shown in Android's app settings; also marks the manifest as carrying Tizen package metadata. */
    val label: String? = null,
    val tizenPackageId: String? = null,
    val tizenVersion: String? = null,
    val tizenKind: String? = null,
) {
    init {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid Android package name: $packageName" }
        require(packageName.length <= 255) { "Package name too long" }
        require(versionCode > 0) { "versionCode must be positive" }
        require(minSdk in 1..targetSdk) { "minSdk must not exceed targetSdk" }
    }

    public fun toBinaryXml(): ByteArray = AxmlWriter.write(element(), listOf(ANDROID))

    private fun element(): AxmlElement = AxmlElement(
        name = "manifest",
        attributes = listOf(
            AxmlAttribute("versionCode", AxmlValue.IntDec(versionCode), ANDROID, ATTR_VERSION_CODE),
            AxmlAttribute("versionName", AxmlValue.Text(versionName), ANDROID, ATTR_VERSION_NAME),
            AxmlAttribute("package", AxmlValue.Text(packageName)),
        ),
        children = listOf(
            AxmlElement(
                "uses-sdk",
                listOf(
                    AxmlAttribute("minSdkVersion", AxmlValue.IntDec(minSdk), ANDROID, ATTR_MIN_SDK),
                    AxmlAttribute("targetSdkVersion", AxmlValue.IntDec(targetSdk), ANDROID, ATTR_TARGET_SDK),
                ),
            ),
            AxmlElement(
                "uses-permission",
                listOf(AxmlAttribute("name", AxmlValue.Text(WATCH_MANAGER_PERMISSION), ANDROID, ATTR_NAME)),
            ),
            AxmlElement(
                "application",
                listOfNotNull(
                    AxmlAttribute("hasCode", AxmlValue.Bool(false), ANDROID, ATTR_HAS_CODE),
                    AxmlAttribute("allowBackup", AxmlValue.Bool(false), ANDROID, ATTR_ALLOW_BACKUP),
                    label?.let { AxmlAttribute("label", AxmlValue.Text(CompanionMetadata.sanitizeLabel(it)), ANDROID, ATTR_LABEL) },
                ),
                children = metadata(),
            ),
        ),
    )

    private fun metadata(): List<AxmlElement> {
        val values = listOf(
            CompanionMetadata.TIZEN_PACKAGE_ID to tizenPackageId,
            CompanionMetadata.TIZEN_VERSION to tizenVersion,
            CompanionMetadata.TIZEN_KIND to tizenKind,
        )
        val entries = values.mapNotNull { (name, value) ->
            value?.let {
                AxmlElement(
                    "meta-data",
                    listOf(
                        AxmlAttribute("name", AxmlValue.Text(name), ANDROID, ATTR_NAME),
                        AxmlAttribute("value", AxmlValue.Text(it), ANDROID, ATTR_VALUE),
                    ),
                )
            }
        }
        if (entries.isEmpty()) return emptyList()
        // Without code the marker is never started; it only makes the companion discoverable through a <queries> intent
        val marker = AxmlElement(
            "activity",
            listOf(
                AxmlAttribute("name", AxmlValue.Text(CompanionMetadata.MARKER_COMPONENT), ANDROID, ATTR_NAME),
                AxmlAttribute("exported", AxmlValue.Bool(true), ANDROID, ATTR_EXPORTED),
            ),
            listOf(
                AxmlElement(
                    "intent-filter",
                    children = listOf(
                        AxmlElement(
                            "action",
                            listOf(AxmlAttribute("name", AxmlValue.Text(CompanionMetadata.MARKER_ACTION), ANDROID, ATTR_NAME)),
                        ),
                    ),
                ),
            ),
        )
        return entries + marker
    }

    public companion object {
        /** Requesting this permission is what makes Gear Plugin treat the APK as a watch-app carrier. */
        public const val WATCH_MANAGER_PERMISSION: String = "com.samsung.wmanager.APP"

        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val ANDROID = AxmlNamespace("android", "http://schemas.android.com/apk/res/android")

        private const val ATTR_LABEL = 0x01010001
        private const val ATTR_NAME = 0x01010003
        private const val ATTR_EXPORTED = 0x01010010
        private const val ATTR_HAS_CODE = 0x0101000c
        private const val ATTR_VALUE = 0x01010024
        private const val ATTR_MIN_SDK = 0x0101020c
        private const val ATTR_VERSION_CODE = 0x0101021b
        private const val ATTR_VERSION_NAME = 0x0101021c
        private const val ATTR_TARGET_SDK = 0x01010270
        private const val ATTR_ALLOW_BACKUP = 0x01010280
    }
}
