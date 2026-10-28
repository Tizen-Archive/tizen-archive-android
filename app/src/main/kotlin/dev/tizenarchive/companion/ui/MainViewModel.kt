package dev.tizenarchive.companion.ui

import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tizenarchive.companion.Dependencies
import dev.tizenarchive.companion.apk.CompanionApk
import dev.tizenarchive.companion.build.DeliveryPacing
import dev.tizenarchive.companion.capture.CaptureResult
import dev.tizenarchive.companion.capture.Delivery
import dev.tizenarchive.companion.catalog.Artifact
import dev.tizenarchive.companion.catalog.ArtifactSelection
import dev.tizenarchive.companion.catalog.CatalogError
import dev.tizenarchive.companion.catalog.CatalogException
import dev.tizenarchive.companion.catalog.InstallLinkParser
import dev.tizenarchive.companion.catalog.InvalidLinkException
import dev.tizenarchive.companion.catalog.NotInstallableReason
import dev.tizenarchive.companion.catalog.Selection
import dev.tizenarchive.companion.catalog.VersionCompare
import dev.tizenarchive.companion.catalog.VersionInfo
import dev.tizenarchive.companion.companions.InstalledCompanion
import dev.tizenarchive.companion.files.PackageFile
import dev.tizenarchive.companion.files.PackagePreview
import dev.tizenarchive.companion.install.InstallOutcome
import dev.tizenarchive.companion.install.UninstallOutcome
import dev.tizenarchive.companion.notify.ProgressInfo
import dev.tizenarchive.companion.sdb.InstallProgress
import dev.tizenarchive.companion.sdb.PackageType
import dev.tizenarchive.companion.sdb.SdbError
import dev.tizenarchive.companion.sdb.WatchPackage
import dev.tizenarchive.companion.sdb.WatchAddress
import dev.tizenarchive.companion.tizen.TizenPackageKind
import dev.tizenarchive.companion.tizen.TizenPackageReader
import dev.tizenarchive.companion.wifi.CaptureEvent
import dev.tizenarchive.companion.wifi.FindOutcome
import dev.tizenarchive.companion.wifi.InstallMode
import dev.tizenarchive.companion.wifi.PackageIdentity
import dev.tizenarchive.companion.wifi.RememberedPackage
import dev.tizenarchive.companion.wifi.WatchListOutcome
import dev.tizenarchive.companion.wifi.WatchOutcome
import dev.tizenarchive.companion.wifi.WatchRemoveOutcome
import dev.tizenarchive.companion.wifi.WatchSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the screen can ask for; [MainViewModel] implements it and UI tests record the calls. */
internal interface MainActions {
    fun cancel()
    fun checkUpdates()
    fun update(companion: InstalledCompanion)
    fun resend(companion: InstalledCompanion)
    fun askRemoval(companion: InstalledCompanion)
    fun dismissIncomingNotice()
    fun setQuery(query: String)
    fun selectAllVisible()
    fun clearSelection()
    fun toggle(uri: Uri)
    fun confirmRemoval()
    fun dismissRemoval()
    fun confirmRemote()
    fun dismissRemote()
    fun dismissPermissionDialog()
    fun setMode(mode: InstallMode)
    fun setWatchAddress(address: String)
    fun findWatches()
    fun setReinstall(reinstall: Boolean)
    fun refreshWatchList()
    fun setWatchQuery(query: String)
    fun askWatchRemoval(item: WatchPackage)
    fun confirmWatchRemoval()
    fun dismissWatchRemoval()
    fun startCapture()
    fun stopCapture()
}

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

    /** Wi-Fi mode: the package is being sent to the watch. */
    data class Uploading(val percent: Int) : ItemState

    /** Wi-Fi mode: the watch is installing; [percent] is null until it reports the first value. */
    data class InstallingOnWatch(val percent: Int?) : ItemState

    data object InstalledOnWatch : ItemState

    /** Wi-Fi mode: the watch refused the package; [code] and [message] come from its package manager. */
    data class WatchRefused(val code: Int?, val message: String?) : ItemState

    data class WatchProblem(val error: SdbError) : ItemState

    data object NoWifi : ItemState

    /** Wi-Fi mode: the watch already has this version or a newer one. */
    data class AlreadyOnWatch(val version: String) : ItemState
}

internal fun ItemState?.isFailure(): Boolean = this is ItemState.Failed || this is ItemState.SignatureConflict || this is ItemState.WatchRefused ||
    this is ItemState.WatchProblem || this is ItemState.NoWifi

