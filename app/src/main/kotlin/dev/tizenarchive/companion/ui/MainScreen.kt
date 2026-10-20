package dev.tizenarchive.companion.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tizenarchive.companion.R
import dev.tizenarchive.companion.catalog.CatalogError
import dev.tizenarchive.companion.catalog.NotInstallableReason
import dev.tizenarchive.companion.companions.InstalledCompanion
import dev.tizenarchive.companion.files.PackageFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onFolderPicked(uri)
    }
    val context = LocalContext.current

    var tab by rememberSaveable { mutableIntStateOf(TAB_INSTALL) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(R.string.app_name)) })
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == TAB_INSTALL, onClick = { tab = TAB_INSTALL }, text = { Text(stringResource(R.string.tab_install)) })
                    Tab(
                        selected = tab == TAB_WATCH,
                        onClick = { tab = TAB_WATCH },
                        text = { Text(stringResource(R.string.tab_watch, state.companions.size)) },
                    )
                }
            }
        },
        bottomBar = {
            if (tab == TAB_INSTALL) {
                BottomActions(
                    state = state,
                    onInstall = viewModel::installSelected,
                    onCancel = viewModel::cancel,
                )
            }
        },
    ) { padding ->
        if (tab == TAB_WATCH) {
            WatchTab(padding, state.companions, onRemove = viewModel::askRemoval)
            return@Scaffold
        }
        // One scrolling list: on a small screen or with a long status text nothing is pushed out of view.
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { FolderCard(state, onPick = { picker.launch(null) }) }
            if (state.incomingRejected > 0) {
                item {
                    Text(
                        pluralStringResource(R.plurals.incoming_rejected, state.incomingRejected, state.incomingRejected),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable(onClick = viewModel::dismissIncomingNotice),
                    )
                }
            }
            if (state.allFiles.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.search)) },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::selectAllVisible, enabled = !state.installing) {
                            Text(stringResource(R.string.select_all))
                        }
                        OutlinedButton(onClick = viewModel::clearSelection, enabled = !state.installing) {
                            Text(stringResource(R.string.clear_selection))
                        }
                    }
                }
            }
            items(state.visibleFiles, key = { it.uri.toString() }) { file ->
                Column {
                    FileRow(
                        file = file,
                        checked = file.uri in state.selected,
                        enabled = !state.installing,
                        itemState = state.states[file.uri],
                        onToggle = { viewModel.toggle(file.uri) },
                    )
                    HorizontalDivider()
                }
            }
            if (state.installed > 0) {
                item {
                    Text(
                        stringResource(R.string.wearable_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    state.removal?.let { removal ->
        val wearable = remember { context.packageManager.getLaunchIntentForPackage(GALAXY_WEARABLE) }
        RemovalDialog(
            removal,
            onConfirm = viewModel::confirmRemoval,
            onDismiss = viewModel::dismissRemoval,
            onOpenWearable = wearable?.let { intent ->
                {
                    context.startActivity(intent)
                    viewModel.dismissRemoval()
                }
            },
        )
    }

    state.remote?.let { remote ->
        RemoteDialog(remote, onConfirm = viewModel::confirmRemote, onDismiss = viewModel::dismissRemote)
    }

    if (state.needsInstallPermission) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPermissionDialog,
            title = { Text(stringResource(R.string.allow_unknown_title)) },
            text = { Text(stringResource(R.string.allow_unknown_message)) },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                    )
                }) { Text(stringResource(R.string.open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPermissionDialog) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private const val GALAXY_WEARABLE = "com.samsung.android.app.watchmanager"
private const val TAB_INSTALL = 0
private const val TAB_WATCH = 1

@Composable
private fun WatchTab(padding: PaddingValues, companions: List<InstalledCompanion>, onRemove: (InstalledCompanion) -> Unit) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier
            .padding(padding)
            .padding(horizontal = 16.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.watch_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (companions.isEmpty()) {
            item { Text(stringResource(R.string.watch_empty), modifier = Modifier.padding(top = 16.dp)) }
        }
        items(companions, key = { it.androidPackage }) { companion ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(companion.label, style = MaterialTheme.typography.titleMedium)
                    val details = listOfNotNull(companion.tizenPackageId, companion.tizenVersion?.let { "v$it" }, companion.kind?.uppercase())
                    if (details.isNotEmpty()) {
                        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val sent = DateUtils.formatDateTime(context, companion.updatedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)
                    Text(stringResource(R.string.watch_sent, sent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { onRemove(companion) }) { Text(stringResource(R.string.remove_from_watch)) }
                }
            }
        }
    }
}

@Composable
private fun RemovalDialog(removal: RemovalState, onConfirm: () -> Unit, onDismiss: () -> Unit, onOpenWearable: (() -> Unit)?) {
    val companion = when (removal) {
        is RemovalState.Confirm -> removal.companion
        is RemovalState.Working -> removal.companion
        is RemovalState.Done -> removal.companion
        is RemovalState.Failed -> removal.companion
    }
    val working = removal is RemovalState.Working
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (removal is RemovalState.Done) R.string.remove_done_title else R.string.remove_title, companion.label)) },
        text = {
            Text(
                when (removal) {
                    is RemovalState.Confirm -> stringResource(R.string.remove_message)
                    is RemovalState.Working -> stringResource(R.string.remove_working)
                    is RemovalState.Done -> stringResource(R.string.remove_done_message)
                    is RemovalState.Failed -> stringResource(R.string.remove_failed, removal.reason ?: "")
                },
            )
        },
        confirmButton = {
            when {
                removal is RemovalState.Confirm -> TextButton(onClick = onConfirm) { Text(stringResource(R.string.remove_from_watch)) }
                removal is RemovalState.Done && onOpenWearable != null ->
                    TextButton(onClick = onOpenWearable) { Text(stringResource(R.string.open_wearable)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) {
                Text(stringResource(if (removal is RemovalState.Failed || removal is RemovalState.Done) R.string.ok else R.string.cancel))
            }
        },
    )
}

