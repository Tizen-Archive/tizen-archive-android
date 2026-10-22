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
import dev.tizenarchive.companion.FakeWatch
import dev.tizenarchive.companion.FakeWatchHistory
import dev.tizenarchive.companion.FakeWatchSettings
import dev.tizenarchive.companion.apk.CompanionApk
import dev.tizenarchive.companion.build.DeliveryPacing
import dev.tizenarchive.companion.catalog.Artifact
import dev.tizenarchive.companion.catalog.Build
import dev.tizenarchive.companion.catalog.Verdict
import dev.tizenarchive.companion.catalog.VersionInfo
import dev.tizenarchive.companion.companions.InstalledCompanion
import dev.tizenarchive.companion.files.PackageFile
import dev.tizenarchive.companion.install.InstallOutcome
import dev.tizenarchive.companion.sdb.FoundWatch
import dev.tizenarchive.companion.sdb.InstallProgress
import dev.tizenarchive.companion.sdb.PackageType
import dev.tizenarchive.companion.sdb.SdbError
import dev.tizenarchive.companion.sdb.WatchPackage
import dev.tizenarchive.companion.tizenPackage
import dev.tizenarchive.companion.wifi.FindOutcome
import dev.tizenarchive.companion.wifi.InstallMode
import dev.tizenarchive.companion.wifi.RememberedPackage
import dev.tizenarchive.companion.wifi.WatchListOutcome
import dev.tizenarchive.companion.wifi.WatchRemoveOutcome
import dev.tizenarchive.companion.wifi.WatchOutcome
import dev.tizenarchive.companion.wifi.WatchSettings
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
    private lateinit var progress: dev.tizenarchive.companion.FakeProgress
    private lateinit var catalog: FakeCatalog
    private lateinit var watch: FakeWatch
    private lateinit var settings: FakeWatchSettings
    private lateinit var history: FakeWatchHistory

    @Before
    fun setUp() {
        Dispatchers_setMain()
        val clock = { dispatcher.scheduler.currentTime + 1_000_000 }
        platform = FakePlatform(tmp.newFolder("cache"), clock)
        installer = FakeInstaller(clock)
        companions = FakeCompanions()
        source = FakeFolderSource()
        notifier = FakeNotifier()
        progress = dev.tizenarchive.companion.FakeProgress()
        catalog = FakeCatalog()
        watch = FakeWatch()
        settings = FakeWatchSettings()
        history = FakeWatchHistory()
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
            progress = progress,
            watch = watch,
            watchSettings = settings,
            watchHistory = history,
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
    fun `the foreground service follows the queue and stops at its end`() {
        addFile("a.tpk", "com.a", "1.0")
        addFile("b.tpk", "com.b", "1.0")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()

        val updates = progress.updates
        assertEquals(setOf(1, 2), updates.map { it.index }.toSet())
        assertTrue(updates.all { it.total == 2 })
        assertTrue(updates.any { it.index == 2 && it.state is ItemState.Waiting })
        assertTrue(updates.any { it.state == ItemState.AwaitingConfirm })
        assertTrue(updates.none { it.state is ItemState.Installed })
        assertEquals(1, progress.stops)
    }

    @Test
    fun `cancelling from the notification cancels the queue`() {
        addFile("a.tpk", "com.a", "1.0")
        installer.gate = kotlinx.coroutines.CompletableDeferred()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.selectAll()
        dispatcher.scheduler.runCurrent()
        progress.onCancel!!.invoke()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(!vm.state.value.installing)
        assertEquals(1, progress.stops)
    }

    @Test
    fun `a resend names the companion in the notification`() {
        val apk = File(tmp.newFolder("sent2"), "base.apk")
        writeCompanionApk(apk, tizenPackage("com.a", "1.0"))
        val c = companion("com.a", "1.0").copy(sourceDir = apk.path, label = "Alpha")
        companions.list = listOf(c)
        val vm = viewModel(folder = null)
        dispatcher.scheduler.advanceUntilIdle()
        vm.resend(c)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(progress.updates.all { it.name == "Alpha" && it.total == 1 })
        assertEquals(1, progress.stops)
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
    fun `a file received while the app is still starting is kept`() {
        val ok = Uri.parse("content://other/ok")
        platform.contents[ok] = tizenPackage("com.in", "1.0")
        platform.names[ok] = "ok.tpk"
        val vm = viewModel(folder = null)
        // Opened from another app on a cold start: nothing has run yet, not even the cache cleanup
        vm.onIncoming(listOf(ok))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.state.value.incoming.size)
        assertTrue(java.io.File(vm.state.value.incoming.single().uri.path!!).exists())
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

    // ---- Wi-Fi mode ---------------------------------------------------------------------------------------

    private fun MainViewModel.wifi(address: String = "192.168.0.188") {
        setMode(InstallMode.WIFI)
        setWatchAddress(address)
    }

    @Test
    fun `wifi mode installs on the watch without the companion path`() {
        platform.canInstall = false
        val a = addFile("a.wgt", "com.a", "1.0")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ItemState.InstalledOnWatch, vm.state.value.states[a.uri])
        assertEquals(1, vm.state.value.installed)
        assertEquals(1, watch.calls.size)
        assertEquals("192.168.0.188", watch.calls[0].address)
        assertTrue(installer.installs.isEmpty())
        assertTrue(!vm.state.value.needsInstallPermission)
    }

    @Test
    fun `the type of the package is taken from its content`() {
        addFile("a.tpk", "com.a", "1.0")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(PackageType.TPK, watch.calls.single().type)
    }

    @Test
    fun `progress goes from sending to installing`() {
        addFile("a.tpk", "com.a", "1.0")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()
        val seen = progress.updates.map { it.state }
        assertTrue(seen.contains(ItemState.Uploading(50)))
        assertTrue(seen.contains(ItemState.Uploading(100)))
        assertTrue(seen.contains(ItemState.InstallingOnWatch(null)))
        assertTrue(seen.contains(ItemState.InstallingOnWatch(60)))
        assertTrue("the final state is not a notification", seen.none { it == ItemState.InstalledOnWatch })
    }

    @Test
    fun `an address outside the private networks is refused before anything is sent`() {
        addFile("a.tpk", "com.a", "1.0")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        for (bad in listOf("", "8.8.8.8", "localhost", "192.168.0")) {
            vm.wifi(bad)
            vm.selectAll()
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue("'$bad'", vm.state.value.watchAddressInvalid)
        }
        assertTrue(watch.calls.isEmpty())
        assertTrue(!vm.state.value.installing)
        vm.setWatchAddress("192.168.0.9")
        assertTrue(!vm.state.value.watchAddressInvalid)
    }

    @Test
    fun `spaces around the address are ignored`() {
        addFile("a.tpk", "com.a", "1.0")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi("  192.168.0.9 ")
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("192.168.0.9", watch.calls.single().address)
    }

    @Test
    fun `a refusal and the connection problems are shown per package`() {
        val a = addFile("a.wgt", "com.a", "1.0")
        val b = addFile("b.wgt", "com.b", "1.0")
        val c = addFile("c.wgt", "com.c", "1.0")
        val d = addFile("d.wgt", "com.d", "1.0")
        watch.outcomes += WatchOutcome.Refused(-24, "Image error")
        watch.outcomes += WatchOutcome.Problem(SdbError.BUSY)
        watch.outcomes += WatchOutcome.NoWifi
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()

        val states = vm.state.value.states
        assertEquals(ItemState.WatchRefused(-24, "Image error"), states[a.uri])
        assertEquals(ItemState.WatchProblem(SdbError.BUSY), states[b.uri])
        assertEquals(ItemState.NoWifi, states[c.uri])
        assertEquals(ItemState.InstalledOnWatch, states[d.uri])
        assertEquals(3, vm.state.value.failed)
        assertEquals(1, vm.state.value.installed)
    }

    @Test
    fun `a package that cannot be read fails only its own row in wifi mode`() {
        val broken = addFile("a.wgt", "com.a", "1.0")
        platform.contents[broken.uri] = byteArrayOf(1, 2, 3)
        val ok = addFile("b.wgt", "com.b", "1.0")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.states[broken.uri] is ItemState.Failed)
        assertEquals(ItemState.InstalledOnWatch, vm.state.value.states[ok.uri])
    }

    @Test
    fun `cancelling a running wifi install cancels the row`() {
        val a = addFile("a.wgt", "com.a", "1.0")
        watch.gate = kotlinx.coroutines.CompletableDeferred()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi()
        vm.selectAll()
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.installing)
        vm.cancel()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ItemState.Cancelled, vm.state.value.states[a.uri])
        assertTrue(!vm.state.value.installing)
    }

    @Test
    fun `the mode and the address are remembered and restored`() {
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi("192.168.1.20")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(InstallMode.WIFI, settings.saved.mode)
        assertEquals("192.168.1.20", settings.saved.address)

        val again = MainViewModel(
            Dependencies(
                platform = platform, folders = FakeFolders(null), folderSource = source, installer = installer, companions = companions,
                catalog = catalog, factory = FakeFactory(), previews = FakePreviews(), notifier = notifier, progress = progress,
                watch = watch, watchSettings = FakeWatchSettings(WatchSettings(InstallMode.WIFI, "10.0.0.5")), watchHistory = FakeWatchHistory(), io = dispatcher,
            ),
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(InstallMode.WIFI, again.state.value.mode)
        assertEquals("10.0.0.5", again.state.value.watchAddress)
    }

    @Test
    fun `the mode cannot be changed while a queue runs`() {
        addFile("a.wgt", "com.a", "1.0")
        watch.gate = kotlinx.coroutines.CompletableDeferred()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi()
        vm.selectAll()
        dispatcher.scheduler.runCurrent()
        vm.setMode(InstallMode.COMPANION)
        assertEquals(InstallMode.WIFI, vm.state.value.mode)
        watch.gate!!.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
    }

    // ---- Finding the watch --------------------------------------------------------------------------------

    private fun found(vararg addresses: Pair<String, Boolean>) =
        FindOutcome.Found(addresses.map { FoundWatch(java.net.InetAddress.getByName(it.first), it.second) })

    @Test
    fun `one watch found fills the address and saves it`() {
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.setMode(InstallMode.WIFI)
        watch.found = found("192.168.0.188" to false)
        vm.findWatches()
        assertEquals(ScanState.Scanning, vm.state.value.scan)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ScanState.Done(listOf(FoundAddress("192.168.0.188", false))), vm.state.value.scan)
        assertEquals("192.168.0.188", vm.state.value.watchAddress)
        assertEquals("192.168.0.188", settings.saved.address)
    }

    @Test
    fun `several devices are listed and the user chooses`() {
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.setWatchAddress("192.168.0.50")
        watch.found = found("192.168.0.9" to true, "192.168.0.188" to false)
        vm.findWatches()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ScanState.Done(listOf(FoundAddress("192.168.0.9", true), FoundAddress("192.168.0.188", false))), vm.state.value.scan)
        assertEquals("the address is left alone", "192.168.0.50", vm.state.value.watchAddress)
        vm.setWatchAddress("192.168.0.188")
        assertEquals("192.168.0.188", vm.state.value.watchAddress)
    }

    @Test
    fun `nothing found keeps the typed address`() {
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.setWatchAddress("192.168.0.50")
        vm.findWatches()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ScanState.Done(emptyList()), vm.state.value.scan)
        assertEquals("192.168.0.50", vm.state.value.watchAddress)
    }

    @Test
    fun `no Wi-Fi and a network that cannot be searched are told apart`() {
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        watch.found = FindOutcome.NoWifi
        vm.findWatches()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ScanState.NoWifi, vm.state.value.scan)
        watch.found = FindOutcome.NoSubnet
        vm.findWatches()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ScanState.NoSubnet, vm.state.value.scan)
    }

    @Test
    fun `a search already running is not started twice`() {
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        watch.findGate = kotlinx.coroutines.CompletableDeferred()
        vm.findWatches()
        dispatcher.scheduler.runCurrent()
        vm.findWatches()
        dispatcher.scheduler.runCurrent()
        assertEquals(1, watch.finds)
        watch.findGate!!.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ScanState.Done(emptyList()), vm.state.value.scan)
    }

    @Test
    fun `without the local network permission the search says so and does not start`() {
        platform.localNetwork = false
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.findWatches()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ScanState.NoPermission, vm.state.value.scan)
        assertEquals(0, watch.finds)
        platform.localNetwork = true
        vm.findWatches()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, watch.finds)
    }

    @Test
    fun `a failing search ends as nothing found`() {
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        watch.findFailure = java.io.IOException("boom")
        vm.findWatches()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ScanState.Done(emptyList()), vm.state.value.scan)
    }

    @Test
    fun `no search while a queue runs`() {
        addFile("a.wgt", "com.a", "1.0")
        watch.gate = kotlinx.coroutines.CompletableDeferred()
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi()
        vm.selectAll()
        dispatcher.scheduler.runCurrent()
        vm.findWatches()
        dispatcher.scheduler.runCurrent()
        assertEquals(0, watch.finds)
        watch.gate!!.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
    }

    // ---- Packages already on the watch, history and removal ------------------------------------------------

    private fun pkg(id: String, name: String = id, version: String = "1.0", type: String = "wgt") = WatchPackage(id, name, version, type)

    @Test
    fun `a version the watch already has is skipped, a newer one is sent`() {
        val same = addFile("a.wgt", "com.same", "1.0")
        val newer = addFile("b.wgt", "com.newer", "2.0")
        val older = addFile("c.wgt", "com.older", "1.0")
        watch.installed["com.same"] = "1.0"
        watch.installed["com.newer"] = "1.5"
        watch.installed["com.older"] = "3.0"
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()

        val states = vm.state.value.states
        assertEquals(ItemState.AlreadyOnWatch("1.0"), states[same.uri])
        assertEquals(ItemState.InstalledOnWatch, states[newer.uri])
        assertEquals(ItemState.AlreadyOnWatch("3.0"), states[older.uri])
        assertEquals(2, vm.state.value.skipped)
        assertEquals(1, vm.state.value.installed)
        assertEquals(0, vm.state.value.failed)
    }

    @Test
    fun `installing again can be forced and is remembered across restarts`() {
        addFile("a.wgt", "com.same", "1.0")
        watch.installed["com.same"] = "1.0"
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi()
        vm.setReinstall(true)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(settings.saved.reinstall)
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, vm.state.value.installed)
        assertEquals(null, watch.calls.single().skipRule)
    }

    @Test
    fun `the skip rule carries the package id and version`() {
        addFile("a.wgt", "com.rule", "4.2")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()
        val rule = watch.calls.single().skipRule!!
        assertEquals("com.rule", rule.id)
        assertEquals("4.2", rule.version)
    }

    @Test
    fun `what was installed is remembered and survives a restart`() {
        platform.now = 1234
        addFile("a.wgt", "com.mine", "2.1")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()

        val item = vm.state.value.remembered.single()
        assertEquals("com.mine", item.id)
        assertEquals("2.1", item.version)
        assertEquals(1234L, item.installedAt)
        assertEquals(listOf("com.mine"), history.items.map { it.id })

        val again = viewModel(folder = null)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("com.mine"), again.state.value.remembered.map { it.id })
    }

    @Test
    fun `a refused install is not remembered`() {
        addFile("a.wgt", "com.bad", "1.0")
        watch.outcomes += WatchOutcome.Refused(-12, "cert")
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi()
        vm.selectAll()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.remembered.isEmpty())
        assertTrue(history.items.isEmpty())
    }

    @Test
    fun `the watch list is loaded on demand`() {
        watch.listed = WatchListOutcome.Listed(listOf(pkg("a"), pkg("b")))
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi()
        vm.refreshWatchList()
        assertEquals(WatchListState.Loading, vm.state.value.watchList)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("a", "b"), (vm.state.value.watchList as WatchListState.Loaded).packages.map { it.id })
    }

    @Test
    fun `the watch list reports problems and is not asked in companion mode`() {
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.refreshWatchList()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, watch.lists)

        vm.wifi()
        watch.listed = WatchListOutcome.Problem(SdbError.BUSY)
        vm.refreshWatchList()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(WatchListState.Problem(SdbError.BUSY), vm.state.value.watchList)
        watch.listed = WatchListOutcome.NoWifi
        vm.refreshWatchList()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(WatchListState.NoWifi, vm.state.value.watchList)
        watch.listFailure = java.io.IOException("boom")
        vm.refreshWatchList()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(WatchListState.Problem(SdbError.DISCONNECTED), vm.state.value.watchList)
    }

    @Test
    fun `the watch list needs a valid address and the permission and is not asked twice at once`() {
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi("8.8.8.8")
        vm.refreshWatchList()
        assertTrue(vm.state.value.watchAddressInvalid)
        assertEquals(0, watch.lists)

        vm.setWatchAddress("192.168.0.9")
        platform.localNetwork = false
        vm.refreshWatchList()
        assertEquals(WatchListState.NoPermission, vm.state.value.watchList)
        platform.localNetwork = true

        watch.listGate = kotlinx.coroutines.CompletableDeferred()
        vm.refreshWatchList()
        dispatcher.scheduler.runCurrent()
        vm.refreshWatchList()
        dispatcher.scheduler.runCurrent()
        assertEquals(1, watch.lists)
        watch.listGate!!.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun vmWithMine(vararg ours: String): MainViewModel {
        history.items = ours.map { RememberedPackage(it, "1.0", it, 1) }
        watch.listed = WatchListOutcome.Listed(listOf(pkg("com.mine"), pkg("com.samsung.system"), pkg("com.also")))
        val vm = viewModel(folder = null)
        dispatcher.scheduler.advanceUntilIdle()
        vm.wifi()
        vm.refreshWatchList()
        dispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Test
    fun `only packages installed by the app can be removed`() {
        val vm = vmWithMine("com.mine")
        vm.askWatchRemoval(pkg("com.samsung.system"))
        assertEquals(null, vm.state.value.watchRemoval)
        vm.askWatchRemoval(pkg("com.mine"))
        assertTrue(vm.state.value.watchRemoval is WatchRemoval.Confirm)
        assertTrue(watch.removed.isEmpty())
    }

    @Test
    fun `a confirmed removal deletes the package, the memory and the list entry`() {
        val vm = vmWithMine("com.mine", "com.also")
        vm.askWatchRemoval(pkg("com.mine"))
        vm.confirmWatchRemoval()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("com.mine"), watch.removed)
        assertEquals(null, vm.state.value.watchRemoval)
        assertEquals(listOf("com.also"), vm.state.value.remembered.map { it.id })
        assertEquals(listOf("com.also"), history.items.map { it.id })
        assertEquals(listOf("com.samsung.system", "com.also"), (vm.state.value.watchList as WatchListState.Loaded).packages.map { it.id })
    }

    @Test
    fun `a refused removal keeps everything and says why`() {
        val vm = vmWithMine("com.mine")
        watch.removeOutcome = WatchRemoveOutcome.Refused(-999, "Undefined Error")
        vm.askWatchRemoval(pkg("com.mine"))
        vm.confirmWatchRemoval()
        dispatcher.scheduler.advanceUntilIdle()

        val failed = vm.state.value.watchRemoval as WatchRemoval.Failed
        assertEquals("Undefined Error -999", failed.refusal)
        assertEquals(listOf("com.mine"), vm.state.value.remembered.map { it.id })
        assertEquals(3, (vm.state.value.watchList as WatchListState.Loaded).packages.size)
        vm.dismissWatchRemoval()
        assertEquals(null, vm.state.value.watchRemoval)
    }

    @Test
    fun `a removal that cannot reach the watch reports the connection problem`() {
        val vm = vmWithMine("com.mine")
        watch.removeOutcome = WatchRemoveOutcome.Problem(SdbError.UNREACHABLE)
        vm.askWatchRemoval(pkg("com.mine"))
        vm.confirmWatchRemoval()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SdbError.UNREACHABLE, (vm.state.value.watchRemoval as WatchRemoval.Failed).error)
    }

    @Test
    fun `a removal in progress cannot be dismissed or started twice`() {
        val vm = vmWithMine("com.mine", "com.also")
        watch.removeGate = kotlinx.coroutines.CompletableDeferred()
        vm.askWatchRemoval(pkg("com.mine"))
        vm.confirmWatchRemoval()
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.watchRemoval is WatchRemoval.Working)
        vm.dismissWatchRemoval()
        vm.askWatchRemoval(pkg("com.also"))
        assertTrue(vm.state.value.watchRemoval is WatchRemoval.Working)
        watch.removeGate!!.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("com.mine"), watch.removed)
    }
}