private fun ItemState.isFinal(): Boolean = this is ItemState.Installed || this is ItemState.InstalledOnWatch || this is ItemState.AlreadySent ||
    this is ItemState.Cancelled || this is ItemState.Failed || this is ItemState.SignatureConflict || this is ItemState.WatchRefused ||
    this is ItemState.WatchProblem || this is ItemState.NoWifi || this is ItemState.AlreadyOnWatch

/** A watch found by the search; [busy] means another sdb host is connected to it right now. */
internal data class FoundAddress(val address: String, val busy: Boolean)

/** What the watch reports about its packages (Wi-Fi mode). */
internal sealed interface WatchListState {
    data object Idle : WatchListState
    data object Loading : WatchListState
    data class Loaded(val packages: List<WatchPackage>) : WatchListState
    data class Problem(val error: SdbError) : WatchListState
    data object NoWifi : WatchListState
    data object NoPermission : WatchListState
}

internal sealed interface WatchRemoval {
    data class Confirm(val item: WatchPackage) : WatchRemoval
    data class Working(val item: WatchPackage) : WatchRemoval

    /** [refusal] is the text from the watch; [error] is a connection problem. */
    data class Failed(val item: WatchPackage, val refusal: String?, val error: SdbError?) : WatchRemoval
}

internal sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Done(val watches: List<FoundAddress>) : ScanState
    data object NoWifi : ScanState
    data object NoSubnet : ScanState

    /** The local network permission is missing, so the phone cannot see the watch. */
    data object NoPermission : ScanState
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

/** A newer version of a sent package that the catalog can provide. */
internal data class UpdateInfo(val version: String, val artifact: Artifact)

internal sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Checking : UpdateCheck

    /** [failed] packages could not be checked (no connection or an unexpected answer). */
    data class Done(val checked: Int, val failed: Int) : UpdateCheck
}

/** Things the app needs on the phone and cannot find. */
internal enum class Missing { GALAXY_WEARABLE, GEAR_PLUGIN, INSTALL_PERMISSION }

internal sealed interface CaptureStatus {
    data object Connecting : CaptureStatus

    data object Watching : CaptureStatus

    data object NoWifi : CaptureStatus

    /** The connection failed or broke; the capture tries again by itself. */
    data class Problem(val error: SdbError) : CaptureStatus
}

/** Why a capture could not start, or why a captured package was not saved. */
internal enum class CaptureHint { NO_FOLDER, NO_PERMISSION, NO_WRITE_ACCESS }

internal enum class CaptureOutcome {
    SAVED,

    /** Saved, but the file has no distributor signature, so the watch will not accept it from another phone either. */
    SAVED_UNSIGNED,

    /** The folder already has a file of that name. */
    ALREADY_THERE,

    /** The watch's installer was faster. */
    MISSED,
    INVALID,
    NOT_SAVED,
}

/** One package the capture met; [fileName] is the name in the folder, or the name on the watch when nothing was saved. */
internal data class CapturedItem(val title: String, val fileName: String, val outcome: CaptureOutcome)

/** The capture of deliveries; [running] false keeps the last results on screen. */
internal data class CaptureState(
    val running: Boolean = false,
    val status: CaptureStatus = CaptureStatus.Connecting,
    val receiving: List<Delivery> = emptyList(),
    val items: List<CapturedItem> = emptyList(),
    val hint: CaptureHint? = null,
)

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
    val missing: Set<Missing> = emptySet(),
    /** Newer catalog versions of sent packages, by companion package name. */
    val updates: Map<String, UpdateInfo> = emptyMap(),
    val updateCheck: UpdateCheck = UpdateCheck.Idle,
    val companions: List<InstalledCompanion> = emptyList(),
    val removal: RemovalState? = null,
    val mode: InstallMode = InstallMode.COMPANION,
    val watchAddress: String = "",
    /** The address field is shown as wrong after a start with an address that is not a private IPv4 one. */
    val watchAddressInvalid: Boolean = false,
    val scan: ScanState = ScanState.Idle,
    /** Wi-Fi mode: install again even when the watch already has the version. */
    val reinstall: Boolean = false,
    val watchList: WatchListState = WatchListState.Idle,
    val watchQuery: String = "",
    val watchRemoval: WatchRemoval? = null,
    val capture: CaptureState = CaptureState(),
    /** Packages this app put on the watch; only these can be removed from the app. */
    val remembered: List<RememberedPackage> = emptyList(),
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

    val installed: Int get() = states.values.count { it is ItemState.Installed || it is ItemState.InstalledOnWatch }
    val skipped: Int get() = states.values.count { it is ItemState.AlreadySent || it is ItemState.AlreadyOnWatch }
    val failed: Int get() = states.values.count { it.isFailure() }
    val cancelled: Int get() = states.values.count { it is ItemState.Cancelled }
}

