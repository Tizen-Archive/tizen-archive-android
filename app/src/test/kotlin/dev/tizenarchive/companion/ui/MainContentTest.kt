package dev.tizenarchive.companion.ui

import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import dev.tizenarchive.companion.signature.SignatureAssessment
import dev.tizenarchive.companion.signature.SignatureVerdict
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
}
