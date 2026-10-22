package dev.tizenarchive.companion.ui

import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tizenarchive.companion.R
import dev.tizenarchive.companion.catalog.Artifact
import dev.tizenarchive.companion.catalog.VersionInfo
import dev.tizenarchive.companion.companions.InstalledCompanion
import dev.tizenarchive.companion.files.PackageFile
import dev.tizenarchive.companion.files.PackagePreview
import dev.tizenarchive.companion.sdb.SdbError
import dev.tizenarchive.companion.sdb.WatchPackage
import dev.tizenarchive.companion.signature.SignatureAssessment
import dev.tizenarchive.companion.signature.SignatureVerdict
import dev.tizenarchive.companion.wifi.InstallMode
import dev.tizenarchive.companion.wifi.RememberedPackage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private class RecordingActions : MainActions {
    val calls = mutableListOf<String>()

    override fun cancel() { calls += "cancel" }
    override fun checkUpdates() { calls += "checkUpdates" }
    override fun update(companion: InstalledCompanion) { calls += "update ${companion.tizenPackageId}" }
    override fun resend(companion: InstalledCompanion) { calls += "resend ${companion.tizenPackageId}" }
    override fun askRemoval(companion: InstalledCompanion) { calls += "askRemoval ${companion.tizenPackageId}" }
    override fun dismissIncomingNotice() { calls += "dismissIncomingNotice" }
    override fun setQuery(query: String) { calls += "setQuery $query" }
    override fun selectAllVisible() { calls += "selectAllVisible" }
    override fun clearSelection() { calls += "clearSelection" }
    override fun toggle(uri: Uri) { calls += "toggle ${uri.lastPathSegment}" }
    override fun confirmRemoval() { calls += "confirmRemoval" }
    override fun dismissRemoval() { calls += "dismissRemoval" }
    override fun confirmRemote() { calls += "confirmRemote" }
    override fun dismissRemote() { calls += "dismissRemote" }
    override fun dismissPermissionDialog() { calls += "dismissPermissionDialog" }
    override fun setMode(mode: InstallMode) { calls += "setMode $mode" }
    override fun setWatchAddress(address: String) { calls += "setWatchAddress $address" }
    override fun findWatches() { calls += "findWatches" }
    override fun setReinstall(reinstall: Boolean) { calls += "setReinstall $reinstall" }
    override fun refreshWatchList() { calls += "refreshWatchList" }
    override fun setWatchQuery(query: String) { calls += "setWatchQuery $query" }
    override fun askWatchRemoval(item: WatchPackage) { calls += "askWatchRemoval ${item.id}" }
    override fun confirmWatchRemoval() { calls += "confirmWatchRemoval" }
    override fun dismissWatchRemoval() { calls += "dismissWatchRemoval" }
}