internal class MainViewModel(private val deps: Dependencies) : ViewModel(), MainActions {
    private val platform = deps.platform
    private val io = deps.io
    private val folders = deps.folders
    private val gateway = deps.installer
    private val companionRepository = deps.companions
    private val catalog = deps.catalog
    private val previewLoader = deps.previews
    private val notifier = deps.notifier
    private val workDir = File(platform.cacheDir, "work")
    private var previewJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var job: Job? = null
    private val progress = deps.progress

    /** Which item of the running operation is being handled, for the notification. */
    private var position = Triple(1, 1, null as String?)

    private fun report(state: ItemState) {
        if (state.isFinal()) return
        progress.update(ProgressInfo(position.first, position.second, position.third, state))
    }

    /**
     * Elapsed-time mark of the last companion installed on the phone and how long the plugin needs for it. It outlives a
     * single operation: an update or a link started right after another installation must wait for the plugin too.
     */
    private var lastInstallMark: Long? = null
    private var lastPace: Long = 0

    /** Clears what an earlier run left in the cache; files received from other apps must wait for it, or it deletes them. */
    private val cleanup: Job

    init {
        progress.onCancel = ::cancel
        cleanup = viewModelScope.launch {
            withContext(io) {
                workDir.deleteRecursively()
                File(platform.cacheDir, INCOMING_DIR).deleteRecursively()
                File(platform.cacheDir, CAPTURE_DIR).deleteRecursively()
            }
        }
        viewModelScope.launch {
            cleanup.join()
            folders.load()?.let { openFolder(it) }
        }
        viewModelScope.launch {
            val saved = withContext(io) { deps.watchSettings.load() }
            _state.update { it.copy(mode = saved.mode, watchAddress = saved.address, reinstall = saved.reinstall) }
        }
        viewModelScope.launch {
            val items = withContext(io) { deps.watchHistory.load() }
            _state.update { it.copy(remembered = items) }
        }
        refreshCompanions()
        refreshMissing()
    }

    // ---- Companions on the phone (= apps sent to the watch) ---------------------------------------------

    fun refreshCompanions() {
        viewModelScope.launch {
            val list = withContext(io) { runCatching { companionRepository.list() }.getOrDefault(emptyList()) }
            _state.update { it.copy(companions = list) }
        }
    }

    override fun askRemoval(companion: InstalledCompanion) = _state.update { it.copy(removal = RemovalState.Confirm(companion)) }

    override fun dismissRemoval() {
        if (_state.value.removal !is RemovalState.Working) _state.update { it.copy(removal = null) }
    }

