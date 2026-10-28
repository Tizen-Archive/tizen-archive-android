package dev.tizenarchive.companion.capture

import dev.tizenarchive.companion.sdb.FakeSdbd
import dev.tizenarchive.companion.sdb.JcaSdbSigner
import dev.tizenarchive.companion.sdb.SdbOptions
import dev.tizenarchive.companion.sdb.SdbError
import dev.tizenarchive.companion.sdb.SdbException
import dev.tizenarchive.companion.sdb.SdbSession
import dev.tizenarchive.companion.signature.SignatureVerdict
import dev.tizenarchive.companion.tizen.TizenPackageKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DeliveryCaptureTest {
    @TempDir
    lateinit var work: File

    private val signer = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        .let { JcaSdbSigner(it.private, it.public as RSAPublicKey) }
    private val watch = FakeSdbd()
    private val dir = DeliveryCapture.DELIVERY_DIR

    @AfterEach
    fun stop() = watch.close()

    private fun session() = SdbSession.connect(Socket(), watch.address, signer, "phone@test", SdbOptions(readTimeoutMs = 5_000))

    private fun tpk(id: String, version: String, signed: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("tizen-manifest.xml"))
            zip.write("""<manifest xmlns="http://tizen.org/ns/packages" package="$id" version="$version"><label>X</label></manifest>""".toByteArray())
            if (signed) {
                zip.putNextEntry(ZipEntry("signature1.xml"))
                zip.write("<Signature/>".toByteArray())
            }
        }
        return out.toByteArray()
    }

    private fun storeName(size: Int) = "tizenapp_1793213454602_samsungapps-000003107455-$size-100100006.wgt"

    private fun DeliveryCapture.pollAll(onReceiving: (List<Delivery>) -> Unit = {}): List<CaptureResult> =
        mutableListOf<CaptureResult>().also { results -> poll(onReceiving) { results += it } }

    @Test
    fun `a complete store delivery is copied and read`() {
        val bytes = tpk("com.example.app", "1.2.3")
        watch.files["$dir/HostStatus.xml"] = ByteArray(10)
        watch.files["$dir/${storeName(bytes.size)}"] = bytes
        session().use { session ->
            val results = DeliveryCapture(session, work).pollAll()
            val captured = assertInstanceOf(CaptureResult.Captured::class.java, results.single())
            assertEquals("com.example.app", captured.info.packageId)
            assertEquals("1.2.3", captured.info.version)
            assertEquals(TizenPackageKind.TPK, captured.info.kind)
            assertArrayEquals(bytes, captured.file.readBytes())
        }
        assertEquals(listOf("$dir/${storeName(bytes.size)}"), watch.pulls)
    }

    @Test
    fun `a file still arriving is reported and copied once complete`() {
        val bytes = tpk("com.example.app", "1.0")
        val name = storeName(bytes.size)
        watch.files["$dir/$name"] = bytes.copyOf(10)
        session().use { session ->
            val capture = DeliveryCapture(session, work)
            val seen = mutableListOf<Delivery>()
            assertTrue(capture.pollAll { seen += it }.isEmpty())
            assertEquals(listOf(Delivery(name, 10, bytes.size.toLong())), seen)
            watch.files["$dir/$name"] = bytes
            assertInstanceOf(CaptureResult.Captured::class.java, capture.pollAll().single())
            assertTrue(capture.pollAll().isEmpty())
        }
    }

    @Test
    fun `a package without the distributor signature is captured with that verdict`() {
        val bytes = tpk("com.example.app", "1.0", signed = false)
        watch.files["$dir/${storeName(bytes.size)}"] = bytes
        session().use { session ->
            val captured = DeliveryCapture(session, work).pollAll().single() as CaptureResult.Captured
            assertEquals(SignatureVerdict.UNSIGNED, captured.signature?.verdict)
        }
    }

    @Test
    fun `an incomplete companion file is offered again instead of being reported`() {
        watch.files["$dir/watcher.tpk"] = ByteArray(7)
        session().use { session ->
            val capture = DeliveryCapture(session, work)
            assertTrue(capture.pollAll().isEmpty())
            // Same size twice: copied, unreadable, so not reported and watched again
            assertTrue(capture.pollAll().isEmpty())
            watch.files["$dir/watcher.tpk"] = tpk("com.example.app", "1.0")
            assertTrue(capture.pollAll().isEmpty())
            assertInstanceOf(CaptureResult.Captured::class.java, capture.pollAll().single())
        }
        assertEquals(listOf("$dir/watcher.tpk", "$dir/watcher.tpk"), watch.pulls)
    }

    @Test
    fun `a delivery copied before the connection broke is still reported`() {
        val bytes = tpk("com.example.app", "1.0")
        val service = BreakingService(listOf(storeName(bytes.size), storeName(bytes.size + 1).replace("-100100006", "-7")), bytes)
        val results = mutableListOf<CaptureResult>()
        assertThrows(SdbException::class.java) { DeliveryCapture(service, work).poll { results += it } }
        assertInstanceOf(CaptureResult.Captured::class.java, results.single())
    }

    /** Lists two complete files, hands out the first and breaks on the second. */
    private class BreakingService(private val names: List<String>, private val bytes: ByteArray) : dev.tizenarchive.companion.sdb.SdbService {
        override val banner = "device::SM-R800::0"

        override fun shell(command: String, onLine: (String) -> Unit) = ""

        override fun push(remotePath: String, data: java.io.InputStream, mode: Int, progress: (Long) -> Unit) = Unit

        override fun list(remoteDir: String) = names.mapIndexed { i, name ->
            dev.tizenarchive.companion.sdb.RemoteEntry(name, bytes.size.toLong() + i, 0x81a4, 0)
        }

        override fun pull(remotePath: String, out: java.io.OutputStream, maxBytes: Long): Boolean {
            if (remotePath.endsWith(names[1])) throw SdbException(SdbError.DISCONNECTED, "gone")
            out.write(bytes)
            return true
        }

        override fun close() = Unit
    }

    @Test
    fun `a file that is not a package is invalid and leaves nothing behind`() {
        watch.files["$dir/${storeName(5)}"] = ByteArray(5)
        session().use { session ->
            assertInstanceOf(CaptureResult.Invalid::class.java, DeliveryCapture(session, work).pollAll().single())
        }
        assertTrue(work.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a file removed between listing and copying is missed`() {
        val bytes = tpk("com.example.app", "1.0")
        val capture = DeliveryCapture(GoneService(storeName(bytes.size), bytes.size.toLong()), work)
        assertInstanceOf(CaptureResult.Missed::class.java, capture.pollAll().single())
        assertTrue(work.listFiles().orEmpty().isEmpty())
    }

    /** Lists a file but has lost it by the time it is pulled, as when the installer is faster. */
    private class GoneService(private val name: String, private val size: Long) : dev.tizenarchive.companion.sdb.SdbService {
        override val banner = "device::SM-R800::0"

        override fun shell(command: String, onLine: (String) -> Unit) = ""

        override fun push(remotePath: String, data: java.io.InputStream, mode: Int, progress: (Long) -> Unit) = Unit

        override fun list(remoteDir: String) = listOf(dev.tizenarchive.companion.sdb.RemoteEntry(name, size, 0x81a4, 0))

        override fun pull(remotePath: String, out: java.io.OutputStream, maxBytes: Long) = false

        override fun close() = Unit
    }
}
