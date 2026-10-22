package dev.tizenarchive.companion.sdb

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WatchInstallerTest {
    @TempDir
    lateinit var dir: File

    private class FakeService : SdbService {
        override val banner = "device::SM-R800::0"
        val steps = mutableListOf<String>()
        val answers = mutableMapOf<String, String>()
        var failOn: String? = null
        var pushed: ByteArray? = null

        override fun shell(command: String, onLine: (String) -> Unit): String {
            steps += command
            if (failOn != null && command.startsWith(failOn!!)) throw SdbException(SdbError.DISCONNECTED, "gone")
            val out = answers.entries.firstOrNull { command.startsWith(it.key) }?.value ?: ""
            out.lines().forEach(onLine)
            return out
        }

        override fun push(remotePath: String, data: InputStream, mode: Int, progress: (Long) -> Unit) {
            steps += "push $remotePath"
            val bytes = data.readBytes()
            pushed = bytes
            progress(bytes.size.toLong() / 2)
            progress(bytes.size.toLong())
        }

        override fun close() = Unit
    }

    private fun packageFile(content: ByteArray = ByteArray(5_000) { it.toByte() }) = File(dir, "x.wgt").also { it.writeBytes(content) }

    private val ok = "key[install_percent] val[40]\nkey[install_percent] val[90]\nkey[end] val[ok]\nspend time for pkgcmd is [4000]ms"

    @Test
    fun `an install makes the directory, uploads, installs and cleans up`() {
        val service = FakeService().also { it.answers["pkgcmd -i"] = ok }
        val file = packageFile()
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val events = mutableListOf<InstallProgress>()
        val result = WatchInstaller(service).install(file, PackageType.WGT) { events += it }

        assertTrue(result.success)
        val remote = "/home/owner/share/tmp/sdk_tools/${sha.take(16)}.wgt"
        assertEquals(
            listOf(
                "mkdir -p /home/owner/share/tmp/sdk_tools; rm -f /home/owner/share/tmp/sdk_tools/${"[0-9a-f]".repeat(16)}.wgt /home/owner/share/tmp/sdk_tools/${"[0-9a-f]".repeat(16)}.tpk",
                "push $remote",
                "pkgcmd -i -t wgt -p $remote -w 2>&1",
                "rm -f $remote",
            ),
            service.steps,
        )
        assertEquals(file.readBytes().toList(), service.pushed!!.toList())
        val uploads = events.filterIsInstance<InstallProgress.Uploading>()
        assertEquals(listOf(2_500L, 5_000L), uploads.map { it.sent })
        assertTrue(uploads.all { it.total == 5_000L })
        assertEquals(listOf<Int?>(null, 40, 90), events.filterIsInstance<InstallProgress.Installing>().map { it.percent })
    }

    @Test
    fun `a tpk goes through the same steps with its own type`() {
        val service = FakeService().also { it.answers["pkgcmd -i"] = ok }
        WatchInstaller(service).install(File(dir, "y.tpk").also { it.writeBytes(ByteArray(10)) }, PackageType.TPK)
        assertTrue(service.steps.any { it.startsWith("pkgcmd -i -t tpk -p ") && it.endsWith(".tpk -w 2>&1") })
    }

    @Test
    fun `a refusal is returned and the file is still removed`() {
        val service = FakeService().also { it.answers["pkgcmd -i"] = "key[error] val[-24]\nkey[end] val[fail]\nprocessing result : Image error [-24] failed" }
        val result = WatchInstaller(service).install(packageFile(), PackageType.WGT)
        assertFalse(result.success)
        assertEquals(-24, result.code)
        assertTrue(service.steps.last().startsWith("rm -f "))
    }

    @Test
    fun `a lost connection during the install is raised, cleanup does not hide it`() {
        val service = FakeService().also { it.failOn = "pkgcmd -i" }
        val error = assertThrows(SdbException::class.java) { WatchInstaller(service).install(packageFile(), PackageType.WGT) }
        assertEquals(SdbError.DISCONNECTED, error.error)
        assertTrue(service.steps.last().startsWith("rm -f "), "cleanup was tried")
    }

    @Test
    fun `the remote name and the commands contain only safe characters`() {
        val service = FakeService().also { it.answers["pkgcmd -i"] = ok }
        WatchInstaller(service).install(File(dir, "evil name; rm -rf %2F\$(id).wgt").also { it.writeBytes(ByteArray(10)) }, PackageType.WGT)
        for (step in service.steps) assertTrue(Regex("[A-Za-z0-9 /._:>&=;\\[\\]-]+").matches(step), step)
    }

    @Test
    fun `an empty package is refused before anything is sent`() {
        val service = FakeService()
        assertThrows(java.io.IOException::class.java) { WatchInstaller(service).install(packageFile(ByteArray(0)), PackageType.WGT) }
        assertTrue(service.steps.isEmpty())
    }

    @Test
    fun `list reads the lines of pkgcmd -l`() {
        val service = FakeService().also {
            it.answers["pkgcmd -l"] = listOf(
                "system apps\tpkg_type [wgt]\tpkgid [55V5m8QGTw]\tname [White Light]\tversion [1.0.4]\tstorage [internal]",
                "system apps\tpkg_type [tpk]\tpkgid [com.samsung.w-music-player]\tname [Музыка]\tversion [2.4.56]\tstorage [internal]",
                "system apps\tpkg_type [tpk]\tpkgid [com.x]\tname [A [beta] app]\tversion [1.0]\tstorage [internal]",
                "spend time for pkgcmd is [10]ms",
                "",
            ).joinToString("\n")
        }
        val packages = WatchInstaller(service).list()
        assertEquals(listOf("55V5m8QGTw", "com.samsung.w-music-player", "com.x"), packages.map { it.id })
        assertEquals(listOf("White Light", "Музыка", "A [beta] app"), packages.map { it.name })
        assertEquals(listOf("1.0.4", "2.4.56", "1.0"), packages.map { it.version })
        assertEquals(listOf("wgt", "tpk", "tpk"), packages.map { it.type })
    }

    @Test
    fun `the installed version is read from the list`() {
        val service = FakeService().also {
            it.answers["pkgcmd -l"] = "system apps\tpkg_type [wgt]\tpkgid [55V5m8QGTw]\tname [White Light]\tversion [1.0.4]\tstorage [internal]\n" +
                "system apps\tpkg_type [tpk]\tpkgid [com.x]\tname [X]\tversion [2.0]\tstorage [internal]"
        }
        val installer = WatchInstaller(service)
        assertEquals("1.0.4", installer.installedVersion("55V5m8QGTw"))
        assertEquals("2.0", installer.installedVersion("com.x"))
        assertEquals(null, installer.installedVersion("com.missing"))
        assertEquals(null, installer.installedVersion("55V5m8QG"))
    }

    @Test
    fun `remove runs one fixed command for a valid id`() {
        val service = FakeService().also { it.answers["pkgcmd -u"] = "key[end] val[ok]" }
        assertTrue(WatchInstaller(service).remove("com.example.App-1").success)
        assertEquals(listOf("pkgcmd -u -n com.example.App-1 2>&1"), service.steps)
    }

    @Test
    fun `remove refuses ids that could be options or commands`() {
        val service = FakeService()
        val installer = WatchInstaller(service)
        for (id in listOf("", "-a", "a b", "a;rm", "a\$(id)", "a`id`", "a\nb", "a|b", "a&b", "a/b", "'a'", "a".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java, { installer.remove(id) }, "'$id'")
        }
        assertTrue(service.steps.isEmpty())
        assertTrue(WatchInstaller.isPackageId("a".repeat(128)))
    }
}
