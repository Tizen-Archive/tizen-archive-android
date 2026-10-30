package dev.tizenarchive.companion.release

import dev.tizenarchive.companion.copyHashed
import dev.tizenarchive.companion.readUpTo
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

public enum class ReleaseError { NETWORK, RATE_LIMITED, BAD_RESPONSE, SIZE_MISMATCH, CHECKSUM_MISMATCH }

public class ReleaseException(public val error: ReleaseError, message: String, cause: Throwable? = null) : Exception(message, cause)

/** The APK of a published release; [notes] are the release's text, plain and short. */
public data class AppRelease(val version: String, val notes: String, val size: Long, val sha256: String, val downloadUrl: String)

/** What the app needs to know about its own releases; [GitHubReleaseClient] is the real one, tests use fakes. */
public interface AppReleaseApi {
    public fun latest(): AppRelease

    /** Downloads and verifies the APK; the partial file is removed on any failure. [progress] may throw to stop the download. */
    public fun download(release: AppRelease, destination: File, progress: (Long) -> Unit = {})
}

/**
 * Reads the latest release of [repository] from GitHub. Nothing from an answer chooses where the APK comes from: it must be the asset of
 * this repository with the name of the version, and every redirect is checked against [isAllowedHost], because GitHub serves
 * the file from another host. Only HTTPS is accepted unless [allowCleartext] is set (tests).
 */
public class GitHubReleaseClient(
    private val userAgent: String,
    private val repository: String = DEFAULT_REPOSITORY,
    private val apiBase: String = "https://api.github.com",
    private val allowCleartext: Boolean = false,
    private val isAllowedHost: (String) -> Boolean = ::isGitHubHost,
) : AppReleaseApi {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    override fun latest(): AppRelease {
        val bytes = connect(URI("$apiBase/repos/$repository/releases/latest"), "application/vnd.github+json").use { connection ->
            try {
                connection.inputStream.use { it.readUpTo(MAX_JSON_BYTES + 1) }
            } catch (e: IOException) {
                throw ReleaseException(ReleaseError.NETWORK, e.message ?: "Network error", e)
            }
        }
        if (bytes.size > MAX_JSON_BYTES) throw ReleaseException(ReleaseError.BAD_RESPONSE, "The response is too large")
        val answer = try {
            json.decodeFromString<ReleaseAnswer>(bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw ReleaseException(ReleaseError.BAD_RESPONSE, "Unexpected response from GitHub", e)
        }
        val version = TAG.matchEntire(answer.tag)?.groupValues?.get(1) ?: throw bad("Unexpected tag")
        val name = "tizen-archive-android-$version.apk"
        val asset = answer.assets.singleOrNull { it.name == name } ?: throw bad("The release has no $name")
        val sha256 = asset.digest?.let { DIGEST.matchEntire(it) }?.groupValues?.get(1)?.lowercase() ?: throw bad("The release does not give a checksum")
        if (asset.size !in 1..MAX_APK_BYTES) throw bad("Unexpected size")
        val url = try {
            URI(asset.url)
        } catch (e: java.net.URISyntaxException) {
            throw bad("Unexpected URL")
        }
        if (!allowed(url) || url.path != "/$repository/releases/download/${answer.tag}/$name") throw bad("Unexpected URL")
        val notes = answer.notes.orEmpty().filter { it == '\n' || !it.isISOControl() }.trim().take(MAX_NOTES)
        return AppRelease(version, notes, asset.size, sha256, asset.url)
    }

    override fun download(release: AppRelease, destination: File, progress: (Long) -> Unit) {
        try {
            connect(URI(release.downloadUrl), "application/octet-stream").use { connection ->
                val (total, actual) = connection.inputStream.use { copyHashed(it, destination, release.size, progress) }
                if (total != release.size) throw ReleaseException(ReleaseError.SIZE_MISMATCH, "Expected ${release.size} bytes, got $total")
                if (actual != release.sha256) throw ReleaseException(ReleaseError.CHECKSUM_MISMATCH, "SHA-256 mismatch")
            }
        } catch (e: Exception) {
            destination.delete()
            if (e is IOException) throw ReleaseException(ReleaseError.NETWORK, e.message ?: "Network error", e)
            throw e
        }
    }

    private fun bad(message: String) = ReleaseException(ReleaseError.BAD_RESPONSE, message)

    private fun allowed(uri: URI): Boolean = (uri.scheme == "https" || (allowCleartext && uri.scheme == "http")) && uri.host?.let(isAllowedHost) == true

    /** Follows redirects by hand, so that each of them passes the same check as the first address. */
    private fun connect(first: URI, accept: String): Connection {
        var uri = first
        repeat(MAX_REDIRECTS + 1) {
            if (!allowed(uri)) throw bad("Unexpected address")
            try {
                val connection = uri.toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", accept)
                when (val code = connection.responseCode) {
                    200 -> return Connection(connection)
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                        connection.disconnect()
                        uri = try {
                            uri.resolve(location ?: throw bad("A redirect without an address"))
                        } catch (e: IllegalArgumentException) {
                            throw bad("Unexpected address")
                        }
                    }
                    403, 429 -> {
                        connection.disconnect()
                        throw ReleaseException(ReleaseError.RATE_LIMITED, "GitHub refused the request: HTTP $code")
                    }
                    else -> {
                        connection.disconnect()
                        throw ReleaseException(ReleaseError.NETWORK, "GitHub answered HTTP $code")
                    }
                }
            } catch (e: IOException) {
                throw ReleaseException(ReleaseError.NETWORK, e.message ?: "Network error", e)
            }
        }
        throw bad("Too many redirects")
    }

    private class Connection(private val connection: HttpURLConnection) : AutoCloseable {
        val inputStream get() = connection.inputStream

        override fun close() = connection.disconnect()
    }

    @Serializable
    private data class ReleaseAnswer(
        @SerialName("tag_name") val tag: String,
        @SerialName("body") val notes: String? = null,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        val size: Long,
        val digest: String? = null,
        @SerialName("browser_download_url") val url: String,
    )

    public companion object {
        public const val DEFAULT_REPOSITORY: String = "Tizen-Archive/tizen-archive-android"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_JSON_BYTES = 1024 * 1024
        private const val MAX_APK_BYTES = 128L * 1024 * 1024
        private const val MAX_NOTES = 2_000
        private const val MAX_REDIRECTS = 5
        private val TAG = Regex("v(\\d{1,6}(?:\\.\\d{1,6}){1,3})")
        private val DIGEST = Regex("sha256:([0-9a-fA-F]{64})")

        /** The API, the release page and the hosts that release files are redirected to. */
        internal fun isGitHubHost(host: String): Boolean = host == "api.github.com" || host == "github.com" || host.endsWith(".githubusercontent.com")
    }
}