    override fun confirmRemoval() {
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
    override fun resend(companion: InstalledCompanion) {
        if (_state.value.installing) return
        if (!platform.canRequestPackageInstalls()) {
            _state.update { it.copy(needsInstallPermission = true) }
            return
        }
        val key = companionKey(companion.androidPackage)
        position = Triple(1, 1, companion.label)
        _state.update { it.copy(installing = true, states = it.states + (key to ItemState.Queued)) }
        job = viewModelScope.launch {
            try {
                processItem(key) { target, _ -> CompanionApk.extractPackage(File(companion.sourceDir), target) }
            } finally {
                _state.update { it.copy(installing = false) }
                progress.stop()
                refreshCompanions()
            }
        }
    }

    // ---- Updates from the catalog ---------------------------------------------------------------------------

    /** Asks the catalog for the latest version of every sent package; runs only when the user taps the button. */
    override fun checkUpdates() {
        if (_state.value.updateCheck == UpdateCheck.Checking || _state.value.installing) return
        _state.update { it.copy(updateCheck = UpdateCheck.Checking, updates = emptyMap()) }
        viewModelScope.launch {
            val found = mutableMapOf<String, UpdateInfo>()
            var checked = 0
            var failed = 0
            for (companion in _state.value.companions) {
                val id = companion.tizenPackageId ?: continue
                val installed = companion.tizenVersion ?: continue
                checked++
                try {
                    val latest = withContext(io) { catalog.version(id, null) }
                    val selection = ArtifactSelection.select(latest) as? Selection.Found ?: continue
                    if (VersionCompare.isNewer(latest.version, installed)) {
                        found[companion.androidPackage] = UpdateInfo(latest.version, selection.artifact)
                    }
                } catch (e: CatalogException) {
                    // A package that is not in the catalog is not an error; anything else is counted
                    if (e.error != CatalogError.NOT_FOUND) failed++
                } catch (e: IllegalArgumentException) {
                    // The id has characters the catalog does not take; nothing to look up
                }
            }
            _state.update { it.copy(updates = found, updateCheck = UpdateCheck.Done(checked, failed)) }
        }
    }

    /** Downloads the newer version and sends it to the watch as an update of the companion. */
    override fun update(companion: InstalledCompanion) {
        val update = _state.value.updates[companion.androidPackage] ?: return
        if (_state.value.installing) return
        if (!platform.canRequestPackageInstalls()) {
            _state.update { it.copy(needsInstallPermission = true) }
            return
        }
        val key = companionKey(companion.androidPackage)
        position = Triple(1, 1, companion.label)
        _state.update { it.copy(installing = true, states = it.states + (key to ItemState.Queued)) }
        job = viewModelScope.launch {
            try {
                processItem(key) { target, set ->
                    set(ItemState.Downloading(0))
                    var lastPercent = -1
                    catalog.download(update.artifact, target) { done ->
                        val percent = if (update.artifact.size > 0) (done * 100 / update.artifact.size).toInt() else 100
                        if (percent != lastPercent) {
                            lastPercent = percent
                            set(ItemState.Downloading(percent))
                        }
                    }
                }
                if (_state.value.states[key] is ItemState.Installed) {
                    _state.update { it.copy(updates = it.updates - companion.androidPackage) }
                }
            } finally {
                _state.update { it.copy(installing = false) }
                progress.stop()
                refreshCompanions()
            }
        }
    }

    // ---- Folder queue -------------------------------------------------------------------------------------

    fun onFolderPicked(uri: Uri) {
        platform.takeFolderPermission(uri)
        viewModelScope.launch {
            folders.save(uri)
            openFolder(uri)
        }
    }

    /** The app is in front again: the "queue finished" notification is no longer needed. */
    fun clearNotification() = notifier.cancel()

    fun refreshInstallPermission() {
        if (platform.canRequestPackageInstalls()) _state.update { it.copy(needsInstallPermission = false) }
        refreshMissing()
    }

    /** Checks for Galaxy Wearable, Samsung's Gear Plugin and the permission to install apps. */
    private fun refreshMissing() {
        val missing = buildSet {
            if (!platform.isInstalled(GALAXY_WEARABLE)) add(Missing.GALAXY_WEARABLE)
            if (!platform.isInstalled(GEAR_PLUGIN)) add(Missing.GEAR_PLUGIN)
            if (!platform.canRequestPackageInstalls()) add(Missing.INSTALL_PERMISSION)
        }
        _state.update { it.copy(missing = missing) }
    }

    override fun setQuery(query: String) = _state.update { it.copy(query = query) }

    override fun toggle(uri: Uri) = _state.update {
        it.copy(selected = if (uri in it.selected) it.selected - uri else it.selected + uri)
    }

    override fun selectAllVisible() = _state.update { it.copy(selected = it.selected + it.visibleFiles.map(PackageFile::uri)) }

    override fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    override fun dismissPermissionDialog() = _state.update { it.copy(needsInstallPermission = false) }

    // ---- Delivery mode and the watch's address ------------------------------------------------------------

    override fun setMode(mode: InstallMode) {
        if (_state.value.installing) return
        if (mode != InstallMode.WIFI) stopCapture()
        _state.update { it.copy(mode = mode, watchAddressInvalid = false) }
        saveSettings()
    }

    override fun setWatchAddress(address: String) {
        _state.update { it.copy(watchAddress = address, watchAddressInvalid = false) }
        saveSettings()
    }

    override fun setReinstall(reinstall: Boolean) {
        _state.update { it.copy(reinstall = reinstall) }
        saveSettings()
    }

    // ---- The watch's packages (Wi-Fi mode) ---------------------------------------------------------------

    override fun setWatchQuery(query: String) = _state.update { it.copy(watchQuery = query) }

    override fun refreshWatchList() {
        val current = _state.value
        if (current.mode != InstallMode.WIFI || current.installing || current.capture.running || current.watchList == WatchListState.Loading) return
        val address = WatchAddress.parse(current.watchAddress.trim())
        if (address == null) {
            _state.update { it.copy(watchAddressInvalid = true) }
            return
        }
        if (!platform.hasLocalNetworkAccess()) {
            _state.update { it.copy(watchList = WatchListState.NoPermission) }
            return
        }
        _state.update { it.copy(watchList = WatchListState.Loading) }
        viewModelScope.launch {
            val next = try {
                when (val outcome = deps.watch.list(address)) {
                    is WatchListOutcome.Listed -> WatchListState.Loaded(outcome.packages)
                    is WatchListOutcome.Problem -> WatchListState.Problem(outcome.error)
                    WatchListOutcome.NoWifi -> WatchListState.NoWifi
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(watchList = WatchListState.Idle) }
                throw e
            } catch (e: Exception) {
                WatchListState.Problem(SdbError.DISCONNECTED)
            }
            _state.update { it.copy(watchList = next) }
        }
    }

    override fun askWatchRemoval(item: WatchPackage) {
        val current = _state.value
        if (current.installing || current.watchRemoval is WatchRemoval.Working) return
        // Only what this app installed can be removed from here: the watch does not tell system packages apart
        if (current.remembered.none { it.id == item.id }) return
        _state.update { it.copy(watchRemoval = WatchRemoval.Confirm(item)) }
    }

    override fun dismissWatchRemoval() {
        if (_state.value.watchRemoval is WatchRemoval.Working) return
        _state.update { it.copy(watchRemoval = null) }
    }

    override fun confirmWatchRemoval() {
        val confirm = _state.value.watchRemoval as? WatchRemoval.Confirm ?: return
        val item = confirm.item
        val address = WatchAddress.parse(_state.value.watchAddress.trim())
        if (address == null) {
            _state.update { it.copy(watchRemoval = null, watchAddressInvalid = true) }
            return
        }
        _state.update { it.copy(watchRemoval = WatchRemoval.Working(item)) }
        viewModelScope.launch {
            releaseWatch()
            val failure = try {
                when (val outcome = deps.watch.remove(address, item.id)) {
                    WatchRemoveOutcome.Removed -> null
                    is WatchRemoveOutcome.Refused -> WatchRemoval.Failed(item, listOfNotNull(outcome.message, outcome.code?.toString()).joinToString(" "), null)
                    is WatchRemoveOutcome.Problem -> WatchRemoval.Failed(item, null, outcome.error)
                    WatchRemoveOutcome.NoWifi -> WatchRemoval.Failed(item, null, SdbError.UNREACHABLE)
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(watchRemoval = null) }
                throw e
            } catch (e: Exception) {
                WatchRemoval.Failed(item, null, SdbError.DISCONNECTED)
            }
            if (failure != null) {
                _state.update { it.copy(watchRemoval = failure) }
                return@launch
            }
            withContext(io) { deps.watchHistory.forget(item.id) }
            _state.update { state ->
                val list = (state.watchList as? WatchListState.Loaded)?.let { WatchListState.Loaded(it.packages.filterNot { p -> p.id == item.id }) } ?: state.watchList
                state.copy(watchRemoval = null, watchList = list, remembered = state.remembered.filterNot { it.id == item.id })
            }
        }
    }

    override fun findWatches() {
        if (_state.value.installing || _state.value.scan == ScanState.Scanning) return
        if (!platform.hasLocalNetworkAccess()) {
            _state.update { it.copy(scan = ScanState.NoPermission) }
            return
        }
        _state.update { it.copy(scan = ScanState.Scanning) }
        viewModelScope.launch {
            val next = try {
                when (val outcome = deps.watch.find()) {
                    is FindOutcome.Found -> ScanState.Done(outcome.watches.map { FoundAddress(it.address.hostAddress.orEmpty(), it.busy) })
                    FindOutcome.NoWifi -> ScanState.NoWifi
                    FindOutcome.NoSubnet -> ScanState.NoSubnet
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(scan = ScanState.Idle) }
                throw e
            } catch (e: Exception) {
                ScanState.Done(emptyList())
            }
            _state.update { it.copy(scan = next) }
            // One watch found: no need to choose
            (next as? ScanState.Done)?.watches?.singleOrNull()?.let { setWatchAddress(it.address) }
        }
    }

    // ---- Capturing what Galaxy Wearable delivers to the watch (Wi-Fi mode) -------------------------------

    private var captureJob: Job? = null

    /** Names saved by the running capture: the folder list is rescanned in the background and may lag behind. */
    private val savedByCapture = mutableSetOf<String>()

    override fun startCapture() {
        val current = _state.value
        if (current.capture.running || current.installing || current.mode != InstallMode.WIFI) return
        val address = WatchAddress.parse(current.watchAddress.trim())
        if (address == null) {
            _state.update { it.copy(watchAddressInvalid = true) }
            return
        }
        if (!platform.hasLocalNetworkAccess()) {
            setCapture { it.copy(hint = CaptureHint.NO_PERMISSION) }
            return
        }
        _state.update { it.copy(capture = CaptureState(running = true)) }
        savedByCapture.clear()
        captureJob = viewModelScope.launch {
            val folder = folders.load()
            if (folder == null) {
                setCapture { it.copy(running = false, hint = CaptureHint.NO_FOLDER) }
                return@launch
            }
            val dir = File(platform.cacheDir, CAPTURE_DIR)
            progress.capturing(0)
            try {
                deps.watch.capture(address, dir) { onCaptureEvent(it, folder) }
            } finally {
                setCapture { it.copy(running = false, receiving = emptyList()) }
                progress.stop()
                withContext(NonCancellable + io) { dir.deleteRecursively() }
            }
        }
    }

    override fun stopCapture() {
        captureJob?.cancel()
    }

    /** The watch takes one sdb connection at a time: a running capture gives way to whatever else needs the watch. */
    private suspend fun releaseWatch() {
        captureJob?.cancelAndJoin()
    }

    private fun setCapture(change: (CaptureState) -> CaptureState) = _state.update { it.copy(capture = change(it.capture)) }

    private suspend fun onCaptureEvent(event: CaptureEvent, folder: Uri) {
        when (event) {
            CaptureEvent.Watching -> setCapture { it.copy(status = CaptureStatus.Watching) }
            CaptureEvent.NoWifi -> setCapture { it.copy(status = CaptureStatus.NoWifi, receiving = emptyList()) }
            is CaptureEvent.Problem -> setCapture { it.copy(status = CaptureStatus.Problem(event.error), receiving = emptyList()) }
            is CaptureEvent.Receiving -> setCapture { it.copy(receiving = event.deliveries) }
            is CaptureEvent.Result -> {
                val item = withContext(io) { keepCaptured(event.result, folder) }
                setCapture {
                    it.copy(items = listOf(item) + it.items, hint = if (item.outcome == CaptureOutcome.NOT_SAVED) CaptureHint.NO_WRITE_ACCESS else it.hint)
                }
                if (item.outcome == CaptureOutcome.SAVED || item.outcome == CaptureOutcome.SAVED_UNSIGNED) {
                    progress.capturing(savedByCapture.size)
                    viewModelScope.launch { openFolder(folder) }
                }
            }
        }
    }

    private fun keepCaptured(result: CaptureResult, folder: Uri): CapturedItem = when (result) {
        is CaptureResult.Missed -> CapturedItem(result.name, result.name, CaptureOutcome.MISSED)
        is CaptureResult.Invalid -> CapturedItem(result.name, result.name, CaptureOutcome.INVALID)
        is CaptureResult.Captured -> try {
            val info = result.info
            val name = "${info.packageId}-${info.version ?: "0"}.${info.kind.extension}".replace(UNSAFE_NAME, "_")
            val outcome = when {
                name in savedByCapture || _state.value.files.any { it.name.equals(name, ignoreCase = true) } -> CaptureOutcome.ALREADY_THERE
                !platform.saveToFolder(folder, name, result.file) -> CaptureOutcome.NOT_SAVED
                else -> {
                    savedByCapture += name
                    if (result.storeSigned) CaptureOutcome.SAVED else CaptureOutcome.SAVED_UNSIGNED
                }
            }
            CapturedItem(info.displayName ?: info.packageId, name, outcome)
        } catch (e: IOException) {
            CapturedItem(result.info.displayName ?: result.info.packageId, result.info.packageId, CaptureOutcome.NOT_SAVED)
        } finally {
            result.file.delete()
        }
    }

    private fun saveSettings() {
        val current = _state.value
        viewModelScope.launch { withContext(io) { deps.watchSettings.save(WatchSettings(current.mode, current.watchAddress, current.reinstall)) } }
    }

    /** False when the mode needs something the user has not provided yet; the screen shows what. */
    private fun readyToDeliver(): Boolean {
        if (_state.value.mode == InstallMode.WIFI) {
            if (WatchAddress.parse(_state.value.watchAddress.trim()) != null) return true
            _state.update { it.copy(watchAddressInvalid = true) }
            return false
        }
        if (platform.canRequestPackageInstalls()) return true
        _state.update { it.copy(needsInstallPermission = true) }
        return false
    }

    fun installSelected() {
        if (_state.value.installing) return
        if (!readyToDeliver()) return
        val queue = _state.value.allFiles.filter { it.uri in _state.value.selected }
        if (queue.isEmpty()) return
        _state.update { it.copy(installing = true, states = queue.associate { f -> f.uri to ItemState.Queued }) }
        job = viewModelScope.launch {
            try {
                releaseWatch()
                for ((index, file) in queue.withIndex()) {
                    position = Triple(index + 1, queue.size, _state.value.previews[file.uri]?.name ?: file.name)
                    processLocal(file)
                }
            } finally {
                _state.update { it.copy(installing = false) }
                progress.stop()
                refreshCompanions()
                forgetSentIncoming()
                if (queue.size > 1) {
                    val states = _state.value.states
                    notifier.notifyDone(
                        sent = states.values.count { it is ItemState.Installed },
                        skipped = states.values.count { it is ItemState.AlreadySent },
                        failed = states.values.count { it.isFailure() },
                        cancelled = states.values.count { it is ItemState.Cancelled },
                    )
                }
            }
        }
    }

    override fun onCleared() {
        progress.onCancel = null
        progress.stop()
    }

    override fun cancel() {
        job?.cancel()
        captureJob?.cancel()
    }

    // ---- Files from other apps ("Open with", "Share") -------------------------------------------------

    fun onIncoming(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            cleanup.join()
            val (accepted, rejected) = withContext(io) { copyIncoming(uris.take(MAX_INCOMING)) }
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

    override fun dismissIncomingNotice() = _state.update { it.copy(incomingRejected = 0) }

    private fun copyIncoming(uris: List<Uri>): Pair<List<PackageFile>, Int> {
        val dir = File(platform.cacheDir, INCOMING_DIR).apply { mkdirs() }
        val accepted = mutableListOf<PackageFile>()
        var rejected = 0
        for (uri in uris) {
            val name = platform.displayName(uri) ?: "package-${System.nanoTime()}"
            val target = File(dir, "${System.nanoTime()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)}")
            try {
                val input = platform.openInput(uri) ?: error("Cannot open")
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

    /** Received files that were installed are no longer needed. */
    private fun forgetSentIncoming() {
        val sent = _state.value.incoming.filter { _state.value.states[it.uri] is ItemState.Installed }
        if (sent.isEmpty()) return
        viewModelScope.launch(io) { sent.forEach { it.uri.path?.let { path -> File(path).delete() } } }
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
                val info = withContext(io) { catalog.version(parsed.packageId, parsed.version) }
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

    override fun confirmRemote() {
        val confirm = _state.value.remote as? RemoteState.Confirm ?: return
        if (!readyToDeliver()) return
        val title = confirm.info.name ?: confirm.info.packageId
        position = Triple(1, 1, title)
        fun set(item: ItemState) {
            _state.update { it.copy(remote = RemoteState.Working(title, item)) }
            report(item)
        }
        _state.update { it.copy(installing = true) }
        job = viewModelScope.launch {
            var final: ItemState = ItemState.Cancelled
            try {
                releaseWatch()
                final = runCatchingItem(::set) {
                    set(ItemState.Downloading(0))
                    val local = File(workDir, "package.bin")
                    withContext(io) {
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
                    deliver(local, ::set)
                }
            } finally {
                _state.update { it.copy(installing = false, remote = RemoteState.Finished(title, final)) }
                progress.stop()
                refreshCompanions()
            }
        }
    }

    override fun dismissRemote() {
        if (_state.value.installing) job?.cancel()
        _state.update { it.copy(remote = null) }
    }

    // ---- Shared pipeline ----------------------------------------------------------------------------------

    private suspend fun openFolder(uri: Uri) {
        _state.update { it.copy(scanning = true, files = emptyList(), selected = emptySet(), states = emptyMap()) }
        val name = withContext(io) { deps.folderSource.displayName(uri) }
        val files = withContext(io) { deps.folderSource.scan(uri) }
        _state.update { it.copy(folderName = name, scanning = false, files = files, previews = emptyMap()) }
        loadPreviews()
    }

    /** Reads names and icons of the listed packages one by one in the background; the list updates as they arrive. */
    private fun loadPreviews() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch(io) {
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

    private suspend fun processLocal(file: PackageFile) = processItem(file.uri, skipIfSent = true) { target, _ ->
        val input = platform.openInput(file.uri) ?: error("Cannot open the file")
        input.use { src -> target.outputStream().use { src.copyTo(it) } }
    }

    /** Takes the package ([prepare] writes it to the file), builds a companion, installs it and records the state under [key]. */
    private suspend fun processItem(key: Uri, skipIfSent: Boolean = false, prepare: suspend (File, (ItemState) -> Unit) -> Unit) {
        fun set(state: ItemState) {
            _state.update { it.copy(states = it.states + (key to state)) }
            report(state)
        }
        val state = runCatchingItem(::set) {
            set(ItemState.Reading)
            val local = File(workDir, "package.bin")
            withContext(io) {
                workDir.deleteRecursively()
                workDir.mkdirs()
                prepare(local, ::set)
            }
            if (skipIfSent) {
                val sentAt = withContext(io) { sentAt(local) }
                if (sentAt != null) return@runCatchingItem ItemState.AlreadySent(sentAt)
            }
            deliver(local, ::set)
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
            withContext(NonCancellable + io) { workDir.deleteRecursively() }
        }

    private suspend fun deliver(local: File, set: (ItemState) -> Unit): ItemState =
        if (_state.value.mode == InstallMode.WIFI) installOnWatch(local, set) else buildAndInstall(local, set)

    /** Wi-Fi mode: the phone talks sdb to the watch and runs `pkgcmd -w` there; the watch answers with the exact outcome. */
    private suspend fun installOnWatch(local: File, set: (ItemState) -> Unit): ItemState {
        val address = WatchAddress.parse(_state.value.watchAddress.trim()) ?: error("Invalid watch address")
        val info = TizenPackageReader.read(local)
        val type = if (info.kind == TizenPackageKind.TPK) PackageType.TPK else PackageType.WGT
        val skipRule = if (_state.value.reinstall) null else PackageIdentity(info.packageId, info.version)
        set(ItemState.Uploading(0))
        val outcome = deps.watch.install(address, local, type, skipRule) { event ->
            set(
                when (event) {
                    is InstallProgress.Uploading -> ItemState.Uploading(if (event.total > 0) (event.sent * 100 / event.total).toInt() else 100)
                    is InstallProgress.Installing -> ItemState.InstallingOnWatch(event.percent)
                },
            )
        }
        return when (outcome) {
            WatchOutcome.Installed -> {
                val item = RememberedPackage(info.packageId, info.version, info.displayName, platform.nowMillis())
                withContext(io) { deps.watchHistory.record(item) }
                _state.update { it.copy(remembered = it.remembered.filterNot { r -> r.id == item.id } + item) }
                ItemState.InstalledOnWatch
            }
            is WatchOutcome.AlreadyInstalled -> ItemState.AlreadyOnWatch(outcome.version)
            is WatchOutcome.Refused -> ItemState.WatchRefused(outcome.code, outcome.message)
            is WatchOutcome.Problem -> ItemState.WatchProblem(outcome.error)
            WatchOutcome.NoWifi -> ItemState.NoWifi
        }
    }

    private suspend fun buildAndInstall(local: File, set: (ItemState) -> Unit): ItemState {
        set(ItemState.Building)
        val built = withContext(io) { deps.factory.build(local, File(workDir, "out")) }
        waitForPlugin(set)
        set(ItemState.AwaitingConfirm)
        return when (val outcome = gateway.install(built.apk)) {
            is InstallOutcome.Installed -> {
                lastInstallMark = platform.elapsedRealtime()
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
            val left = lastPace - (platform.elapsedRealtime() - mark)
            if (left <= 0) return
            set(ItemState.Waiting(((left + 999) / 1000).toInt()))
            delay(minOf(left, 1000))
        }
    }

    private companion object {
        const val GALAXY_WEARABLE = "com.samsung.android.app.watchmanager"
        const val GEAR_PLUGIN = "com.samsung.android.geargplugin"
        const val INCOMING_DIR = "incoming"
        const val CAPTURE_DIR = "capture"
        val UNSAFE_NAME = Regex("[^A-Za-z0-9._-]")
        const val MAX_INCOMING = 200
        const val MAX_PREVIEWS = 1000
        const val PREVIEW_BATCH = 8
    }

}