/** A tall screen, so that lazy list rows are composed without scrolling. */
@Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class MainContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = RecordingActions()
    private var installTaps = 0
    private var pickTaps = 0

    private fun str(id: Int, vararg args: Any) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    private fun file(name: String) = PackageFile(Uri.parse("content://tree/$name"), name, 2048)

    private fun companion(id: String) = InstalledCompanion("dev.tizenarchive.companion.p$id", id, id, "1.0", "tpk", 1, 2, "")

    private fun toggleSetup() = compose.onNode(hasContentDescription(str(R.string.setup_toggle), substring = true)).performClick()

    private fun show(state: UiState) = compose.setContent {
        MainContent(state, actions, onPickFolder = { pickTaps++ }, onInstall = { installTaps++ })
    }

    @Test
    fun `lists files and toggles one on tap`() {
        show(UiState(files = listOf(file("alpha.tpk"), file("beta.wgt"))))
        compose.onNodeWithText("alpha.tpk").assertExists()
        compose.onNodeWithText("beta.wgt").performClick()
        assertEquals(listOf("toggle beta.wgt"), actions.calls)
    }

    @Test
    fun `install button is disabled without a selection and counts the selection`() {
        val a = file("a.tpk")
        show(UiState(files = listOf(a)))
        compose.onNodeWithText(str(R.string.install_selected, 0)).assertIsNotEnabled()
    }

    @Test
    fun `install button starts the install`() {
        val a = file("a.tpk")
        show(UiState(files = listOf(a), selected = setOf(a.uri)))
        compose.onNodeWithText(str(R.string.install_selected, 1)).assertIsEnabled().performClick()
        assertEquals(1, installTaps)
    }

    @Test
    fun `while installing the button is Cancel and the list is locked`() {
        val a = file("a.tpk")
        show(UiState(files = listOf(a), selected = setOf(a.uri), installing = true, states = mapOf(a.uri to ItemState.Building)))
        compose.onNodeWithText(str(R.string.state_building)).assertExists()
        compose.onNodeWithText(str(R.string.select_all)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.cancel)).performClick()
        assertEquals(listOf("cancel"), actions.calls)
    }

    @Test
    fun `waiting row shows the countdown`() {
        val a = file("a.tpk")
        show(UiState(files = listOf(a), installing = true, states = mapOf(a.uri to ItemState.Waiting(12))))
        compose.onNodeWithText(str(R.string.state_waiting, 12)).assertExists()
    }

    @Test
    fun `a package the watch will refuse carries a warning, a fine one does not`() {
        val bad = file("bad.tpk")
        val ok = file("ok.tpk")
        val previews = mapOf(
            bad.uri to PackagePreview("Bad", "com.bad", "1", "tpk", null, SignatureAssessment(SignatureVerdict.UNSIGNED)),
            ok.uri to PackagePreview("Good", "com.ok", "1", "tpk", null, SignatureAssessment(SignatureVerdict.STORE_SIGNED)),
        )
        show(UiState(files = listOf(bad, ok), previews = previews))
        compose.onNodeWithText(str(R.string.sig_unsigned)).assertExists()
        compose.onAllNodesWithText(str(R.string.sig_unsigned)).assertCountEquals(1)
    }

    @Test
    fun `a failure shows its reason`() {
        val a = file("a.tpk")
        show(UiState(files = listOf(a), states = mapOf(a.uri to ItemState.Failed("boom"))))
        compose.onNodeWithText(str(R.string.state_failed, "boom")).assertExists()
    }

    @Test
    fun `search field and select all report to the actions`() {
        show(UiState(files = listOf(file("a.tpk"))))
        compose.onNodeWithText(str(R.string.search)).performTextInput("x")
        compose.onNodeWithText(str(R.string.select_all)).performClick()
        compose.onNodeWithText(str(R.string.clear_selection)).performClick()
        assertEquals(listOf("setQuery x", "selectAllVisible", "clearSelection"), actions.calls)
    }

    @Test
    fun `missing prerequisites show a card`() {
        show(UiState(missing = setOf(Missing.GALAXY_WEARABLE)))
        toggleSetup()
        compose.onNodeWithText(str(R.string.missing_wearable)).assertExists()
    }

    @Test
    fun `the permission dialog can be dismissed`() {
        show(UiState(needsInstallPermission = true))
        compose.onNodeWithText(str(R.string.allow_unknown_title)).assertExists()
        compose.onNodeWithText(str(R.string.cancel)).performClick()
        assertEquals(listOf("dismissPermissionDialog"), actions.calls)
    }

    @Test
    fun `remote confirm dialog offers to install`() {
        val info = VersionInfo("com.a", "1.2", "Alpha", "tpk")
        show(UiState(remote = RemoteState.Confirm(info, Artifact("0".repeat(64), 1_500_000, "https://x/y"))))
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText(str(R.string.install_on_watch)).performClick()
        assertEquals(listOf("confirmRemote"), actions.calls)
    }

    @Test
    fun `watch tab lists companions and routes the buttons`() {
        val c = companion("com.a")
        show(UiState(companions = listOf(c)))
        compose.onNodeWithText(str(R.string.tab_watch, 1)).performClick()
        compose.onNodeWithText(str(R.string.resend_to_watch)).performClick()
        compose.onNodeWithText(str(R.string.check_updates)).performClick()
        compose.onNodeWithText(str(R.string.remove_from_watch)).performClick()
        assertEquals(listOf("resend com.a", "checkUpdates", "askRemoval com.a"), actions.calls)
    }

    @Test
    fun `removal confirmation dialog confirms`() {
        val c = companion("com.a")
        show(UiState(companions = listOf(c), removal = RemovalState.Confirm(c)))
        compose.onNodeWithText(str(R.string.remove_title, "com.a")).assertExists()
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h2400dp")
    fun `russian strings are used under the ru locale`() {
        show(UiState(files = listOf(file("a.tpk"))))
        compose.onNodeWithText(str(R.string.install_selected, 0)).assertExists()
        assert(str(R.string.install_selected, 0) != "Install selected (0)")
    }

    @Test
    fun `the switch of the way of sending changes the mode`() {
        show(UiState())
        compose.onNodeWithText(str(R.string.mode_wifi)).performClick()
        assertEquals(listOf("setMode WIFI"), actions.calls)
    }

    @Test
    fun `wifi mode shows the address field and hides the Galaxy Wearable hints`() {
        show(UiState(mode = InstallMode.WIFI, watchAddress = "192.168.0.20", missing = setOf(Missing.GALAXY_WEARABLE)))
        toggleSetup()
        compose.onNodeWithText(str(R.string.mode_wifi_hint)).assertExists()
        compose.onNodeWithText("192.168.0.20").assertExists()
        compose.onNodeWithText(str(R.string.watch_address_help)).assertExists()
        compose.onAllNodesWithText(str(R.string.mode_companion_hint)).assertCountEquals(0)
        compose.onAllNodesWithText(str(R.string.missing_wearable)).assertCountEquals(0)
    }

    @Test
    fun `typing in the address field reports the text`() {
        show(UiState(mode = InstallMode.WIFI))
        toggleSetup()
        compose.onNodeWithText(str(R.string.watch_address)).performTextInput("10.0.0.5")
        assertEquals(listOf("setWatchAddress 10.0.0.5"), actions.calls)
    }

    @Test
    fun `a wrong address is explained`() {
        show(UiState(mode = InstallMode.WIFI, watchAddress = "8.8.8.8", watchAddressInvalid = true))
        toggleSetup()
        compose.onNodeWithText(str(R.string.watch_address_invalid)).assertExists()
    }

    @Test
    fun `the mode cannot be switched while a queue runs`() {
        show(UiState(installing = true))
        compose.onNodeWithText(str(R.string.mode_wifi)).assertIsNotEnabled()
    }

    @Test
    fun `wifi rows tell what is happening and what went wrong`() {
        val files = listOf(file("a.wgt"), file("b.wgt"), file("c.wgt"), file("d.wgt"), file("e.wgt"), file("f.wgt"))
        val states = mapOf(
            files[0].uri to ItemState.Uploading(40),
            files[1].uri to ItemState.InstallingOnWatch(70),
            files[2].uri to ItemState.InstalledOnWatch,
            files[3].uri to ItemState.WatchRefused(-24, "Image error"),
            files[4].uri to ItemState.WatchProblem(SdbError.BUSY),
            files[5].uri to ItemState.NoWifi,
        )
        show(UiState(mode = InstallMode.WIFI, files = files, installing = true, states = states))
        compose.onNodeWithText(str(R.string.state_uploading, 40)).assertExists()
        compose.onNodeWithText(str(R.string.state_watch_installing_percent, 70)).assertExists()
        compose.onNodeWithText(str(R.string.state_done_watch)).assertExists()
        compose.onNodeWithText(str(R.string.state_watch_refused, "Image error -24")).assertExists()
        compose.onNodeWithText(str(R.string.watch_problem_busy)).assertExists()
        compose.onNodeWithText(str(R.string.state_no_wifi)).assertExists()
    }

    @Test
    fun `the search button asks for a search and is only offered in wifi mode`() {
        show(UiState())
        toggleSetup()
        compose.onAllNodesWithText(str(R.string.find_watch)).assertCountEquals(0)
    }

    @Test
    fun `the search button starts the search`() {
        show(UiState(mode = InstallMode.WIFI))
        toggleSetup()
        compose.onNodeWithText(str(R.string.find_watch)).assertIsEnabled().performClick()
        assertEquals(listOf("findWatches"), actions.calls)
    }

    @Test
    fun `the search button is off while searching and during a queue`() {
        show(UiState(mode = InstallMode.WIFI, scan = ScanState.Scanning))
        toggleSetup()
        compose.onNodeWithText(str(R.string.find_watch)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.finding_watch)).assertExists()
    }

    @Test
    fun `search results are explained`() {
        show(UiState(mode = InstallMode.WIFI, scan = ScanState.Done(listOf(FoundAddress("192.168.0.188", false)))))
        toggleSetup()
        compose.onNodeWithText(str(R.string.found_watch, "192.168.0.188")).assertExists()
    }

    @Test
    fun `several results are offered as buttons and a busy one is marked`() {
        show(UiState(mode = InstallMode.WIFI, scan = ScanState.Done(listOf(FoundAddress("192.168.0.9", true), FoundAddress("192.168.0.188", false)))))
        toggleSetup()
        compose.onNodeWithText(str(R.string.found_several)).assertExists()
        compose.onNodeWithText(str(R.string.found_busy, "192.168.0.9")).assertExists()
        compose.onNodeWithText("192.168.0.188").performClick()
        assertEquals(listOf("setWatchAddress 192.168.0.188"), actions.calls)
    }

    @Test
    fun `an empty search gives advice, other failures say what is wrong`() {
        show(UiState(mode = InstallMode.WIFI, scan = ScanState.Done(emptyList())))
        toggleSetup()
        compose.onNodeWithText(str(R.string.found_none)).assertExists()
    }

    @Test
    fun `a missing permission is explained`() {
        show(UiState(mode = InstallMode.WIFI, scan = ScanState.NoPermission))
        toggleSetup()
        compose.onNodeWithText(str(R.string.find_no_permission)).assertExists()
    }

    @Test
    fun `no Wi-Fi and an unsearchable network have their own messages`() {
        show(UiState(mode = InstallMode.WIFI, scan = ScanState.NoWifi))
        toggleSetup()
        compose.onNodeWithText(str(R.string.find_no_wifi)).assertExists()
    }

    private fun wp(id: String, name: String, version: String = "1.0") = WatchPackage(id, name, version, "wgt")

    @Test
    fun `the tab of the watch is named without a count in wifi mode and asks the list on opening`() {
        show(UiState(mode = InstallMode.WIFI))
        compose.onNodeWithText(str(R.string.tab_watch_wifi)).performClick()
        compose.onNodeWithText(str(R.string.watch_wifi_intro)).assertExists()
        compose.runOnIdle { assertEquals(listOf("refreshWatchList"), actions.calls) }
    }

    @Test
    fun `the watch list shows packages and offers removal only for those the app installed`() {
        val state = UiState(
            mode = InstallMode.WIFI,
            watchList = WatchListState.Loaded(listOf(wp("com.mine", "Mine", "2.0"), wp("com.samsung.system", "System app"))),
            remembered = listOf(RememberedPackage("com.mine", "2.0", "Mine", 1_700_000_000_000)),
        )
        show(state)
        compose.onNodeWithText(str(R.string.tab_watch_wifi)).performClick()
        compose.onNodeWithText("Mine").assertExists()
        compose.onNodeWithText("System app").assertExists()
        compose.onNodeWithText(str(R.string.watch_wifi_count, 2)).assertExists()
        compose.onAllNodesWithText(str(R.string.watch_wifi_remove)).assertCountEquals(1)
        compose.onNodeWithText(str(R.string.watch_wifi_remove)).performClick()
        assertEquals(listOf("refreshWatchList", "askWatchRemoval com.mine").takeLast(1), actions.calls.takeLast(1))
    }

    @Test
    fun `the watch list can be searched`() {
        show(UiState(mode = InstallMode.WIFI, watchQuery = "light", watchList = WatchListState.Loaded(listOf(wp("a", "White Light"), wp("b", "Clock")))))
        compose.onNodeWithText(str(R.string.tab_watch_wifi)).performClick()
        compose.onNodeWithText("White Light").assertExists()
        compose.onAllNodesWithText("Clock").assertCountEquals(0)
    }

    @Test
    fun `a failing list explains itself`() {
        show(UiState(mode = InstallMode.WIFI, watchList = WatchListState.Problem(SdbError.BUSY)))
        compose.onNodeWithText(str(R.string.tab_watch_wifi)).performClick()
        compose.onNodeWithText(str(R.string.watch_problem_busy)).assertExists()
    }

    @Test
    fun `the removal dialog asks, works and reports`() {
        val item = wp("com.mine", "Mine")
        show(UiState(mode = InstallMode.WIFI, watchRemoval = WatchRemoval.Confirm(item)))
        compose.onNodeWithText(str(R.string.watch_remove_title, "Mine")).assertExists()
        compose.onNodeWithText(str(R.string.watch_remove_message)).assertExists()
        compose.onNodeWithText(str(R.string.watch_wifi_remove)).performClick()
        assertEquals(listOf("confirmWatchRemoval"), actions.calls)
    }

    @Test
    fun `a removal that failed shows the reason from the watch`() {
        show(UiState(mode = InstallMode.WIFI, watchRemoval = WatchRemoval.Failed(wp("com.mine", "Mine"), "Undefined Error -999", null)))
        compose.onNodeWithText(str(R.string.watch_remove_refused, "Undefined Error -999")).assertExists()
    }

    @Test
    fun `the install-again option is a checkbox and an already installed row explains the skip`() {
        val a = file("a.wgt")
        show(UiState(mode = InstallMode.WIFI, files = listOf(a), installing = false, states = mapOf(a.uri to ItemState.AlreadyOnWatch("1.0"))))
        toggleSetup()
        compose.onNodeWithText(str(R.string.state_already_on_watch, "1.0")).assertExists()
        compose.onNodeWithText(str(R.string.reinstall_option)).performClick()
        assertEquals(listOf("setReinstall true"), actions.calls)
    }

    // ---- The way of sending: switch, status line and setup ------------------------------------------------

    @Test
    fun `a ready way of sending shows its status and opens the setup on tap`() {
        show(UiState(mode = InstallMode.WIFI, watchAddress = "192.168.0.20"))
        compose.onNodeWithText(str(R.string.status_wifi_ready, "192.168.0.20")).assertExists()
        compose.onAllNodesWithText(str(R.string.mode_wifi_hint)).assertCountEquals(0)
        toggleSetup()
        compose.onNodeWithText(str(R.string.mode_wifi_hint)).assertExists()
    }

    @Test
    fun `a way of sending that needs setup says what is missing and waits to be opened`() {
        show(UiState(mode = InstallMode.WIFI))
        compose.onNodeWithText(str(R.string.status_wifi_no_address)).assertExists()
        compose.onAllNodesWithText(str(R.string.mode_wifi_hint)).assertCountEquals(0)
        toggleSetup()
        compose.onNodeWithText(str(R.string.mode_wifi_hint)).assertExists()
    }

    @Test
    fun `an address that was refused opens the setup by itself, with the error at the field`() {
        show(UiState(mode = InstallMode.WIFI, watchAddress = "8.8.8.8", watchAddressInvalid = true))
        compose.onNodeWithText(str(R.string.status_wifi_bad_address)).assertExists()
        compose.onNodeWithText(str(R.string.mode_wifi_hint)).assertExists()
        compose.onNodeWithText(str(R.string.watch_address_invalid)).assertExists()
    }

    @Test
    fun `a search that found nothing opens the setup with the advice`() {
        show(UiState(mode = InstallMode.WIFI, watchAddress = "192.168.0.20", scan = ScanState.Done(emptyList())))
        compose.onNodeWithText(str(R.string.found_none)).assertExists()
    }

    @Test
    fun `switching to a way that is not ready opens the setup, to a ready one does not`() {
        val ui = androidx.compose.runtime.mutableStateOf(UiState())
        compose.setContent { MainContent(ui.value, actions, onPickFolder = {}, onInstall = {}) }
        compose.onAllNodesWithText(str(R.string.mode_wifi_hint)).assertCountEquals(0)
        // The user taps the switch, then the view model reports the new mode
        compose.onNodeWithText(str(R.string.mode_wifi)).performClick()
        ui.value = UiState(mode = InstallMode.WIFI)
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.mode_wifi_hint)).assertExists()
    }

    @Test
    fun `a mode that changes without a tap, as when the saved one is loaded, opens nothing`() {
        val ui = androidx.compose.runtime.mutableStateOf(UiState())
        compose.setContent { MainContent(ui.value, actions, onPickFolder = {}, onInstall = {}) }
        ui.value = UiState(mode = InstallMode.WIFI)
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.status_wifi_no_address)).assertExists()
        compose.onAllNodesWithText(str(R.string.mode_wifi_hint)).assertCountEquals(0)
    }

    @Test
    fun `switching back to Galaxy Wearable with something missing opens its setup`() {
        val ui = androidx.compose.runtime.mutableStateOf(UiState(mode = InstallMode.WIFI, watchAddress = "192.168.0.20", missing = setOf(Missing.GALAXY_WEARABLE)))
        compose.setContent { MainContent(ui.value, actions, onPickFolder = {}, onInstall = {}) }
        compose.onNodeWithText(str(R.string.mode_companion)).performClick()
        ui.value = UiState(mode = InstallMode.COMPANION, missing = setOf(Missing.GALAXY_WEARABLE))
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.missing_wearable)).assertExists()
    }

    @Test
    fun `the Done button closes the setup`() {
        show(UiState(mode = InstallMode.WIFI, watchAddress = "192.168.0.20"))
        toggleSetup()
        compose.onNodeWithText(str(R.string.setup_done)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText(str(R.string.mode_wifi_hint)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `Galaxy Wearable is ready when nothing is missing`() {
        show(UiState())
        compose.onNodeWithText(str(R.string.status_wearable_ready)).assertExists()
        compose.onAllNodesWithText(str(R.string.mode_companion_hint)).assertCountEquals(0)
    }

    @Test
    fun `missing Galaxy Wearable is explained in the setup`() {
        show(UiState(missing = setOf(Missing.GALAXY_WEARABLE)))
        compose.onNodeWithText(str(R.string.status_wearable_setup)).assertExists()
        toggleSetup()
        compose.onNodeWithText(str(R.string.missing_wearable)).assertExists()
    }

    @Test
    fun `the status row carries a name for screen readers and acts as a button`() {
        show(UiState())
        compose.onNode(hasContentDescription(str(R.string.setup_toggle), substring = true)).assertExists().assertHasClickAction()
    }

    @Test
    fun `a locked switch says why`() {
        show(UiState(installing = true))
        compose.onNodeWithText(str(R.string.mode_locked)).assertExists()
    }

    @Test
    fun `each way of sending shows where its list comes from`() {
        show(UiState(mode = InstallMode.WIFI, watchAddress = "192.168.0.20"))
        compose.onNodeWithText(str(R.string.tab_watch_wifi)).performClick()
        compose.onNodeWithText(str(R.string.source_wifi, "192.168.0.20")).assertExists()
    }

    @Test
    fun `the source of the companion list is named too`() {
        show(UiState(companions = listOf(companion("a"))))
        compose.onNodeWithText(str(R.string.tab_watch, 1)).performClick()
        compose.onNodeWithText(str(R.string.source_companion)).assertExists()
    }

    @Test
    fun `a link install names the way it will use`() {
        val info = VersionInfo(packageId = "Dice", version = "1.0.2", name = "Dice", type = "wgt")
        val artifact = Artifact("a".repeat(64), 2048, "/x")
        show(UiState(mode = InstallMode.WIFI, watchAddress = "192.168.0.20", remote = RemoteState.Confirm(info, artifact)))
        compose.onNodeWithText(str(R.string.remote_route_wifi, "192.168.0.20"), substring = true).assertExists()
    }

    @Test
    fun `readiness is decided from the mode, the address and what is missing`() {
        assertEquals(Readiness.READY, sendReadiness(UiState()))
        assertEquals(Readiness.MISSING, sendReadiness(UiState(missing = setOf(Missing.GEAR_PLUGIN))))
        assertEquals(Readiness.NO_ADDRESS, sendReadiness(UiState(mode = InstallMode.WIFI)))
        assertEquals(Readiness.NO_ADDRESS, sendReadiness(UiState(mode = InstallMode.WIFI, watchAddress = "  ")))
        assertEquals(Readiness.BAD_ADDRESS, sendReadiness(UiState(mode = InstallMode.WIFI, watchAddress = "8.8.8.8")))
        assertEquals(Readiness.BAD_ADDRESS, sendReadiness(UiState(mode = InstallMode.WIFI, watchAddress = "192.168.0.20", watchAddressInvalid = true)))
        assertEquals(Readiness.READY, sendReadiness(UiState(mode = InstallMode.WIFI, watchAddress = " 192.168.0.20 ")))
        // Missing Galaxy Wearable does not matter in Wi-Fi mode
        assertEquals(Readiness.READY, sendReadiness(UiState(mode = InstallMode.WIFI, watchAddress = "10.0.0.1", missing = setOf(Missing.GALAXY_WEARABLE))))
    }
}
