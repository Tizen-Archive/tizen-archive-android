package dev.tizenarchive.companion.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.tizenarchive.companion.build.CompanionBuilder
import dev.tizenarchive.companion.build.DeliveryPacing
import dev.tizenarchive.companion.files.FolderScanner
import dev.tizenarchive.companion.files.FolderStore
import dev.tizenarchive.companion.files.PackageFile
import dev.tizenarchive.companion.install.InstallOutcome
import dev.tizenarchive.companion.install.PackageInstallerGateway
import dev.tizenarchive.companion.signing.KeystoreSigningKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface ItemState {
    data object Queued : ItemState
    data object Reading : ItemState
    data object Building : ItemState
    data class Waiting(val secondsLeft: Int) : ItemState
    data object AwaitingConfirm : ItemState
    data object Installed : ItemState
    data object Cancelled : ItemState
    data class Failed(val reason: String) : ItemState

    /** A helper app with this name exists but is signed with another key, so it cannot be updated. */
    data object SignatureConflict : ItemState
}

internal data class UiState(
    val folderName: String? = null,
    val scanning: Boolean = false,
    val files: List<PackageFile> = emptyList(),
    val query: String = "",
    val selected: Set<Uri> = emptySet(),
    val states: Map<Uri, ItemState> = emptyMap(),
    val installing: Boolean = false,
    val needsInstallPermission: Boolean = false,
) {
    val visibleFiles: List<PackageFile>
        get() = if (query.isBlank()) files else files.filter { it.name.contains(query.trim(), ignoreCase = true) }

    val installed: Int get() = states.values.count { it is ItemState.Installed }
    val failed: Int get() = states.values.count { it is ItemState.Failed || it is ItemState.SignatureConflict }
    val cancelled: Int get() = states.values.count { it is ItemState.Cancelled }
}

internal class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val folders = FolderStore(application)
    private val gateway = PackageInstallerGateway(application)
    private val signingKey = KeystoreSigningKey()
    private val workDir = File(application.cacheDir, "work")

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var tree: Uri? = null
    private var job: Job? = null

    /** Elapsed-time mark of the last companion installed on the phone and how long the plugin needs for it. */
    private var lastInstallMark: Long? = null
    private var lastPace: Long = 0

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { workDir.deleteRecursively() }
            folders.load()?.let { openFolder(it) }
        }
    }

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
        val queue = _state.value.files.filter { it.uri in _state.value.selected }
        if (queue.isEmpty()) return
        _state.update { it.copy(installing = true, states = queue.associate { f -> f.uri to ItemState.Queued }) }
        lastInstallMark = null
        job = viewModelScope.launch {
            try {
                for (file in queue) process(file)
            } finally {
                _state.update { it.copy(installing = false) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun openFolder(uri: Uri) {
        tree = uri
        _state.update { it.copy(scanning = true, files = emptyList(), selected = emptySet(), states = emptyMap()) }
        val name = withContext(Dispatchers.IO) { runCatching { FolderScanner.displayName(context.contentResolver, uri) }.getOrNull() }
        val files = withContext(Dispatchers.IO) { runCatching { FolderScanner.scan(context.contentResolver, uri) }.getOrDefault(emptyList()) }
        _state.update { it.copy(folderName = name, scanning = false, files = files) }
    }

    /** Gear Plugin drops deliveries that arrive while it is still busy, so space the installations out. */
    private suspend fun waitForPlugin(set: (ItemState) -> Unit) {
        val mark = lastInstallMark ?: return
        while (true) {
            val left = lastPace - (android.os.SystemClock.elapsedRealtime() - mark)
            if (left <= 0) return
            set(ItemState.Waiting(((left + 999) / 1000).toInt()))
            delay(minOf(left, 1000))
        }
    }

    private suspend fun process(file: PackageFile) {
        fun set(state: ItemState) = _state.update { it.copy(states = it.states + (file.uri to state)) }
        val local = File(workDir, "package.bin")
        val out = File(workDir, "out")
        try {
            set(ItemState.Reading)
            withContext(Dispatchers.IO) {
                workDir.deleteRecursively()
                workDir.mkdirs()
                val input = context.contentResolver.openInputStream(file.uri) ?: error("Cannot open the file")
                input.use { src -> local.outputStream().use { src.copyTo(it) } }
            }
            set(ItemState.Building)
            val built = withContext(Dispatchers.IO) { CompanionBuilder(signingKey.get()).build(local, out) }
            waitForPlugin(::set)
            set(ItemState.AwaitingConfirm)
            when (val outcome = gateway.install(built.apk)) {
                is InstallOutcome.Installed -> {
                    lastInstallMark = android.os.SystemClock.elapsedRealtime()
                    lastPace = DeliveryPacing.delayAfter(file.size).toMillis()
                    set(ItemState.Installed)
                }
                InstallOutcome.Declined -> set(ItemState.Cancelled)
                is InstallOutcome.Failed ->
                    if (outcome.status == android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT) {
                        set(ItemState.SignatureConflict)
                    } else {
                        set(ItemState.Failed(outcome.message ?: "code ${outcome.status}"))
                    }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            set(ItemState.Cancelled)
            throw e
        } catch (e: Exception) {
            set(ItemState.Failed(e.message ?: e.javaClass.simpleName))
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { workDir.deleteRecursively() }
        }
    }

}
