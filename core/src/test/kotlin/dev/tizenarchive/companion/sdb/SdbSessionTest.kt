package dev.tizenarchive.companion.sdb

import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SdbSessionTest {
    private val signer = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        .let { JcaSdbSigner(it.private, it.public as RSAPublicKey) }
    private val servers = mutableListOf<FakeSdbd>()

    private fun watch(maxData: Int = 262_144) = FakeSdbd(maxData).also { servers += it }

    private fun connect(watch: FakeSdbd, options: SdbOptions = SdbOptions(readTimeoutMs = 5_000, confirmTimeoutMs = 5_000)) =
        SdbSession.connect(Socket(), watch.address, signer, "phone@test", options)

    @AfterEach
    fun stop() = servers.forEach { it.close() }

    private fun errorOf(block: () -> Unit): SdbError = assertThrows(SdbException::class.java, block).error

    @Test
    fun `a new key is confirmed once and then remembered`() {
        val watch = watch()
        connect(watch).use { assertEquals("device::SM-R800::0", it.banner) }
        assertEquals(1, watch.confirmations.get())
        assertEquals(listOf("phone@test"), watch.keyNames)
        connect(watch).use { assertEquals("device::SM-R800::0", it.banner) }
        assertEquals(1, watch.confirmations.get())
    }

    @Test
    fun `a denied key ends in a refusal`() {
        val watch = watch().also { it.allowNewKeys = false }
        assertEquals(SdbError.AUTH_REJECTED, errorOf { connect(watch) })
    }

    @Test
    fun `a key nobody confirms times out`() {
        val watch = watch().also { it.confirmDelayMs = 3_000 }
        assertEquals(SdbError.AUTH_TIMEOUT, errorOf { connect(watch, SdbOptions(readTimeoutMs = 5_000, confirmTimeoutMs = 300)) })
    }

    @Test
    fun `a watch that closes at once is reported as busy`() {
        val watch = watch().also { it.busy = true }
        assertEquals(SdbError.BUSY, errorOf { connect(watch) })
    }

    @Test
    fun `a watch that resets the connection is reported as busy too`() {
        val watch = watch().also {
            it.busy = true
            it.busyReset = true
        }
        assertEquals(SdbError.BUSY, errorOf { connect(watch) })
    }

    @Test
    fun `an address that refuses is unreachable`() {
        val port = ServerSocket(0).use { it.localPort }
        val error = errorOf { SdbSession.connect(Socket(), InetSocketAddress("127.0.0.1", port), signer, "phone@test", SdbOptions(connectTimeoutMs = 500)) }
        assertEquals(SdbError.UNREACHABLE, error)
    }

    @Test
    fun `something that is not sdb is a protocol error`() {
        val watch = watch().also { it.sendGarbage = true }
        assertEquals(SdbError.PROTOCOL, errorOf { connect(watch) })
    }

    @Test
    fun `shell returns the output and reports whole lines as they arrive`() {
        val watch = watch().also {
            it.shells["pkgcmd -l"] = "first line\r\nsecond line\nlast without newline"
            it.shellChunk.set(7)
        }
        connect(watch).use { session ->
            val lines = mutableListOf<String>()
            val all = session.shell("pkgcmd -l") { lines += it }
            assertEquals("first line\r\nsecond line\nlast without newline", all)
            assertEquals(listOf("first line", "second line", "last without newline"), lines)
        }
    }

    @Test
    fun `an empty command output is an empty string`() {
        val watch = watch()
        connect(watch).use { assertEquals("", it.shell("true")) }
    }

    @Test
    fun `push sends a file in chunks the watch accepts and reports progress`() {
        val watch = watch(maxData = 4_096)
        val data = ByteArray(50_000) { (it * 31).toByte() }
        connect(watch).use { session ->
            val progress = mutableListOf<Long>()
            session.push("/home/owner/share/tmp/sdk_tools/a.wgt", ByteArrayInputStream(data), 420) { progress += it }
            assertEquals(data.size.toLong(), progress.last())
            assertEquals(progress.sorted(), progress)
        }
        assertArrayEquals(data, watch.files["/home/owner/share/tmp/sdk_tools/a.wgt"])
        assertEquals(420, watch.modes["/home/owner/share/tmp/sdk_tools/a.wgt"])
        assertTrue(watch.writeSizes.all { it <= 4_096 }, "no write larger than the watch's maxdata: ${watch.writeSizes.max()}")
    }

    @Test
    fun `an empty file is pushed too`() {
        val watch = watch()
        connect(watch).use { it.push("/tmp/empty.bin", ByteArrayInputStream(ByteArray(0))) }
        assertEquals(0, watch.files["/tmp/empty.bin"]!!.size)
    }

    @Test
    fun `a closed service that sends again does not confuse the next call`() {
        val watch = watch().also {
            it.staleClose = true
            it.shells["echo ok"] = "ok\n"
        }
        connect(watch).use { session ->
            session.push("/tmp/x.bin", ByteArrayInputStream(ByteArray(10)))
            assertEquals("ok\n", session.shell("echo ok"))
            assertEquals("ok\n", session.shell("echo ok"))
        }
    }

    @Test
    fun `remote paths with odd characters are refused before anything is sent`() {
        val watch = watch()
        connect(watch).use { session ->
            for (path in listOf("relative.bin", "/tmp/a b", "/tmp/a,0644", "/tmp/a;rm", "/tmp/\u0000", "", "/")) {
                assertThrows(IllegalArgumentException::class.java, { session.push(path, ByteArrayInputStream(ByteArray(1))) }, path)
            }
        }
        assertTrue(watch.files.isEmpty())
    }

    @Test
    fun `a call on a closed session fails as disconnected`() {
        val watch = watch()
        val session = connect(watch)
        session.close()
        assertEquals(SdbError.DISCONNECTED, errorOf { session.shell("x") })
    }
}
