package dev.tizenarchive.companion.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.tizenarchive.companion.BuildConfig
import dev.tizenarchive.companion.apk.CompanionApk
import dev.tizenarchive.companion.build.CompanionBuilder
import dev.tizenarchive.companion.build.DeliveryPacing
import dev.tizenarchive.companion.catalog.Artifact
import dev.tizenarchive.companion.catalog.ArtifactSelection
import dev.tizenarchive.companion.catalog.CatalogClient
import dev.tizenarchive.companion.catalog.CatalogError
import dev.tizenarchive.companion.catalog.CatalogException
import dev.tizenarchive.companion.catalog.InstallLinkParser
import dev.tizenarchive.companion.catalog.InvalidLinkException
import dev.tizenarchive.companion.catalog.NotInstallableReason
import dev.tizenarchive.companion.catalog.Selection
import dev.tizenarchive.companion.catalog.VersionInfo
import dev.tizenarchive.companion.companions.CompanionRepository
import dev.tizenarchive.companion.companions.InstalledCompanion
import dev.tizenarchive.companion.files.FolderScanner
import dev.tizenarchive.companion.files.FolderStore
import dev.tizenarchive.companion.files.PackageFile
import dev.tizenarchive.companion.files.PackagePreview
import dev.tizenarchive.companion.files.PreviewLoader
import dev.tizenarchive.companion.install.InstallOutcome
import dev.tizenarchive.companion.install.PackageInstallerGateway
import dev.tizenarchive.companion.install.UninstallOutcome
import dev.tizenarchive.companion.signing.KeystoreSigningKey
import dev.tizenarchive.companion.tizen.TizenPackageReader
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The key under which the state of sending a companion again is kept (the list items use their file URI). */
internal fun companionKey(androidPackage: String): Uri = Uri.parse("companion:$androidPackage")

internal sealed interface ItemState {
    data object Queued : ItemState
    data object Reading : ItemState
    data class Downloading(val percent: Int) : ItemState
    data object Building : ItemState
    data class Waiting(val secondsLeft: Int) : ItemState
    data object AwaitingConfirm : ItemState
    data object Installed : ItemState

    /** The same version of this package was already sent from this phone. */
    data class AlreadySent(val sentAt: Long) : ItemState
    data object Cancelled : ItemState
    data class Failed(val reason: String) : ItemState

    /** A helper app with this name exists but is signed with another key, so it cannot be updated. */
    data object SignatureConflict : ItemState
}

/** What went wrong while handling a link from the website. */
internal sealed interface RemoteProblem {
    data object InvalidLink : RemoteProblem
    data object Busy : RemoteProblem
    data class NotInstallable(val reason: NotInstallableReason) : RemoteProblem
    data class Catalog(val error: CatalogError) : RemoteProblem
}

internal sealed interface RemoteState {
    data class Resolving(val packageId: String) : RemoteState
    data class Confirm(val info: VersionInfo, val artifact: Artifact) : RemoteState
    data class Working(val title: String, val item: ItemState) : RemoteState
    data class Finished(val title: String, val item: ItemState) : RemoteState
    data class Problem(val problem: RemoteProblem) : RemoteState
}

/** Removing a companion from the phone also removes the app from the watch. */
internal sealed interface RemovalState {
    data class Confirm(val companion: InstalledCompanion) : RemovalState
    data class Working(val companion: InstalledCompanion) : RemovalState

    /** The helper app is gone from the phone; Gear Plugin may or may not have removed the app from the watch. */
    data class Done(val companion: InstalledCompanion) : RemovalState
    data class Failed(val companion: InstalledCompanion, val reason: String?) : RemovalState
}

internal data class UiState(
    val folderName: String? = null,
    val scanning: Boolean = false,
    val files: List<PackageFile> = emptyList(),
    /** Files received from other apps ("Open with", "Share"), copied to the cache. */
    val incoming: List<PackageFile> = emptyList(),
    /** How many received files were not Tizen packages and were dropped. */
    val incomingRejected: Int = 0,
    val query: String = "",
    val selected: Set<Uri> = emptySet(),
    /** Names, versions and icons read from the packages in the list, filled in the background. */
    val previews: Map<Uri, PackagePreview> = emptyMap(),
    val states: Map<Uri, ItemState> = emptyMap(),
    val installing: Boolean = false,
    val needsInstallPermission: Boolean = false,
    val remote: RemoteState? = null,
    val companions: List<InstalledCompanion> = emptyList(),
    val removal: RemovalState? = null,
) {
    val allFiles: List<PackageFile> get() = incoming + files

    val visibleFiles: List<PackageFile>
        get() {
            val needle = query.trim()
            if (needle.isEmpty()) return allFiles
            return allFiles.filter { file ->
                val preview = previews[file.uri]
                file.name.contains(needle, ignoreCase = true) ||
                    preview?.name?.contains(needle, ignoreCase = true) == true ||
                    preview?.packageId?.contains(needle, ignoreCase = true) == true
            }
        }

    val installed: Int get() = states.values.count { it is ItemState.Installed }
    val skipped: Int get() = states.values.count { it is ItemState.AlreadySent }
    val failed: Int get() = states.values.count { it is ItemState.Failed || it is ItemState.SignatureConflict }
    val cancelled: Int get() = states.values.count { it is ItemState.Cancelled }
}

