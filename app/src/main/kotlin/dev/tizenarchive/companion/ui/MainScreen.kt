package dev.tizenarchive.companion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tizenarchive.companion.LOCAL_NETWORK_API
import dev.tizenarchive.companion.R
import dev.tizenarchive.companion.catalog.CatalogError
import dev.tizenarchive.companion.catalog.NotInstallableReason
import dev.tizenarchive.companion.companions.InstalledCompanion
import dev.tizenarchive.companion.files.PackageFile
import dev.tizenarchive.companion.files.PackagePreview
import dev.tizenarchive.companion.sdb.SdbError
import dev.tizenarchive.companion.signature.SignatureAssessment
import dev.tizenarchive.companion.signature.SignatureVerdict
import dev.tizenarchive.companion.wifi.InstallMode
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onFolderPicked(uri)
    }
    val context = LocalContext.current

    // A long queue must not be cut off by the screen turning off
    val view = LocalView.current
    DisposableEffect(state.installing) {
        view.keepScreenOn = state.installing
        onDispose { view.keepScreenOn = false }
    }

    // Ask once for the right to show "queue finished"; the queue starts either way
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.installSelected() }
    val proceed = {
        val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && state.selected.size > 1 && !notificationsAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) {
            notificationsAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.installSelected()
        }
    }

    // Android 17 closes the local network to apps that have not been allowed to use it; the watch is on the local network
    var localNetworkAsked by rememberSaveable { mutableStateOf(false) }
    val localNetworkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { proceed() }
    val startInstall = {
        val needsLocalNetwork = state.mode == InstallMode.WIFI && Build.VERSION.SDK_INT >= LOCAL_NETWORK_API && !localNetworkAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        if (needsLocalNetwork) {
            localNetworkAsked = true
            localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            proceed()
        }
    }

    // The search and the watch's package list need the same permission as the install, so it is asked for before the first use
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val localNetworkForActions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { afterPermission?.invoke() }
    val actions = remember(viewModel, state.mode) {
        fun withLocalNetwork(action: () -> Unit) {
            val needs = Build.VERSION.SDK_INT >= LOCAL_NETWORK_API &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
            if (needs) {
                afterPermission = action
                localNetworkForActions.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            } else {
                action()
            }
        }
        object : MainActions by viewModel {
            override fun findWatches() = withLocalNetwork(viewModel::findWatches)

            override fun refreshWatchList() = withLocalNetwork(viewModel::refreshWatchList)
        }
    }

    MainContent(state, actions, onPickFolder = { picker.launch(null) }, onInstall = startInstall)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainContent(state: UiState, actions: MainActions, onPickFolder: () -> Unit, onInstall: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(TAB_INSTALL) }
    // Opening the tab asks the watch for its packages once
    LaunchedEffect(tab, state.mode) {
        if (tab == TAB_WATCH && state.mode == InstallMode.WIFI && state.watchList == WatchListState.Idle) actions.refreshWatchList()
    }
    // Each page keeps its own scroll position, also across switching tabs and the way of sending
    val installList = rememberLazyListState()
    val companionList = rememberLazyListState()
    val wifiList = rememberLazyListState()
    // The way of sending slides away when scrolling down and comes back on scrolling up; the tabs stay
    val headerScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    LaunchedEffect(state.hasSetupProblem()) { if (state.hasSetupProblem()) headerScroll.state.heightOffset = 0f }
    Scaffold(
        modifier = Modifier.nestedScroll(headerScroll.nestedScrollConnection),
        topBar = {
            // No title bar: the screen is small and the launcher already shows the name. Everything keeps clear of the status bar.
            Column(modifier = Modifier.statusBarsPadding()) {
                CollapsingHeader(headerScroll.state) { SendHeader(state, actions) }
                PrimaryTabRow(selectedTabIndex = tab) {
                    // The chosen tab is also bold: with the system's dynamic colours the two label colours can be close
                    val selectedColor = MaterialTheme.colorScheme.primary
                    val otherColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Tab(
                        selected = tab == TAB_INSTALL,
                        onClick = { tab = TAB_INSTALL },
                        selectedContentColor = selectedColor,
                        unselectedContentColor = otherColor,
                        text = {
                            Text(stringResource(R.string.tab_install), fontWeight = if (tab == TAB_INSTALL) FontWeight.Bold else FontWeight.Normal)
                        },
                    )
                    Tab(
                        selected = tab == TAB_WATCH,
                        onClick = { tab = TAB_WATCH },
                        selectedContentColor = selectedColor,
                        unselectedContentColor = otherColor,
                        text = {
                            Text(
                                if (state.mode == InstallMode.WIFI) stringResource(R.string.tab_watch_wifi) else stringResource(R.string.tab_watch, state.companions.size),
                                fontWeight = if (tab == TAB_WATCH) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }
        },
        bottomBar = {
            if (tab == TAB_INSTALL) {
                BottomActions(
                    state = state,
                    onInstall = onInstall,
                    onCancel = actions::cancel,
                )
            }
        },
    ) { padding ->
        if (tab == TAB_WATCH) {
            Crossfade(targetState = state.mode, animationSpec = tween(CROSSFADE_MS), label = "watch tab") { mode ->
                if (mode == InstallMode.WIFI) {
                    WifiWatchTab(padding, state, actions, wifiList)
                } else {
                    WatchTab(
                        padding,
                        companionList,
                        state.companions,
                        states = state.states,
                        installing = state.installing,
                        updates = state.updates,
                        updateCheck = state.updateCheck,
                        onCheckUpdates = actions::checkUpdates,
                        onUpdate = actions::update,
                        onResend = actions::resend,
                        onCancel = actions::cancel,
                        onRemove = actions::askRemoval,
                    )
                }
            }
            return@Scaffold
        }
        // One scrolling list: on a small screen or with a long status text nothing is pushed out of view.
        LazyColumn(
            state = installList,
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { FolderCard(state, onPick = { onPickFolder() }) }
            if (state.incomingRejected > 0) {
                item {
                    Text(
                        pluralStringResource(R.plurals.incoming_rejected, state.incomingRejected, state.incomingRejected),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable(onClick = actions::dismissIncomingNotice),
                    )
                }
            }
            if (state.allFiles.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = actions::setQuery,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.search)) },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = actions::selectAllVisible, enabled = !state.installing) {
                            Text(stringResource(R.string.select_all))
                        }
                        OutlinedButton(onClick = actions::clearSelection, enabled = !state.installing) {
                            Text(stringResource(R.string.clear_selection))
                        }
                    }
                }
            }
            items(state.visibleFiles, key = { it.uri.toString() }) { file ->
                Column {
                    FileRow(
                        file = file,
                        preview = state.previews[file.uri],
                        checked = file.uri in state.selected,
                        enabled = !state.installing,
                        itemState = state.states[file.uri],
                        onToggle = { actions.toggle(file.uri) },
                    )
                    HorizontalDivider()
                }
            }
            if (state.installed > 0 && state.mode == InstallMode.COMPANION) {
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
            onConfirm = actions::confirmRemoval,
            onDismiss = actions::dismissRemoval,
            onOpenWearable = wearable?.let { intent ->
                {
                    context.startActivity(intent)
                    actions.dismissRemoval()
                }
            },
        )
    }

    state.remote?.let { remote ->
        val route = if (state.mode == InstallMode.WIFI) {
            stringResource(R.string.remote_route_wifi, state.watchAddress.trim())
        } else {
            stringResource(R.string.remote_route_companion)
        }
        RemoteDialog(remote, route, onConfirm = actions::confirmRemote, onDismiss = actions::dismissRemote)
    }

    state.watchRemoval?.let { removal ->
        WatchRemovalDialog(removal, onConfirm = actions::confirmWatchRemoval, onDismiss = actions::dismissWatchRemoval)
    }

    if (state.needsInstallPermission) {
        AlertDialog(
            onDismissRequest = actions::dismissPermissionDialog,
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
                TextButton(onClick = actions::dismissPermissionDialog) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}


private const val GALAXY_WEARABLE = "com.samsung.android.app.watchmanager"
private const val TAB_INSTALL = 0
private const val TAB_WATCH = 1

@Composable
private fun WatchTab(
    padding: PaddingValues,
    listState: LazyListState,
    companions: List<InstalledCompanion>,
    states: Map<Uri, ItemState>,
    installing: Boolean,
    updates: Map<String, UpdateInfo>,
    updateCheck: UpdateCheck,
    onCheckUpdates: () -> Unit,
    onUpdate: (InstalledCompanion) -> Unit,
    onResend: (InstalledCompanion) -> Unit,
    onCancel: () -> Unit,
    onRemove: (InstalledCompanion) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        state = listState,
        modifier = Modifier
            .padding(padding)
            .padding(horizontal = 16.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SourceNote(stringResource(R.string.source_companion)) }
        item {
            Text(
                stringResource(R.string.watch_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (companions.isEmpty()) {
            item { Text(stringResource(R.string.watch_empty), modifier = Modifier.padding(top = 16.dp)) }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = onCheckUpdates, enabled = updateCheck != UpdateCheck.Checking && !installing) {
                        Text(stringResource(if (updateCheck == UpdateCheck.Checking) R.string.checking_updates else R.string.check_updates))
                    }
                    if (updateCheck is UpdateCheck.Done) {
                        val text = when {
                            updateCheck.failed > 0 -> stringResource(R.string.updates_error, updateCheck.failed)
                            updates.isEmpty() -> stringResource(R.string.updates_none)
                            else -> pluralStringResource(R.plurals.updates_found, updates.size, updates.size)
                        }
                        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
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
                    val itemState = states[companionKey(companion.androidPackage)]
                    if (itemState != null) {
                        Text(
                            describe(itemState),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (itemState.isFailure()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val update = updates[companion.androidPackage]
                    if (update != null && itemState == null) {
                        Text(
                            stringResource(R.string.update_available, update.version),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    val working = installing && itemState != null && itemState !is ItemState.Installed && itemState !is ItemState.Failed && itemState !is ItemState.Cancelled && itemState !is ItemState.SignatureConflict
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (working) {
                            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                        } else {
                            if (update != null) {
                                Button(onClick = { onUpdate(companion) }, enabled = !installing) { Text(stringResource(R.string.update_to, update.version)) }
                            }
                            OutlinedButton(onClick = { onResend(companion) }, enabled = !installing) { Text(stringResource(R.string.resend_to_watch)) }
                            OutlinedButton(onClick = { onRemove(companion) }, enabled = !installing) { Text(stringResource(R.string.remove_from_watch)) }
                        }
                    }
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
private fun RemoteDialog(remote: RemoteState, route: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
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
            text = stringResource(R.string.remote_confirm_text, remote.info.version, formatSize(remote.artifact.size)) + "\n\n" + route
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
internal fun MissingCard(missing: Set<Missing>) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.missing_title), style = MaterialTheme.typography.titleSmall)
            if (Missing.GALAXY_WEARABLE in missing) {
                Text(stringResource(R.string.missing_wearable))
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.samsung.android.app.watchmanager")))
                }) { Text(stringResource(R.string.missing_open_store)) }
            } else if (Missing.GEAR_PLUGIN in missing) {
                Text(stringResource(R.string.missing_plugin))
            }
            if (Missing.INSTALL_PERMISSION in missing) {
                Text(stringResource(R.string.missing_permission))
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                }) { Text(stringResource(R.string.open_settings)) }
            }
        }
    }
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
private fun FileRow(file: PackageFile, preview: PackagePreview?, checked: Boolean, enabled: Boolean, itemState: ItemState?, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            preview?.icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(preview?.name ?: file.name, style = MaterialTheme.typography.bodyLarge)
            val details = listOfNotNull(
                preview?.packageId,
                preview?.version?.let { "v$it" },
                preview?.kind?.uppercase(),
                formatSize(file.size),
            ).joinToString(" · ")
            Text(
                text = itemState?.let { describe(it) } ?: details,
                style = MaterialTheme.typography.bodySmall,
                color = if (itemState is ItemState.Failed || itemState is ItemState.SignatureConflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val warning = preview?.signature?.takeIf { it.worthWarning && itemState == null }
            if (warning != null) {
                Text(describe(warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (preview?.name != null && itemState == null) {
                Text(file.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun describe(assessment: SignatureAssessment): String {
    val date = assessment.notAfter?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)).orEmpty()
    return when (assessment.verdict) {
        SignatureVerdict.UNSIGNED -> stringResource(R.string.sig_unsigned)
        SignatureVerdict.FORGED -> stringResource(R.string.sig_forged)
        SignatureVerdict.UNTRUSTED_BY_WATCH -> stringResource(R.string.sig_untrusted)
        SignatureVerdict.EXPIRED -> stringResource(R.string.sig_expired, date)
        SignatureVerdict.DEVICE_BOUND ->
            if (assessment.deviceCount > 0) {
                stringResource(R.string.sig_device_bound, assessment.deviceCount, date)
            } else {
                stringResource(R.string.sig_device_bound_unknown, date)
            }
        SignatureVerdict.STORE_SIGNED, SignatureVerdict.NOT_VERIFIED -> ""
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
    is ItemState.AlreadySent -> stringResource(R.string.state_already_sent, DateUtils.formatDateTime(LocalContext.current, state.sentAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR))
    ItemState.Cancelled -> stringResource(R.string.state_cancelled)
    ItemState.SignatureConflict -> stringResource(R.string.state_conflict)
    is ItemState.Failed -> stringResource(R.string.state_failed, state.reason)
    is ItemState.Uploading -> stringResource(R.string.state_uploading, state.percent)
    is ItemState.InstallingOnWatch ->
        state.percent?.let { stringResource(R.string.state_watch_installing_percent, it) } ?: stringResource(R.string.state_watch_installing)
    ItemState.InstalledOnWatch -> stringResource(R.string.state_done_watch)
    is ItemState.WatchRefused -> stringResource(R.string.state_watch_refused, refusal(state))
    is ItemState.WatchProblem -> stringResource(watchProblemText(state.error))
    ItemState.NoWifi -> stringResource(R.string.state_no_wifi)
    is ItemState.AlreadyOnWatch -> stringResource(R.string.state_already_on_watch, state.version)
}

@Composable
private fun refusal(state: ItemState.WatchRefused): String = listOfNotNull(state.message, state.code?.let { "$it" }).joinToString(" ")
    .ifEmpty { stringResource(R.string.state_watch_refused_unknown) }

private fun watchProblemText(error: SdbError): Int = when (error) {
    SdbError.UNREACHABLE -> R.string.watch_problem_unreachable
    SdbError.BUSY -> R.string.watch_problem_busy
    SdbError.AUTH_TIMEOUT -> R.string.watch_problem_auth_timeout
    SdbError.AUTH_REJECTED -> R.string.watch_problem_auth_rejected
    SdbError.TIMEOUT -> R.string.watch_problem_timeout
    SdbError.DISCONNECTED, SdbError.SERVICE_REFUSED -> R.string.watch_problem_disconnected
    SdbError.PROTOCOL -> R.string.watch_problem_protocol
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
            Text(stringResource(R.string.summary, state.installed, state.failed, state.cancelled, state.skipped), style = MaterialTheme.typography.bodyMedium)
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


@Composable
private fun WifiWatchTab(padding: PaddingValues, state: UiState, actions: MainActions, listState: LazyListState) {
    val context = LocalContext.current
    val ours = remember(state.remembered) { state.remembered.associateBy { it.id } }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .padding(padding)
            .padding(horizontal = 16.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SourceNote(stringResource(R.string.source_wifi, state.watchAddress.trim())) }
        item { Text(stringResource(R.string.watch_wifi_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            OutlinedButton(onClick = actions::refreshWatchList, enabled = !state.installing && state.watchList != WatchListState.Loading) {
                Text(stringResource(R.string.watch_wifi_refresh))
            }
        }
        if (state.watchAddressInvalid) {
            item { Text(stringResource(R.string.watch_address_invalid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        when (val list = state.watchList) {
            WatchListState.Idle -> Unit
            WatchListState.Loading -> item { Text(stringResource(R.string.watch_wifi_loading), style = MaterialTheme.typography.bodySmall) }
            WatchListState.NoWifi -> item { Text(stringResource(R.string.find_no_wifi), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            WatchListState.NoPermission ->
                item { Text(stringResource(R.string.find_no_permission), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            is WatchListState.Problem ->
                item { Text(stringResource(watchProblemText(list.error)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            is WatchListState.Loaded -> {
                item {
                    OutlinedTextField(
                        value = state.watchQuery,
                        onValueChange = actions::setWatchQuery,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.watch_wifi_search)) },
                    )
                }
                val needle = state.watchQuery.trim()
                val shown = list.packages
                    .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) || it.id.contains(needle, ignoreCase = true) }
                    .sortedWith(compareBy({ it.id !in ours }, { it.name.lowercase() }))
                item { Text(stringResource(R.string.watch_wifi_count, list.packages.size), style = MaterialTheme.typography.bodySmall) }
                if (shown.isEmpty()) item { Text(stringResource(R.string.watch_wifi_nothing)) }
                items(shown, key = { it.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOf(item.id, "v${item.version}", item.type.uppercase()).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val mine = ours[item.id]
                            if (mine != null) {
                                val date = DateUtils.formatDateTime(context, mine.installedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)
                                Text(stringResource(R.string.watch_wifi_ours, date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = { actions.askWatchRemoval(item) }, enabled = !state.installing) { Text(stringResource(R.string.watch_wifi_remove)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchRemovalDialog(removal: WatchRemoval, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val item = when (removal) {
        is WatchRemoval.Confirm -> removal.item
        is WatchRemoval.Working -> removal.item
        is WatchRemoval.Failed -> removal.item
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.watch_remove_title, item.name)) },
        text = {
            Text(
                when (removal) {
                    is WatchRemoval.Confirm -> stringResource(R.string.watch_remove_message)
                    is WatchRemoval.Working -> stringResource(R.string.watch_remove_working)
                    is WatchRemoval.Failed ->
                        stringResource(R.string.watch_remove_refused, removal.refusal ?: removal.error?.let { stringResource(watchProblemText(it)) }.orEmpty())
                },
            )
        },
        confirmButton = {
            if (removal is WatchRemoval.Confirm) TextButton(onClick = onConfirm) { Text(stringResource(R.string.watch_wifi_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = removal !is WatchRemoval.Working) {
                Text(stringResource(if (removal is WatchRemoval.Failed) R.string.ok else R.string.cancel))
            }
        },
    )
}


/** Says where the list of the "On the watch" tab comes from: the two ways of sending show different things under the same name. */
@Composable
private fun SourceNote(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

/** A header that follows the scroll offset of [state]: it slides up out of view and back; the offset limit is its own height. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollapsingHeader(state: TopAppBarState, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.clipToBounds()) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
        val full = placeables.sumOf { it.height }
        state.heightOffsetLimit = -full.toFloat()
        val offset = state.heightOffset.roundToInt().coerceIn(-full, 0)
        layout(constraints.maxWidth, full + offset) {
            var y = offset
            for (placeable in placeables) {
                placeable.placeRelative(0, y)
                y += placeable.height
            }
        }
    }
}

private const val CROSSFADE_MS = 200
