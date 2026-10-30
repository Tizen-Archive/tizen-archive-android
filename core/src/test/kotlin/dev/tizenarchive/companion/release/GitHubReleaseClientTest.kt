package dev.tizenarchive.companion.release

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

class GitHubReleaseClientTest {
    @TempDir
    lateinit var dir: File

    private lateinit var server: HttpServer
    private val apk = ByteArray(100_000) { (it * 3).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val path = "/Tizen-Archive/tizen-archive-android/releases/download/v0.4.0/tizen-archive-android-0.4.0.apk"
    private var served = apk
    private var answer = ""
    private var status = 200
    private var redirectTo: String? = null
    private val agents = mutableListOf<String?>()

    private val base get() = "http://127.0.0.1:${server.address.port}"

    private fun client() = GitHubReleaseClient("test-agent", apiBase = base, allowCleartext = true, isAllowedHost = { it == "127.0.0.1" })

    private fun release(tag: String = "v0.4.0", name: String = "tizen-archive-android-0.4.0.apk", digest: String? = "sha256:$sha", url: String = "$base$path", size: Int = apk.size, body: String = "Fixes") =
        """{"tag_name":"$tag","body":${"\"" + body + "\""},"extra":1,"assets":[
            {"name":"$name.sha256","size":98,"digest":"sha256:${"0".repeat(64)}","browser_download_url":"$url.sha256"},
            {"name":"$name","size":$size,${digest?.let { "\"digest\":\"$it\"," }.orEmpty()}"browser_download_url":"$url"}]}"""

    private fun reply(exchange: HttpExchange, code: Int, body: ByteArray) {
        exchange.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        answer = release()
        server.createContext("/") { ex ->
            agents += ex.requestHeaders.getFirst("User-Agent")
            when (ex.requestURI.path) {
                "/repos/Tizen-Archive/tizen-archive-android/releases/latest" -> reply(ex, status, answer.toByteArray())
                path -> {
                    val target = redirectTo
                    if (target != null) {
                        ex.responseHeaders.add("Location", target)
                        reply(ex, 302, ByteArray(0))
                    } else {
                        reply(ex, 200, served)
                    }
                }
                "/storage/file.apk" -> reply(ex, 200, served)
                else -> reply(ex, 404, ByteArray(0))
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun failure(block: () -> Unit) = assertThrows(ReleaseException::class.java, block).error

    @Test
    fun `the latest release gives its version, notes, size and checksum`() {
        val release = client().latest()
        assertEquals("0.4.0", release.version)
        assertEquals("Fixes", release.notes)
        assertEquals(apk.size.toLong(), release.size)
        assertEquals(sha, release.sha256)
        assertEquals("$base$path", release.downloadUrl)
        assertEquals("test-agent", agents.single())
    }

    @Test
    fun `notes lose control characters and are cut short`() {
        answer = release(body = "a\\u0007b\\n" + "x".repeat(3_000))
        val notes = client().latest().notes
        assertTrue(notes.startsWith("ab\n"))
        assertEquals(2_000, notes.length)
    }

    @Test
    fun `an answer that does not name this app's file is refused`() {
        val answers = listOf(
            release(tag = "0.4.0"),
            release(tag = "v0.4.0;rm"),
            release(name = "other.apk"),
            release(digest = null),
            release(digest = "md5:abc"),
            release(size = 0),
            release(url = "$base/evil/tizen-archive-android-0.4.0.apk"),
            release(url = "http://example.org$path"),
            "not json",
            """{"assets":[]}""",
        )
        for (text in answers) {
            answer = text
            assertEquals(ReleaseError.BAD_RESPONSE, failure { client().latest() }, text)
        }
    }

    @Test
    fun `a refusal by GitHub is told from a failure`() {
        status = 403
        assertEquals(ReleaseError.RATE_LIMITED, failure { client().latest() })
        status = 500
        assertEquals(ReleaseError.NETWORK, failure { client().latest() })
    }

    @Test
    fun `only HTTPS is accepted by default`() {
        val strict = GitHubReleaseClient("test-agent", apiBase = base, isAllowedHost = { true })
        assertEquals(ReleaseError.BAD_RESPONSE, failure { strict.latest() })
    }

    @Test
    fun `the default hosts are those of GitHub, the API included`() {
        for (host in listOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
            assertTrue(GitHubReleaseClient.isGitHubHost(host), host)
        }
        for (host in listOf("example.org", "github.com.example.org", "evilgithub.com", "evilgithubusercontent.com")) {
            assertFalse(GitHubReleaseClient.isGitHubHost(host), host)
        }
    }

    @Test
    fun `a download follows a redirect to an allowed host and checks the file`() {
        redirectTo = "/storage/file.apk"
        val target = File(dir, "app.apk")
        val seen = mutableListOf<Long>()
        client().download(client().latest(), target) { seen += it }
        assertEquals(apk.toList(), target.readBytes().toList())
        assertEquals(apk.size.toLong(), seen.last())
    }

    @Test
    fun `a redirect to any other host is refused`() {
        redirectTo = "http://localhost:${server.address.port}/storage/file.apk"
        val target = File(dir, "app.apk")
        assertEquals(ReleaseError.BAD_RESPONSE, failure { client().download(client().latest(), target) })
        assertFalse(target.exists())
    }

    @Test
    fun `a file that does not match is removed`() {
        val release = client().latest()
        val target = File(dir, "app.apk")
        served = apk.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertEquals(ReleaseError.CHECKSUM_MISMATCH, failure { client().download(release, target) })
        assertFalse(target.exists())
        served = apk.copyOf(500)
        assertEquals(ReleaseError.SIZE_MISMATCH, failure { client().download(release, target) })
        assertFalse(target.exists())
        served = apk + 1
        assertEquals(ReleaseError.SIZE_MISMATCH, failure { client().download(release, target) })
        assertFalse(target.exists())
    }

    @Test
    fun `a progress listener can stop the download`() {
        val target = File(dir, "app.apk")
        assertThrows(IllegalStateException::class.java) { client().download(client().latest(), target) { error("stop") } }
        assertFalse(target.exists())
    }
}