internal class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val folders = FolderStore(application)
    private val gateway = PackageInstallerGateway(application)
    private val companionRepository = CompanionRepository(application)
    private val signingKey = KeystoreSigningKey()
    private val catalog = CatalogClient(
        BuildConfig.CATALOG_URL,
        "tizen-companion-installer/${BuildConfig.VERSION_NAME}",
        allowCleartext = BuildConfig.DEBUG,
    )
    private val workDir = File(application.cacheDir, "work")
    private val previewLoader = PreviewLoader(application)
    private var previewJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var job: Job? = null

    /** Elapsed-time mark of the last companion installed on the phone and how long the plugin needs for it. */
    private var lastInstallMark: Long? = null
    private var lastPace: Long = 0

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                workDir.deleteRecursively()
                File(context.cacheDir, INCOMING_DIR).deleteRecursively()
            }
            folders.load()?.let { openFolder(it) }
        }
        refreshCompanions()
    }

    // ---- Companions on the phone (= apps sent to the watch) ---------------------------------------------

    fun refreshCompanions() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { companionRepository.list() }.getOrDefault(emptyList()) }
            _state.update { it.copy(companions = list) }
        }
    }

    fun askRemoval(companion: InstalledCompanion) = _state.update { it.copy(removal = RemovalState.Confirm(companion)) }

    fun dismissRemoval() {
        if (_state.value.removal !is RemovalState.Working) _state.update { it.copy(removal = null) }
    }

    fun confirmRemoval() {
        val companion = (_state.value.removal as? RemovalState.Confirm)?.companion ?: return
        _state.update { it.copy(removal = RemovalState.Working(companion)) }
        viewModelScope.launch {
            val next = when (val outcome = gateway.uninstall(companion.androidPackage)) {
                UninstallOutcome.Removed -> RemovalState.Done(companion)
                UninstallOutcome.Declined -> null
                is UninstallOutcome.Failed -> RemovalState.Failed(companion, outcome.message)
            }
            _state.update { it.copy(removal = next) }
            refreshCompanions()
        }
    }

    /** Sends the package of a listed companion again, taking it out of the companion itself (e.g. after a failed delivery). */
    fun resend(companion: InstalledCompanion) {
        if (_state.value.installing) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(needsInstallPermission = true) }
            return
        }
        val key = companionKey(companion.androidPackage)
        _state.update { it.copy(installing = true, states = it.states + (key to ItemState.Queued)) }
        lastInstallMark = null
        job = viewModelScope.launch {
            try {
                processItem(key) { target -> CompanionApk.extractPackage(File(companion.sourceDir), target) }
            } finally {
                _state.update { it.copy(installing = false) }
                refreshCompanions()
            }
        }
    }

    // ---- Folder queue -------------------------------------------------------------------------------------

    fun onFolderPicked(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModelScope.launch {
            folders.save(uri)
            openFolder(uri)
        }
    }

    fun refreshInstallPermission() {
        if (context.packageManager.canRequestPackageInstalls()) _state.update { it.copy(needsInstallPermission = false) }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun toggle(uri: Uri) = _state.update {
        it.copy(selected = if (uri in it.selected) it.selected - uri else it.selected + uri)
    }

    fun selectAllVisible() = _state.update { it.copy(selected = it.selected + it.visibleFiles.map(PackageFile::uri)) }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun dismissPermissionDialog() = _state.update { it.copy(needsInstallPermission = false) }

    fun installSelected() {
        if (_state.value.installing) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(needsInstallPermission = true) }
            return
        }
        val queue = _state.value.allFiles.filter { it.uri in _state.value.selected }
        if (queue.isEmpty()) return
        _state.update { it.copy(installing = true, states = queue.associate { f -> f.uri to ItemState.Queued }) }
        lastInstallMark = null
        job = viewModelScope.launch {
            try {
                for (file in queue) processLocal(file)
            } finally {
                _state.update { it.copy(installing = false) }
                refreshCompanions()
                forgetSentIncoming()
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    // ---- Files from other apps ("Open with", "Share") -------------------------------------------------

    fun onIncoming(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val (accepted, rejected) = withContext(Dispatchers.IO) { copyIncoming(uris.take(MAX_INCOMING)) }
            _state.update { state ->
                val known = state.incoming.map { it.uri }.toSet()
                val fresh = accepted.filter { it.uri !in known }
                state.copy(
                    incoming = state.incoming + fresh,
                    selected = state.selected + fresh.map(PackageFile::uri),
                    incomingRejected = state.incomingRejected + rejected,
                )
            }
            loadPreviews()
        }
    }

    fun dismissIncomingNotice() = _state.update { it.copy(incomingRejected = 0) }

    private fun copyIncoming(uris: List<Uri>): Pair<List<PackageFile>, Int> {
        val dir = File(context.cacheDir, INCOMING_DIR).apply { mkdirs() }
        val accepted = mutableListOf<PackageFile>()
        var rejected = 0
        for (uri in uris) {
            val name = displayName(uri) ?: "package-${System.nanoTime()}"
            val target = File(dir, "${System.nanoTime()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)}")
            try {
                val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open")
                input.use { src -> target.outputStream().use { src.copyTo(it) } }
                TizenPackageReader.read(target)
                accepted += PackageFile(Uri.fromFile(target), name, target.length())
            } catch (e: Exception) {
                target.delete()
                rejected++
            }
        }
        return accepted to rejected
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    /** Received files that were installed are no longer needed. */
    private fun forgetSentIncoming() {
        val sent = _state.value.incoming.filter { _state.value.states[it.uri] is ItemState.Installed }
        if (sent.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { sent.forEach { it.uri.path?.let { path -> File(path).delete() } } }
        _state.update { state ->
            val gone = sent.map(PackageFile::uri).toSet()
            state.copy(incoming = state.incoming.filterNot { it.uri in gone }, selected = state.selected - gone)
        }
    }

    // ---- Link from the website ----------------------------------------------------------------------------

    fun onInstallLink(link: String) {
        if (_state.value.installing) {
            _state.update { it.copy(remote = RemoteState.Problem(RemoteProblem.Busy)) }
            return
        }
        val parsed = try {
            InstallLinkParser.parse(link)
        } catch (e: InvalidLinkException) {
            _state.update { it.copy(remote = RemoteState.Problem(RemoteProblem.InvalidLink)) }
            return
        }
        _state.update { it.copy(remote = RemoteState.Resolving(parsed.packageId)) }
        job = viewModelScope.launch {
            val next = try {
                val info = withContext(Dispatchers.IO) { catalog.version(parsed.packageId, parsed.version) }
                when (val selection = ArtifactSelection.select(info)) {
                    is Selection.Found -> RemoteState.Confirm(info, selection.artifact)
                    is Selection.NotInstallable -> RemoteState.Problem(RemoteProblem.NotInstallable(selection.reason))
                }
            } catch (e: CatalogException) {
                RemoteState.Problem(RemoteProblem.Catalog(e.error))
            } catch (e: IllegalArgumentException) {
                RemoteState.Problem(RemoteProblem.InvalidLink)
            }
            _state.update { it.copy(remote = next) }
        }
    }

    fun confirmRemote() {
        val confirm = _state.value.remote as? RemoteState.Confirm ?: return
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(needsInstallPermission = true) }
            return
        }
        val title = confirm.info.name ?: confirm.info.packageId
        fun set(item: ItemState) = _state.update { it.copy(remote = RemoteState.Working(title, item)) }
        _state.update { it.copy(installing = true) }
        lastInstallMark = null
        job = viewModelScope.launch {
            var final: ItemState = ItemState.Cancelled
            try {
                final = runCatchingItem(::set) {
                    set(ItemState.Downloading(0))
                    val local = File(workDir, "package.bin")
                    withContext(Dispatchers.IO) {
                        workDir.deleteRecursively()
                        workDir.mkdirs()
                        var lastPercent = -1
                        catalog.download(confirm.artifact, local) { done ->
                            val percent = if (confirm.artifact.size > 0) (done * 100 / confirm.artifact.size).toInt() else 100
                            if (percent != lastPercent) {
                                lastPercent = percent
                                set(ItemState.Downloading(percent))
                            }
                        }
                    }
                    buildAndInstall(local, ::set)
                }
            } finally {
                _state.update { it.copy(installing = false, remote = RemoteState.Finished(title, final)) }
                refreshCompanions()
            }
        }
    }

    fun dismissRemote() {
        if (_state.value.installing) job?.cancel()
        _state.update { it.copy(remote = null) }
    }

    // ---- Shared pipeline ----------------------------------------------------------------------------------

    private suspend fun openFolder(uri: Uri) {
        _state.update { it.copy(scanning = true, files = emptyList(), selected = emptySet(), states = emptyMap()) }
        val name = withContext(Dispatchers.IO) { runCatching { FolderScanner.displayName(context.contentResolver, uri) }.getOrNull() }
        val files = withContext(Dispatchers.IO) { runCatching { FolderScanner.scan(context.contentResolver, uri) }.getOrDefault(emptyList()) }
        _state.update { it.copy(folderName = name, scanning = false, files = files, previews = emptyMap()) }
        loadPreviews()
    }

    /** Reads names and icons of the listed packages one by one in the background; the list updates as they arrive. */
    private fun loadPreviews() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.IO) {
            val pending = _state.value.allFiles.filter { it.uri !in _state.value.previews }.take(MAX_PREVIEWS)
            val batch = mutableMapOf<Uri, PackagePreview>()
            for ((index, file) in pending.withIndex()) {
                ensureActive()
                runCatching { previewLoader.load(file.uri, file.size) }.getOrNull()?.let { batch[file.uri] = it }
                if (batch.size >= PREVIEW_BATCH || index == pending.lastIndex) {
                    val chunk = batch.toMap()
                    batch.clear()
                    _state.update { it.copy(previews = it.previews + chunk) }
                }
            }
        }
    }

    private suspend fun processLocal(file: PackageFile) = processItem(file.uri, skipIfSent = true) { target ->
        val input = context.contentResolver.openInputStream(file.uri) ?: error("Cannot open the file")
        input.use { src -> target.outputStream().use { src.copyTo(it) } }
    }

    /** Takes the package ([prepare] writes it to the file), builds a companion, installs it and records the state under [key]. */
    private suspend fun processItem(key: Uri, skipIfSent: Boolean = false, prepare: suspend (File) -> Unit) {
        fun set(state: ItemState) = _state.update { it.copy(states = it.states + (key to state)) }
        val state = runCatchingItem(::set) {
            set(ItemState.Reading)
            val local = File(workDir, "package.bin")
            withContext(Dispatchers.IO) {
                workDir.deleteRecursively()
                workDir.mkdirs()
                prepare(local)
            }
            if (skipIfSent) {
                val sentAt = withContext(Dispatchers.IO) { sentAt(local) }
                if (sentAt != null) return@runCatchingItem ItemState.AlreadySent(sentAt)
            }
            buildAndInstall(local, ::set)
        }
        set(state)
    }

    /** When this very version of the package was already sent from this phone (a companion with the same id and version). */
    private fun sentAt(local: File): Long? {
        val info = runCatching { TizenPackageReader.read(local) }.getOrNull() ?: return null
        return companionRepository.list()
            .firstOrNull { it.tizenPackageId == info.packageId && it.tizenVersion == info.version }
            ?.updatedAt
    }

    /** Runs [block], turns exceptions into a final item state and always cleans the work directory. */
    private suspend fun runCatchingItem(set: (ItemState) -> Unit, block: suspend () -> ItemState): ItemState =
        try {
            block()
        } catch (e: CancellationException) {
            set(ItemState.Cancelled)
            throw e
        } catch (e: CatalogException) {
            ItemState.Failed(e.message ?: e.error.name)
        } catch (e: Exception) {
            ItemState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { workDir.deleteRecursively() }
        }

    private suspend fun buildAndInstall(local: File, set: (ItemState) -> Unit): ItemState {
        set(ItemState.Building)
        val built = withContext(Dispatchers.IO) { CompanionBuilder(signingKey.get()).build(local, File(workDir, "out")) }
        waitForPlugin(set)
        set(ItemState.AwaitingConfirm)
        return when (val outcome = gateway.install(built.apk)) {
            is InstallOutcome.Installed -> {
                lastInstallMark = SystemClock.elapsedRealtime()
                lastPace = DeliveryPacing.delayAfter(local.length()).toMillis()
                ItemState.Installed
            }
            InstallOutcome.Declined -> ItemState.Cancelled
            is InstallOutcome.Failed ->
                if (outcome.status == PackageInstaller.STATUS_FAILURE_CONFLICT) {
                    ItemState.SignatureConflict
                } else {
                    ItemState.Failed(outcome.message ?: "code ${outcome.status}")
                }
        }
    }

    /** Gear Plugin drops deliveries that arrive while it is still busy, so space the installations out. */
    private suspend fun waitForPlugin(set: (ItemState) -> Unit) {
        val mark = lastInstallMark ?: return
        while (true) {
            val left = lastPace - (SystemClock.elapsedRealtime() - mark)
            if (left <= 0) return
            set(ItemState.Waiting(((left + 999) / 1000).toInt()))
            delay(minOf(left, 1000))
        }
    }

    private companion object {
        const val INCOMING_DIR = "incoming"
        const val MAX_INCOMING = 200
        const val MAX_PREVIEWS = 1000
        const val PREVIEW_BATCH = 8
    }

}
