package dev.tizenarchive.companion

import android.Manifest
import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import dev.tizenarchive.companion.build.CompanionBuilder
import dev.tizenarchive.companion.build.CompanionFactory
import dev.tizenarchive.companion.catalog.CatalogApi
import dev.tizenarchive.companion.catalog.CatalogClient
import dev.tizenarchive.companion.companions.CompanionRepository
import dev.tizenarchive.companion.companions.Companions
import dev.tizenarchive.companion.files.FolderMemory
import dev.tizenarchive.companion.files.FolderScanner
import dev.tizenarchive.companion.files.FolderSource
import dev.tizenarchive.companion.files.FolderStore
import dev.tizenarchive.companion.files.PackageFile
import dev.tizenarchive.companion.files.PackagePreviews
import dev.tizenarchive.companion.files.PreviewLoader
import dev.tizenarchive.companion.install.Installer
import dev.tizenarchive.companion.install.PackageInstallerGateway
import dev.tizenarchive.companion.notify.QueueNotifications
import dev.tizenarchive.companion.notify.QueueNotifier
import dev.tizenarchive.companion.notify.QueueProgress
import dev.tizenarchive.companion.notify.QueueProgressNotifier
import dev.tizenarchive.companion.signing.KeystoreSigningKey
import dev.tizenarchive.companion.wifi.KeystoreSdbKey
import dev.tizenarchive.companion.wifi.WatchGateway
import dev.tizenarchive.companion.wifi.WatchHistoryMemory
import dev.tizenarchive.companion.wifi.WatchHistoryStore
import dev.tizenarchive.companion.wifi.WatchSettingsMemory
import dev.tizenarchive.companion.wifi.WatchSettingsStore
import dev.tizenarchive.companion.wifi.WifiWatchGateway
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The first Android version with the local network permission. */
internal const val LOCAL_NETWORK_API = 37

/** The few things the app asks the Android system directly; tests replace them. */
internal interface Platform {
    val cacheDir: File

    fun canRequestPackageInstalls(): Boolean

    /** Since Android 17 the local network, where the watch is, is closed to apps without this permission. */
    fun hasLocalNetworkAccess(): Boolean

    fun isInstalled(packageName: String): Boolean

    /** Milliseconds since boot, used to space out deliveries to Gear Plugin. */
    fun elapsedRealtime(): Long

    /** Wall-clock time, for "installed on" dates. */
    fun nowMillis(): Long

    fun openInput(uri: Uri): InputStream?

    fun displayName(uri: Uri): String?

    fun takeReadPermission(uri: Uri)
}

internal class AndroidPlatform(private val context: Context) : Platform {
    override val cacheDir: File get() = context.cacheDir

    override fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun hasLocalNetworkAccess(): Boolean =
        Build.VERSION.SDK_INT < LOCAL_NETWORK_API ||
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    override fun isInstalled(packageName: String): Boolean = runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun openInput(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)

    override fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    override fun takeReadPermission(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}

internal class ContentFolderSource(private val resolver: ContentResolver) : FolderSource {
    override fun displayName(tree: Uri): String? = runCatching { FolderScanner.displayName(resolver, tree) }.getOrNull()

    override fun scan(tree: Uri): List<PackageFile> = runCatching { FolderScanner.scan(resolver, tree) }.getOrDefault(emptyList())
}

/** Everything the view model works with, so that tests can run it on fakes. */
internal class Dependencies(
    val platform: Platform,
    val folders: FolderMemory,
    val folderSource: FolderSource,
    val installer: Installer,
    val companions: Companions,
    val catalog: CatalogApi,
    val factory: CompanionFactory,
    val previews: PackagePreviews,
    val notifier: QueueNotifications,
    val progress: QueueProgress,
    val watch: WatchGateway,
    val watchSettings: WatchSettingsMemory,
    val watchHistory: WatchHistoryMemory,
    val io: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        fun create(application: Application): Dependencies {
            val keys = KeystoreSigningKey()
            return Dependencies(
                platform = AndroidPlatform(application),
                folders = FolderStore(application),
                folderSource = ContentFolderSource(application.contentResolver),
                installer = PackageInstallerGateway(application),
                companions = CompanionRepository(application),
                catalog = CatalogClient(
                    BuildConfig.CATALOG_URL,
                    "tizen-companion-installer/${BuildConfig.VERSION_NAME}",
                    allowCleartext = BuildConfig.DEBUG,
                ),
                // The key is fetched when a companion is built, as before
                factory = CompanionFactory { source, outputDir -> CompanionBuilder(keys.get()).build(source, outputDir) },
                previews = PreviewLoader(application),
                notifier = QueueNotifier(application),
                progress = QueueProgressNotifier(application),
                watch = WifiWatchGateway(application, KeystoreSdbKey()),
                watchSettings = WatchSettingsStore(application),
                watchHistory = WatchHistoryStore(application),
            )
        }
    }
}