@Composable
private fun RemoteDialog(remote: RemoteState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val title: String
    val text: String
    var confirmLabel: String? = null
    var dismissLabel = stringResource(R.string.cancel)
    when (remote) {
        is RemoteState.Resolving -> {
            title = stringResource(R.string.remote_resolving_title)
            text = remote.packageId
        }
        is RemoteState.Confirm -> {
            title = remote.info.name ?: remote.info.packageId
            text = stringResource(R.string.remote_confirm_text, remote.info.version, formatSize(remote.artifact.size))
            confirmLabel = stringResource(R.string.install_on_watch)
        }
        is RemoteState.Working -> {
            title = remote.title
            text = describe(remote.item)
        }
        is RemoteState.Finished -> {
            title = remote.title
            text = describe(remote.item)
            dismissLabel = stringResource(R.string.ok)
        }
        is RemoteState.Problem -> {
            title = stringResource(R.string.remote_problem_title)
            text = describe(remote.problem)
            dismissLabel = stringResource(R.string.ok)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { confirmLabel?.let { TextButton(onClick = onConfirm) { Text(it) } } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

@Composable
private fun describe(problem: RemoteProblem): String = when (problem) {
    RemoteProblem.InvalidLink -> stringResource(R.string.problem_invalid_link)
    RemoteProblem.Busy -> stringResource(R.string.problem_busy)
    is RemoteProblem.Catalog -> stringResource(
        when (problem.error) {
            CatalogError.NOT_FOUND -> R.string.problem_not_found
            CatalogError.NETWORK -> R.string.problem_network
            CatalogError.BAD_RESPONSE -> R.string.problem_bad_response
            CatalogError.SIZE_MISMATCH, CatalogError.CHECKSUM_MISMATCH -> R.string.problem_corrupted
            CatalogError.TOO_LARGE -> R.string.problem_too_large
        },
    )
    is RemoteProblem.NotInstallable -> stringResource(
        when (problem.reason) {
            NotInstallableReason.NO_BUILDS -> R.string.reason_no_builds
            NotInstallableReason.NOT_DOWNLOADABLE -> R.string.reason_not_downloadable
            NotInstallableReason.DEVICE_BOUND -> R.string.reason_device_bound
            NotInstallableReason.EXPIRED -> R.string.reason_expired
            NotInstallableReason.REJECTED_BY_WATCH -> R.string.reason_rejected
            NotInstallableReason.UNVERIFIED -> R.string.reason_unverified
        },
    )
}

@Composable
private fun FolderCard(state: UiState, onPick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.scanning -> Text(stringResource(R.string.scanning))
                state.folderName == null -> Text(stringResource(R.string.no_folder))
                else -> Text("${state.folderName} — " + pluralStringResource(R.plurals.files_found, state.files.size, state.files.size))
            }
            Button(onClick = onPick, enabled = !state.installing) {
                Text(stringResource(if (state.folderName == null) R.string.choose_folder else R.string.change_folder))
            }
        }
    }
}

@Composable
private fun FileRow(file: PackageFile, checked: Boolean, enabled: Boolean, itemState: ItemState?, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = itemState?.let { describe(it) } ?: formatSize(file.size),
                style = MaterialTheme.typography.bodySmall,
                color = if (itemState is ItemState.Failed || itemState is ItemState.SignatureConflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun describe(state: ItemState): String = when (state) {
    ItemState.Queued -> stringResource(R.string.state_queued)
    ItemState.Reading -> stringResource(R.string.state_reading)
    is ItemState.Downloading -> stringResource(R.string.state_downloading, state.percent)
    ItemState.Building -> stringResource(R.string.state_building)
    is ItemState.Waiting -> stringResource(R.string.state_waiting, state.secondsLeft)
    ItemState.AwaitingConfirm -> stringResource(R.string.state_confirm)
    ItemState.Installed -> stringResource(R.string.state_done)
    ItemState.Cancelled -> stringResource(R.string.state_cancelled)
    ItemState.SignatureConflict -> stringResource(R.string.state_conflict)
    is ItemState.Failed -> stringResource(R.string.state_failed, state.reason)
}

@Composable
private fun formatSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) stringResource(R.string.size_mb, bytes / (1024.0 * 1024.0)) else stringResource(R.string.size_kb, (bytes / 1024).toInt())

@Composable
private fun BottomActions(state: UiState, onInstall: () -> Unit, onCancel: () -> Unit) {
    // The app draws edge to edge, so the bar itself keeps clear of the navigation bar and lifts above the keyboard.
    Column(
        modifier = Modifier
            .navigationBarsPadding()
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.states.isNotEmpty()) {
            Text(stringResource(R.string.summary, state.installed, state.failed, state.cancelled), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.installing) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        } else {
            Button(onClick = onInstall, enabled = state.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.install_selected, state.selected.size))
            }
        }
    }
}
