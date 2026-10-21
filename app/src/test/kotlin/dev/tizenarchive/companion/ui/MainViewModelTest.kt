package dev.tizenarchive.companion.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tizenarchive.companion.Dependencies
import dev.tizenarchive.companion.FakeCatalog
import dev.tizenarchive.companion.FakeCompanions
import dev.tizenarchive.companion.FakeFactory
import dev.tizenarchive.companion.FakeFolderSource
import dev.tizenarchive.companion.FakeFolders
import dev.tizenarchive.companion.FakeInstaller
import dev.tizenarchive.companion.FakeNotifier
import dev.tizenarchive.companion.FakePlatform
import dev.tizenarchive.companion.FakePreviews
import dev.tizenarchive.companion.apk.CompanionApk
import dev.tizenarchive.companion.build.DeliveryPacing
import dev.tizenarchive.companion.catalog.Artifact
import dev.tizenarchive.companion.catalog.Build
import dev.tizenarchive.companion.catalog.Verdict
import dev.tizenarchive.companion.catalog.VersionInfo
import dev.tizenarchive.companion.companions.InstalledCompanion
import dev.tizenarchive.companion.files.PackageFile
import dev.tizenarchive.companion.install.InstallOutcome
import dev.tizenarchive.companion.tizenPackage
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MainViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val tree = Uri.parse("content://tree/packages")

    private lateinit var platform: FakePlatform
    private lateinit var installer: FakeInstaller
    private lateinit var companions: FakeCompanions
    private lateinit var source: FakeFolderSource
    private lateinit var notifier: FakeNotifier
    private lateinit var catalog: FakeCatalog

    @Before
    fun setUp() {
        Dispatchers_setMain()
        val clock = { dispatcher.scheduler.currentTime + 1_000_000 }
        platform = FakePlatform(tmp.newFolder("cache"), clock)
        installer = FakeInstaller(clock)
        companions = FakeCompanions()
        source = FakeFolderSource()
        notifier = FakeNotifier()
        catalog = FakeCatalog()
    }

    @After
    fun tearDown() = kotlinx.coroutines.Dispatchers.resetMain()

    private fun Dispatchers_setMain() = kotlinx.coroutines.Dispatchers.setMain(dispatcher)

    private fun viewModel(folder: Uri? = tree) = MainViewModel(
        Dependencies(
            platform = platform,
            folders = FakeFolders(folder),
            folderSource = source,
            installer = installer,
            companions = companions,
            catalog = catalog,
            factory = FakeFactory(),
            previews = FakePreviews(),
            notifier = notifier,
            io = dispatcher,
        ),
    )

    private fun addFile(name: String, id: String, version: String, padding: Int = 0): PackageFile {
        val bytes = tizenPackage(id, version, padding)
        val uri = Uri.parse("content://tree/packages/$name")
        platform.contents[uri] = bytes
        return PackageFile(uri, name, bytes.size.toLong()).also { source.files = source.files + it }
    }

    private fun MainViewModel.selectAll() {
        selectAllVisible()
        installSelected()
    }

    @Test
    fun `shows the remembered folder and its packages`() {
        addFile("a.tpk", "com.a", "1.0")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Packages", vm.state.value.folderName)
        assertEquals(listOf("a.tpk"), vm.state.value.files.map { it.name })
    }

    @Test
    fun `second delivery waits for the plugin pacing`() {
        addFile("a.tpk", "com.a", "1.0")
        val b = addFile("b.tpk", "com.b", "1.0", padding = 2000)
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, installer.installs.size)
        assertEquals(2, vm.state.value.installed)
        val first = installer.installs[0]
        val second = installer.installs[1]
        val sizeOfFirst = platform.contents.values.first().size.toLong()
        assertTrue(
            "waited ${second.at - first.at} ms",
            second.at - first.at >= DeliveryPacing.delayAfter(sizeOfFirst).toMillis(),
        )
        assertTrue(b.uri in vm.state.value.states)
    }

    @Test
    fun `pacing also holds between two separate operations`() {
        addFile("a.tpk", "com.a", "1.0")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()

        // Another operation started right away: a link from the website
        catalog.versions["com.b"] = remoteVersion("com.b")
        catalog.payload = tizenPackage("com.b", "2.0")
        vm.onInstallLink("tizen-archive://install?package=com.b")
        dispatcher.scheduler.advanceUntilIdle()
        vm.confirmRemote()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, installer.installs.size)
        assertTrue(installer.installs[1].at - installer.installs[0].at >= DeliveryPacing.delayAfter(platform.contents.values.first().size.toLong()).toMillis())
    }

    @Test
    fun `a version that was already sent is skipped`() {
        addFile("a.tpk", "com.a", "1.0")
        companions.list = listOf(companion("com.a", "1.0"))
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, installer.installs.size)
        assertEquals(1, vm.state.value.skipped)
    }

    @Test
    fun `a new version of a sent package is delivered`() {
        addFile("a.tpk", "com.a", "2.0")
        companions.list = listOf(companion("com.a", "1.0"))
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, installer.installs.size)
        assertEquals(1, vm.state.value.installed)
    }

    @Test
    fun `a signature conflict is reported separately`() {
        val file = addFile("a.tpk", "com.a", "1.0")
        installer.outcomes += InstallOutcome.Failed(android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT, "conflict")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ItemState.SignatureConflict, vm.state.value.states[file.uri])
        assertEquals(1, vm.state.value.failed)
    }

    @Test
    fun `declining the system dialog counts as cancelled`() {
        val file = addFile("a.tpk", "com.a", "1.0")
        installer.outcomes += InstallOutcome.Declined
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ItemState.Cancelled, vm.state.value.states[file.uri])
    }

    @Test
    fun `a broken package fails only its own row`() {
        val bad = PackageFile(Uri.parse("content://tree/packages/bad.wgt"), "bad.wgt", 3)
        platform.contents[bad.uri] = byteArrayOf(1, 2, 3)
        source.files = listOf(bad)
        val good = addFile("good.tpk", "com.good", "1.0")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.states[bad.uri] is ItemState.Failed)
        assertEquals(ItemState.Installed, vm.state.value.states[good.uri])
    }

    @Test
    fun `cancelling stops the queue and marks the rest`() {
        addFile("a.tpk", "com.a", "1.0")
        addFile("b.tpk", "com.b", "1.0")
        installer.gate = kotlinx.coroutines.CompletableDeferred()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        dispatcher.scheduler.runCurrent()
        vm.cancel()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(!vm.state.value.installing)
        assertEquals(1, installer.installs.size)
        assertTrue(vm.state.value.cancelled >= 1)
    }

    @Test
    fun `a long queue ends with one notification`() {
        addFile("a.tpk", "com.a", "1.0")
        addFile("b.tpk", "com.b", "1.0")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(FakeNotifier.Done(sent = 2, skipped = 0, failed = 0, cancelled = 0)), notifier.done)
    }

    @Test
    fun `a single file ends without a notification`() {
        addFile("a.tpk", "com.a", "1.0")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(notifier.done.isEmpty())
    }

    @Test
    fun `without the install permission nothing starts`() {
        addFile("a.tpk", "com.a", "1.0")
        platform.canInstall = false
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.needsInstallPermission)
        assertTrue(installer.installs.isEmpty())
        assertTrue(Missing.INSTALL_PERMISSION in vm.state.value.missing)
    }

    @Test
    fun `missing Galaxy Wearable is reported`() {
        platform.installedPackages.clear()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(setOf(Missing.GALAXY_WEARABLE, Missing.GEAR_PLUGIN), vm.state.value.missing)
    }

    @Test
    fun `incoming files are accepted when they are Tizen packages and rejected otherwise`() {
        val ok = Uri.parse("content://other/ok")
        val bad = Uri.parse("content://other/bad")
        platform.contents[ok] = tizenPackage("com.in", "1.0")
        platform.contents[bad] = byteArrayOf(9)
        platform.names[ok] = "ok.tpk"
        val vm = viewModel(folder = null)
        dispatcher.scheduler.advanceUntilIdle()
        vm.onIncoming(listOf(ok, bad))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.state.value.incoming.size)
        assertEquals(1, vm.state.value.incomingRejected)
        assertEquals(vm.state.value.incoming.map { it.uri }.toSet(), vm.state.value.selected)
    }

    @Test
    fun `sent incoming files are forgotten`() {
        val ok = Uri.parse("content://other/ok")
        platform.contents[ok] = tizenPackage("com.in", "1.0")
        platform.names[ok] = "ok.tpk"
        val vm = viewModel(folder = null)
        dispatcher.scheduler.advanceUntilIdle()
        vm.onIncoming(listOf(ok))
        dispatcher.scheduler.advanceUntilIdle()
        vm.installSelected()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.incoming.isEmpty())
        assertEquals(1, installer.installs.size)
    }

    @Test
    fun `a link for an unknown package shows a catalog problem`() {
        val vm = viewModel(folder = null)
        dispatcher.scheduler.advanceUntilIdle()
        vm.onInstallLink("tizen-archive://install?package=com.nope")
        dispatcher.scheduler.advanceUntilIdle()

        val problem = (vm.state.value.remote as RemoteState.Problem).problem
        assertTrue(problem is RemoteProblem.Catalog)
    }

    @Test
    fun `a garbage link is invalid`() {
        val vm = viewModel(folder = null)
        dispatcher.scheduler.advanceUntilIdle()
        vm.onInstallLink("https://evil.example/")
        assertEquals(RemoteState.Problem(RemoteProblem.InvalidLink), vm.state.value.remote)
    }

    @Test
    fun `removal asks first and refreshes the list`() {
        val c = companion("com.a", "1.0")
        companions.list = listOf(c)
        val vm = viewModel(folder = null)
        dispatcher.scheduler.advanceUntilIdle()
        vm.askRemoval(c)
        assertEquals(RemovalState.Confirm(c), vm.state.value.removal)
        companions.list = emptyList()
        vm.confirmRemoval()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(c.androidPackage), installer.removed)
        assertEquals(RemovalState.Done(c), vm.state.value.removal)
        assertTrue(vm.state.value.companions.isEmpty())
    }

    @Test
    fun `declining the removal dialog closes it quietly`() {
        val c = companion("com.a", "1.0")
        companions.list = listOf(c)
        installer.uninstallOutcome = dev.tizenarchive.companion.install.UninstallOutcome.Declined
        val vm = viewModel(folder = null)
        dispatcher.scheduler.advanceUntilIdle()
        vm.askRemoval(c)
        vm.confirmRemoval()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, vm.state.value.removal)
    }

    @Test
    fun `resend takes the package out of the companion`() {
        val apk = File(tmp.newFolder("sent"), "base.apk")
        writeCompanionApk(apk, tizenPackage("com.a", "1.0"))
        val c = companion("com.a", "1.0").copy(sourceDir = apk.path)
        companions.list = listOf(c)
        val vm = viewModel(folder = null)
        dispatcher.scheduler.advanceUntilIdle()
        vm.resend(c)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, installer.installs.size)
        assertEquals(ItemState.Installed, vm.state.value.states[companionKey(c.androidPackage)])
    }

    @Test
    fun `search matches file names`() {
        addFile("alpha.tpk", "com.a", "1.0")
        addFile("beta.tpk", "com.b", "1.0")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.setQuery("alp")
        assertEquals(listOf("alpha.tpk"), vm.state.value.visibleFiles.map { it.name })
    }

    private fun remoteVersion(id: String) = VersionInfo(
        packageId = id,
        version = "2.0",
        name = id,
        type = "tpk",
        builds = listOf(Build(Verdict.INSTALLABLE, listOf(Artifact("0".repeat(64), 100, "https://example.org/$id")))),
    )

    private fun writeCompanionApk(target: File, pkg: ByteArray) {
        java.util.zip.ZipOutputStream(target.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry(CompanionApk.assetName("tpk")))
            z.write(pkg)
            z.closeEntry()
        }
    }

    private fun companion(id: String, version: String) = InstalledCompanion(
        androidPackage = "dev.tizenarchive.companion.p$id",
        label = id,
        tizenPackageId = id,
        tizenVersion = version,
        kind = "tpk",
        firstInstalledAt = 1,
        updatedAt = 42,
        sourceDir = "",
    )
}
