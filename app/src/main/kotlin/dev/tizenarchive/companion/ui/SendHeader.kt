package dev.tizenarchive.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.tizenarchive.companion.R
import dev.tizenarchive.companion.sdb.WatchAddress
import dev.tizenarchive.companion.wifi.InstallMode
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration

/** Whether the chosen way of sending has what it needs; drives the status line and whether the setup opens by itself. */
internal enum class Readiness { READY, NO_ADDRESS, BAD_ADDRESS, MISSING }

internal fun sendReadiness(state: UiState): Readiness {
    if (state.mode == InstallMode.WIFI) {
        val address = state.watchAddress.trim()
        return when {
            state.watchAddressInvalid || (address.isNotEmpty() && WatchAddress.parse(address) == null) -> Readiness.BAD_ADDRESS
            address.isEmpty() -> Readiness.NO_ADDRESS
            else -> Readiness.READY
        }
    }
    return if (state.missing.isEmpty()) Readiness.READY else Readiness.MISSING
}

/** Something the setup part explains: it is opened (and the header brought back) when one appears. */
internal fun UiState.hasSetupProblem(): Boolean = watchAddressInvalid || (mode == InstallMode.WIFI && scan.isProblem())

private fun ScanState.isProblem(): Boolean =
    this == ScanState.NoWifi || this == ScanState.NoPermission || this == ScanState.NoSubnet || (this is ScanState.Done && watches.isEmpty())

