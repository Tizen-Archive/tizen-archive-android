package dev.tizenarchive.companion.catalog

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CatalogClientTest {
    @TempDir
    lateinit var dir: File

    private lateinit var server: HttpServer
    private val file = ByteArray(200_000) { (it * 7).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) }
    private val seenAgents = mutableListOf<String?>()
    private var served: ByteArray = file

    private fun client() = CatalogClient("http://127.0.0.1:${server.address.port}", "tizen-companion-installer/test", allowCleartext = true)

    private fun reply(exchange: HttpExchange, code: Int, body: ByteArray, type: String = "application/json") {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api") { ex ->
            seenAgents += ex.requestHeaders.getFirst("User-Agent")
            val json = """{"packageId":"Dice","version":"1.0.2","name":"Dice","type":"wgt","extra":1,"builds":[
                {"verdict":"installable","artifacts":[{"sha256":"$sha","size":${file.size},"downloadUrl":"/api/artifacts/$sha/download"}]},
                {"verdict":"something-new","artifacts":[]}]}"""
            when (ex.requestURI.path) {
                "/api/packages/Dice" -> reply(ex, 200, """{"latestVersion":"1.0.2"}""".toByteArray())
                "/api/packages/Dice/versions/1.0.2" -> reply(ex, 200, json.toByteArray())
                "/api/artifacts/$sha/download" -> reply(ex, 200, served, "application/octet-stream")
                "/api/redirect" -> {
                    ex.responseHeaders.add("Location", "http://example.invalid/")
                    ex.sendResponseHeaders(302, -1)
                    ex.close()
                }
                else -> reply(ex, 404, "{}".toByteArray())
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `resolves the latest version and sends the user agent`() {
        val info = client().version("Dice", null)
        assertEquals("1.0.2", info.version)
        assertEquals(Verdict.INSTALLABLE, info.builds[0].verdict)
        assertEquals(Verdict.UNKNOWN, info.builds[1].verdict)
        assertTrue(seenAgents.all { it == "tizen-companion-installer/test" })
    }

    @Test
    fun `downloads and verifies a package`() {
        val artifact = (ArtifactSelection.select(client().version("Dice", "1.0.2")) as Selection.Found).artifact
        val target = File(dir, "p.wgt")
        var last = 0L
        client().download(artifact, target) { last = it }
        assertEquals(file.size.toLong(), last)
        assertTrue(file.contentEquals(target.readBytes()))
    }

    @Test
    fun `a corrupted download is refused and removed`() {
        val artifact = (ArtifactSelection.select(client().version("Dice", "1.0.2")) as Selection.Found).artifact
        served = file.copyOf().also { it[5] = (it[5] + 1).toByte() }
        val target = File(dir, "p.wgt")
        val error = assertThrows(CatalogException::class.java) { client().download(artifact, target) }
        assertEquals(CatalogError.CHECKSUM_MISMATCH, error.error)
        assertFalse(target.exists())
    }

    @Test
    fun `a longer or shorter body is refused`() {
        val artifact = (ArtifactSelection.select(client().version("Dice", "1.0.2")) as Selection.Found).artifact
        for (body in listOf(file + byteArrayOf(1), file.copyOf(file.size - 1))) {
            served = body
            val target = File(dir, "q.wgt")
            val error = assertThrows(CatalogException::class.java) { client().download(artifact, target) }
            assertEquals(CatalogError.SIZE_MISMATCH, error.error)
            assertFalse(target.exists())
        }
    }

    @Test
    fun `unknown package is not found`() {
        assertEquals(CatalogError.NOT_FOUND, assertThrows(CatalogException::class.java) { client().version("Nope", "1.0") }.error)
    }

    @Test
    fun `redirects are not followed and absolute urls are refused`() {
        val base = client()
        val artifact = Artifact(sha, file.size.toLong(), "/api/redirect")
        assertEquals(CatalogError.NETWORK, assertThrows(CatalogException::class.java) { base.download(artifact, File(dir, "r")) }.error)
        val absolute = Artifact(sha, file.size.toLong(), "http://evil.example/x")
        assertEquals(CatalogError.BAD_RESPONSE, assertThrows(CatalogException::class.java) { base.download(absolute, File(dir, "s")) }.error)
    }

    @Test
    fun `cleartext needs an explicit opt in`() {
        assertThrows(IllegalArgumentException::class.java) { CatalogClient("http://example.org", "ua") }
    }
}