/**
 * The way packages are sent, above the tabs: a switch and one status line. The line opens the setup in a bottom sheet, which does not push the
 * lists down; the sheet also opens by itself on a problem and when switching to a way that is not ready yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SendHeader(state: UiState, actions: MainActions, modifier: Modifier = Modifier) {
    val readiness = sendReadiness(state)
    var showSetup by rememberSaveable { mutableStateOf(false) }
    // Set by the user's tap on the switch: the mode also changes when the saved one is loaded at the start, which must not open anything
    var switchedByUser by remember { mutableStateOf(false) }
    LaunchedEffect(state.hasSetupProblem()) { if (state.hasSetupProblem()) showSetup = true }
    LaunchedEffect(state.mode) {
        if (switchedByUser) {
            switchedByUser = false
            if (readiness != Readiness.READY) showSetup = true
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val caption = stringResource(R.string.send_caption)
    val switch: @Composable (Modifier) -> Unit = { m -> ModeSwitch(state, actions, onSwitched = { switchedByUser = true }, modifier = m) }
    val status: @Composable (Modifier) -> Unit = { m -> StatusLine(state, readiness, onClick = { showSetup = true }, modifier = m) }
    if (landscape) {
        // The screen is short: the switch and the status share one row and the caption is only read out
        Column(modifier = modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                switch(Modifier.weight(1f).semantics { contentDescription = caption })
                status(Modifier.weight(1f))
            }
            if (state.installing) LockedNote()
        }
    } else {
        Column(modifier = modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            switch(Modifier.padding(top = 4.dp))
            if (state.installing) LockedNote()
            status(Modifier)
        }
    }

    if (showSetup) SetupSheet(state, actions, onClose = { showSetup = false })
}

/** The setup behind the status line. It scrolls, keeps clear of the keyboard and has its own close button besides swiping and the back key. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupSheet(state: UiState, actions: MainActions, onClose: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val addressFocus = remember { FocusRequester() }
    LaunchedEffect(state.watchAddressInvalid) {
        if (state.watchAddressInvalid) {
            delay(FOCUS_DELAY_MS)
            runCatching { addressFocus.requestFocus() }
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Text(stringResource(R.string.setup_toggle), style = MaterialTheme.typography.titleMedium)
            if (state.mode == InstallMode.WIFI) {
                OutlinedTextField(
                    value = state.watchAddress,
                    onValueChange = actions::setWatchAddress,
                    modifier = Modifier.fillMaxWidth().focusRequester(addressFocus),
                    singleLine = true,
                    enabled = !state.installing,
                    isError = state.watchAddressInvalid,
                    label = { Text(stringResource(R.string.watch_address)) },
                    supportingText = {
                        Text(stringResource(if (state.watchAddressInvalid) R.string.watch_address_invalid else R.string.watch_address_help))
                    },
                )
                FindWatches(state, actions)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !state.installing) { actions.setReinstall(!state.reinstall) },
                ) {
                    Checkbox(checked = state.reinstall, onCheckedChange = { actions.setReinstall(it) }, enabled = !state.installing)
                    Text(stringResource(R.string.reinstall_option), style = MaterialTheme.typography.bodySmall)
                }
                // The long explanation comes last: what is asked for (the address) and what can be done come first
                Text(stringResource(R.string.mode_wifi_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (state.missing.isNotEmpty()) MissingCard(state.missing)
                Text(stringResource(R.string.mode_companion_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = { scope.launch { sheet.hide() }.invokeOnCompletion { onClose() } },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) { Text(stringResource(R.string.setup_done)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(state: UiState, actions: MainActions, onSwitched: () -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        val modes = listOf(
            InstallMode.COMPANION to (R.string.mode_companion to R.string.mode_companion_sub),
            InstallMode.WIFI to (R.string.mode_wifi to R.string.mode_wifi_sub),
        )
        modes.forEachIndexed { index, (mode, labels) ->
            SegmentedButton(
                selected = state.mode == mode,
                onClick = {
                    if (mode != state.mode) onSwitched()
                    actions.setMode(mode)
                },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                enabled = !state.installing,
                modifier = Modifier.heightIn(min = 56.dp),
                label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(labels.first), maxLines = 2, textAlign = TextAlign.Center)
                        Text(stringResource(labels.second), style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = TextAlign.Center)
                    }
                },
            )
        }
    }
}

@Composable
private fun LockedNote() {
    Text(stringResource(R.string.mode_locked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StatusLine(state: UiState, readiness: Readiness, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ready = readiness == Readiness.READY
    val status = when (readiness) {
        Readiness.READY ->
            if (state.mode == InstallMode.WIFI) stringResource(R.string.status_wifi_ready, state.watchAddress.trim()) else stringResource(R.string.status_wearable_ready)
        Readiness.NO_ADDRESS -> stringResource(R.string.status_wifi_no_address)
        Readiness.BAD_ADDRESS -> stringResource(R.string.status_wifi_bad_address)
        Readiness.MISSING -> stringResource(R.string.status_wearable_setup)
    }
    val toggleLabel = stringResource(R.string.setup_toggle)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$toggleLabel: $status" },
    ) {
        // A tick or a warning sign next to the text: the state is never carried by colour alone
        Icon(
            imageVector = if (ready) Glyphs.Check else Glyphs.Warning,
            contentDescription = null,
            tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Icon(imageVector = Glyphs.ChevronRight, contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun FindWatches(state: UiState, actions: MainActions) {
    val scanning = state.scan == ScanState.Scanning
    OutlinedButton(onClick = actions::findWatches, enabled = !state.installing && !scanning) { Text(stringResource(R.string.find_watch)) }
    when (val scan = state.scan) {
        ScanState.Idle -> Unit
        ScanState.Scanning -> Text(stringResource(R.string.finding_watch), style = MaterialTheme.typography.bodySmall)
        ScanState.NoWifi -> Text(stringResource(R.string.find_no_wifi), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        ScanState.NoPermission -> Text(stringResource(R.string.find_no_permission), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        ScanState.NoSubnet -> Text(stringResource(R.string.find_no_subnet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        is ScanState.Done -> when (scan.watches.size) {
            0 -> Text(stringResource(R.string.found_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            1 -> Text(stringResource(R.string.found_watch, scan.watches.single().address), style = MaterialTheme.typography.bodySmall)
            else -> {
                Text(stringResource(R.string.found_several), style = MaterialTheme.typography.bodySmall)
                for (watch in scan.watches) {
                    TextButton(onClick = { actions.setWatchAddress(watch.address) }, enabled = !state.installing) {
                        Text(if (watch.busy) stringResource(R.string.found_busy, watch.address) else watch.address)
                    }
                }
            }
        }
    }
}

private const val FOCUS_DELAY_MS = 150L
